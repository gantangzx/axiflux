#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 THIRD-PARTY-LICENSES.md（第三方依赖与许可清单）

用途：商业化交付合规 —— 交付物必须能回答「你用了哪些第三方组件、分别什么许可、有什么义务」。
      本脚本直接读取发布 fat jar 的 BOOT-INF/lib，保证清单与产物一致（而非靠人写）。

用法:
  python3 gen-third-party-licenses.py <tianshu-app.jar> [-o THIRD-PARTY-LICENSES.md] [--version 1.0.0]

已知许可仅覆盖清单内常见组件（KNOWN）；未覆盖项标为「待核验」，需以各上游 POM 为准。
高风险许可（LGPL/GPL/EPL/MPL 等 copyleft）单独列出，提示法务确认义务。
"""
import argparse
import os
import re
import sys
import zipfile
from datetime import date

# 常见组件许可（以官方仓库为准；未列出的需人工核验）
KNOWN = {
    "spring-boot": "Apache-2.0", "spring-core": "Apache-2.0", "spring-context": "Apache-2.0",
    "spring-web": "Apache-2.0", "spring-webflux": "Apache-2.0", "spring-security-core": "Apache-2.0",
    "spring-security-oauth2-resource-server": "Apache-2.0", "spring-data-commons": "Apache-2.0",
    "spring-data-jpa": "Apache-2.0", "spring-tx": "Apache-2.0", "spring-aop": "Apache-2.0",
    "spring-jdbc": "Apache-2.0", "spring-beans": "Apache-2.0", "spring-expression": "Apache-2.0",
    "spring-jcl": "Apache-2.0", "spring-aspects": "Apache-2.0", "spring-orm": "Apache-2.0",
    "hibernate-core": "LGPL-2.1-or-later", "hibernate-models": "Apache-2.0",
    "jakarta.persistence-api": "EPL-2.0 / GPL-2.0(dual)", "jakarta.annotation-api": "EPL-2.0 / GPL-2.0(dual)",
    "jakarta.validation-api": "Apache-2.0", "jakarta.transaction-api": "EPL-2.0 / GPL-2.0(dual)",
    "jakarta.mail-api": "EPL-2.0 / GPL-2.0(dual)", "jakarta.activation-api": "EPL-2.0 / GPL-2.0(dual)",
    "jakarta.inject-api": "Apache-2.0", "jakarta.xml.bind-api": "EPL-2.0 / GPL-2.0(dual)",
    "netty-buffer": "Apache-2.0", "netty-common": "Apache-2.0", "netty-handler": "Apache-2.0",
    "netty-codec-http": "Apache-2.0", "netty-transport": "Apache-2.0", "netty-resolver": "Apache-2.0",
    "jackson-core": "Apache-2.0", "jackson-databind": "Apache-2.0", "jackson-annotations": "Apache-2.0",
    "jackson-dataformat-yaml": "Apache-2.0", "jackson-datatype-jsr310": "Apache-2.0",
    "logback-classic": "EPL-1.0 / LGPL-2.1(dual)", "logback-core": "EPL-1.0 / LGPL-2.1(dual)",
    "slf4j-api": "MIT", "log4j-api": "Apache-2.0", "log4j-to-slf4j": "Apache-2.0",
    "postgresql": "BSD-2-Clause", "HikariCP": "Apache-2.0", "lettuce-core": "Apache-2.0",
    "jedis": "MIT", "flyway-core": "Apache-2.0", "flyway-database-postgresql": "Apache-2.0",
    "langchain4j-core": "Apache-2.0", "langchain4j-open-ai": "Apache-2.0",
    "langchain4j-anthropic": "Apache-2.0", "langchain4j-http-client": "Apache-2.0",
    "guava": "Apache-2.0", "gson": "Apache-2.0", "okhttp": "Apache-2.0", "okio": "Apache-2.0",
    "grpc-core": "Apache-2.0", "grpc-netty-shaded": "Apache-2.0", "grpc-protobuf": "Apache-2.0",
    "grpc-stub": "Apache-2.0", "nimbus-jose-jwt": "Apache-2.0",
    "micrometer-core": "Apache-2.0", "micrometer-registry-prometheus": "Apache-2.0",
    "prometheus-metrics-core": "Apache-2.0",
    "commons-lang3": "Apache-2.0", "commons-codec": "Apache-2.0", "commons-logging": "Apache-2.0",
    "org.eclipse.jgit": "EDL-1.0 (Eclipse Distribution License, BSD-3 style)",
    "aviator": "LGPL-3.0-or-later", "kotlin-stdlib": "Apache-2.0",
    "snakeyaml": "Apache-2.0", "thymeleaf": "Apache-2.0", "shedlock-spring": "Apache-2.0",
    "springdoc-openapi-starter-webflux-ui": "Apache-2.0", "jtokkit": "MIT",
    "angus-mail": "EPL-2.0 / GPL-2.0(dual)", "angus-activation": "EPL-2.0 / GPL-2.0(dual)",
    "byte-buddy": "Apache-2.0", "antlr4-runtime": "BSD-3-Clause", "aspectjweaver": "EPL-2.0",
    "jboss-logging": "Apache-2.0", "classmate": "Apache-2.0", "jaxb-runtime": "EPL-2.0 / GPL-2.0(dual)",
    "istack-commons-runtime": "EPL-2.0 / GPL-2.0(dual)", "jul-to-slf4j": "MIT",
    "HdrHistogram": "CC0-1.0 / BSD-2-Clause(dual)", "context-propagation": "Apache-2.0",
    "reactor-core": "Apache-2.0", "reactor-netty-core": "Apache-2.0", "reactor-netty-http": "Apache-2.0",
}

COPYLEFT_HINTS = ("LGPL", "GPL", "EPL", "MPL", "CDDL", "CPL")

# 组件族前缀规则（同族组件在同一上游项目内使用统一许可；以官方发布为准）
PREFIX_LICENSES = [
    ("spring-", "Apache-2.0"),
    ("netty-", "Apache-2.0"),
    ("grpc-", "Apache-2.0"),
    ("micrometer-", "Apache-2.0"),
    ("prometheus-metrics-", "Apache-2.0"),
    ("kotlin-stdlib", "Apache-2.0"),
    ("protobuf-", "BSD-3-Clause"),
    ("proto-google-common-protos", "Apache-2.0"),
    ("reactor-", "Apache-2.0"),
    ("okhttp", "Apache-2.0"),
    ("okio", "Apache-2.0"),
    ("reactive-streams", "CC0-1.0"),
    ("shedlock-", "Apache-2.0"),
    ("langchain4j-", "Apache-2.0"),
    ("retrofit", "Apache-2.0"),
    ("jakarta.", "EPL-2.0 / GPL-2.0(dual)"),
    ("angus-", "EPL-2.0 / GPL-2.0(dual)"),
    ("jaxb-", "EPL-2.0 / GPL-2.0(dual)"),
    ("istack-", "EPL-2.0 / GPL-2.0(dual)"),
    ("client", "Apache-2.0"),
    ("JavaEWAH", "Apache-2.0"),
    ("perfmark-api", "Apache-2.0"),
    ("failureaccess", "Apache-2.0"),
    ("listenablefuture", "Apache-2.0"),
    ("converter-jackson", "Apache-2.0"),
    ("jsr305", "BSD-3-Clause"),
    ("checker-qual", "MIT"),
    ("jspecify", "Apache-2.0"),
    ("animal-sniffer-annotations", "MIT"),
    ("error_prone_annotations", "Apache-2.0"),
    ("j2objc-annotations", "Apache-2.0"),
    ("annotations", "Apache-2.0"),
    ("redis-authx-core", "Apache-2.0"),
    ("springdoc-", "Apache-2.0"),
    ("swagger-", "Apache-2.0"),
    ("webjars-locator", "Apache-2.0"),
    ("txw2", "EPL-2.0 / GPL-2.0(dual)"),
]


def license_of(name):
    if name in KNOWN:
        return KNOWN[name]
    for prefix, lic in PREFIX_LICENSES:
        if name.startswith(prefix):
            return lic + "（按组件族推定）"
    return ""
VERSION_RE = re.compile(r"^(?P<name>.+?)-(?P<version>\d[^-]*(?:[.\-][A-Za-z0-9_+.\-]*)?)\.jar$")
CLASSIFIER_RE = re.compile(r"-(linux|osx|windows|aarch_64|x86_64|classes)(-[A-Za-z0-9_]+)*$")


def split_name(jar):
    base = jar[:-4] if jar.endswith(".jar") else jar
    base = CLASSIFIER_RE.sub("", base)
    m = VERSION_RE.match(base + ".jar")
    if m:
        return m.group("name"), m.group("version")
    parts = base.rsplit("-", 1)
    return (parts[0], parts[1]) if len(parts) == 2 else (base, "")


def collect(jar_path):
    zf = zipfile.ZipFile(jar_path)
    libs = sorted(os.path.basename(n) for n in zf.namelist() if n.startswith("BOOT-INF/lib/") and n.endswith(".jar"))
    rows, own = {}, {}
    for l in libs:
        name, ver = split_name(l)
        # 本项目自有模块不计入第三方清单
        if name.startswith("tianshu-"):
            own.setdefault(name, set()).add(ver)
            continue
        rows.setdefault(name, set()).add(ver)
    return rows, own


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jar")
    ap.add_argument("-o", "--out", default="THIRD-PARTY-LICENSES.md")
    ap.add_argument("--version", default="0.1.0-SNAPSHOT")
    args = ap.parse_args()

    rows, own = collect(args.jar)
    copyleft, unknown = [], []
    for name in sorted(rows):
        lic = license_of(name)
        if not lic:
            unknown.append(name)
        elif any(h in lic for h in COPYLEFT_HINTS):
            copyleft.append((name, ", ".join(sorted(rows[name])), lic))

    lines = []
    lines.append("# 第三方依赖与许可清单（THIRD-PARTY LICENSES）\n")
    lines.append("> 产品：天枢 Agent 平台（Tianshu Agent Platform） 版本：%s  \n" % args.version)
    lines.append("> 生成时间：%s ｜ 生成方式：`scripts/ops/gen-third-party-licenses.py` 直读发布 jar 的 `BOOT-INF/lib`  \n" % date.today().isoformat())
    lines.append("> 本清单与 jar 内容一一对应（共 %d 个第三方 jar，%d 个不同组件）。\n" % (
        sum(len(v) for v in rows.values()) or sum(1 for _ in rows), len(rows)))
    lines.append("\n## 0. 声明\n")
    lines.append("- 本产品自身代码以 **Apache-2.0** 许可（社区版）与 **Tianshu Enterprise License 1.0**（企业版模块，见 `LICENSE-EE.md`）分发。\n")
    lines.append("- 本产品**以二进制依赖形式**（未修改源码）使用以下第三方组件；各组件的版权归其各自作者所有，许可全文见各上游项目。\n")
    lines.append("- 交付时随附 `LICENSE`（Apache-2.0 正文）、`NOTICE`（版权与归属声明）与本清单。\n")
    lines.append("- 表内许可为**依据上游公开发布信息整理**，最终以各组件仓库/发行包内的 LICENSE 文件为准；带 ⚠ 的 copyleft 组件建议由法务在商务合同前确认义务履行方式。\n")

    lines.append("\n## 1. 需法务确认的 Copyleft / 弱 Copyleft 组件（⚠）\n")
    if copyleft:
        lines.append("| 组件 | 版本 | 许可 | 义务提示 |\n|---|---|---|---|\n")
        for n, v, l in copyleft:
            tip = "以未修改的二进制依赖形式使用；如对外分发需随附许可全文并保证可替换/可重新链接（弱 copyleft 常见要求）"
            if "GPL" in l and "LGPL" not in l and "EPL" not in l:
                tip = "强 copyleft，需法务评估（当前为二进制依赖使用，仍应确认无衍生义务）"
            lines.append("| ⚠ %s | %s | %s | %s |\n" % (n, v, l, tip))
    else:
        lines.append("（未检出带 copyleft 标记的组件）\n")
    lines.append("\n> 说明：Hibernate ORM、Aviator、Logback、Jakarta EE API（EPL/GPL 双许可）等为行业常见依赖，\n")
    lines.append("> 双许可组件通常在「EPL 或 GPL」中择一，商业分发场景普遍选择 EPL 分支；请法务确认后归档结论。\n")

    lines.append("\n## 2. 全量组件清单\n")
    lines.append("| # | 组件 | 版本 | 许可 |\n|---|---|---|---|\n")
    for i, name in enumerate(sorted(rows), 1):
        lic = license_of(name) or "**待核验**"
        lines.append("| %d | %s | %s | %s |\n" % (i, name, ", ".join(sorted(rows[name])), lic))

    if unknown:
        lines.append("\n## 3. 待核验组件（%d 个）\n" % len(unknown))
        lines.append("以下组件未纳入内置已知许可表，需逐项核对上游 POM / LICENSE：\n\n")
        lines.append(", ".join("`%s`" % u for u in unknown) + "\n")

    lines.append("\n> 本产品自有模块（`%s`）不计入第三方清单；其版权归 gantang 所有，许可见 `LICENSE` / `LICENSE-EE.md`。\n" % "`, `".join(sorted(own)))
    lines.append("\n## 4. 维护要求\n")
    lines.append("- 每次发布（含依赖升级）后重新执行本脚本，确保清单与产物一致。\n")
    lines.append("- 建议并行接入 SCA 扫描（OWASP Dependency-Check / Trivy / Snyk），输出漏洞报告归档。\n")
    lines.append("- 客户要求提供 SBOM 时，可输出 CycloneDX：`mvn org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom`。\n")

    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("".join(lines))
    print("written %s | components=%d | copyleft=%d | unknown=%d" % (args.out, len(rows), len(copyleft), len(unknown)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
