#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
AxiFlux 发布包完整性校验 (check-package.py)

用途：在打包后、交付前，验证 fat jar / 离线分发包是否"可交付"。
      防止出现「jar 里没有前端静态资源」「缺少 Flyway 迁移」「薄 jar（无 BOOT-INF/lib）」等
      现场才发现的事故（本项目 2026-09-24 真实踩过：失败的 -Pee 构建把 app jar 覆盖成薄 jar，
      repackage 未执行，导致服务能起但控制台 / 返回 5xx 或超时）。

用法:
  python3 check-package.py <path-to-axiflux-app.jar>
  python3 check-package.py <path-to-axiflux-offline.tar.gz|zip>   # 也支持离线分发包
  python3 check-package.py --dist-dir <解包目录>

退出码: 0=通过  1=有 FAIL  2=用法错误
"""
import io
import os
import sys
import tarfile
import zipfile

FAIL = []
WARN = []
OK = []
# 分发形态期望：None=自动（EE 存在只作提示）；False=社区包（禁止 EE）；True=企业包（要求 EE）
EXPECT_EE = None

REQUIRED_JAR_PREFIXES = [
    ("app-classes", "BOOT-INF/classes/com/gantang/axiflux/", 1),
    ("spring-boot-libs", "BOOT-INF/lib/", 60),
    ("static-index", "BOOT-INF/classes/static/index.html", 1),
    ("static-assets", "BOOT-INF/classes/static/assets/", 1),
]

REQUIRED_ASSETS = ["axiflux.svg", "application.yml", "application-prod.yml", "application-local.yml"]


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return "%.1f%s" % (n, unit)
        n /= 1024.0
    return "%.1fTB" % n


def check_jar(path):
    print("=" * 71)
    print(" AxiFlux 发布包完整性校验")
    print(" 目标: %s" % path)
    print(" 大小: %s" % human(os.path.getsize(path)))
    print("=" * 71)

    try:
        zf = zipfile.ZipFile(path)
        names = zf.namelist()
    except Exception as e:  # noqa: BLE001
        print("[FAIL] 无法作为 jar 读取: %s" % e)
        return 1

    lib_count = sum(1 for n in names if n.startswith("BOOT-INF/lib/"))
    if lib_count == 0:
        FAIL.append(
            "BOOT-INF/lib 为空 → 这是『薄 jar』，spring-boot-maven-plugin repackage 未执行"
            "（常见原因：构建时 app 进程占用 jar，repackage 重命名 .original 失败）。"
            "请先停服务再打包。"
        )
    else:
        OK.append("BOOT-INF/lib 依赖 %d 个" % lib_count)

    for label, prefix, minimum in REQUIRED_JAR_PREFIXES:
        cnt = sum(1 for n in names if n.startswith(prefix))
        if label == "spring-boot-libs":
            continue
        if cnt < minimum:
            FAIL.append("缺少 %s（匹配 %s 的条目 %d 个，要求 ≥%d）" % (label, prefix, cnt, minimum))
        else:
            OK.append("%s: %d 个条目" % (label, cnt))

    # 前端入口是否与磁盘一致（防止 static 被清空后未重新构建就打包）
    entry = [n for n in names if n.startswith("BOOT-INF/classes/static/assets/index-") and n.endswith(".js")]
    if entry:
        OK.append("前端入口: %s" % os.path.basename(entry[0]))
    else:
        FAIL.append("找不到 static/assets/index-*.js（前端未构建或静态资源未打包）")

    for a in REQUIRED_ASSETS:
        if a.endswith(".yml"):
            hit = [n for n in names if n.endswith("/" + a)]
        else:
            hit = [n for n in names if n.endswith("/static/" + a)]
        if hit:
            OK.append("%s 已打包" % a)
        else:
            WARN.append("%s 未在包内找到" % a)

    # Flyway 迁移：可位于 BOOT-INF/classes/db/migration，也常见于嵌套模块 jar（axiflux-storage）
    migrations = [n for n in names if n.startswith("BOOT-INF/classes/db/migration/V") and n.endswith(".sql")]
    if not migrations:
        nested = [n for n in names if n.startswith("BOOT-INF/lib/axiflux-") and n.endswith(".jar")]
        for n in nested:
            try:
                with zipfile.ZipFile(io.BytesIO(zf.read(n))) as nz:
                    hit = [x for x in nz.namelist() if "db/migration/V" in x and x.endswith(".sql")]
                    if hit:
                        OK.append("Flyway 迁移位于嵌套模块 %s: %d 个" % (os.path.basename(n), len(hit)))
                        migrations = hit
                        break
            except Exception:  # noqa: BLE001
                continue

    versions = []
    for n in migrations:
        base = os.path.basename(n)
        if base.startswith("V") and base.endswith(".sql"):
            try:
                versions.append(int(base[1:].split("__")[0]))
            except ValueError:
                pass
    if versions:
        OK.append("Flyway 迁移最高版本: V%d（共 %d 个文件）" % (max(versions), len(set(versions))))
    else:
        FAIL.append("找不到任何 Flyway 迁移（db/migration/V*.sql），数据库初始化将失败")

    # 版本号
    props = [n for n in names if n.endswith("META-INF/MANIFEST.MF")]
    if props:
        try:
            mf = zf.read(props[0]).decode("utf-8", "replace")
            for line in mf.splitlines():
                if line.startswith(("Implementation-Version", "Spring-Boot-Version", "Build-Jdk")):
                    OK.append("MANIFEST %s" % line.strip())
        except Exception:  # noqa: BLE001
            pass

    # EE 分发判定
    ee = [n for n in names if "axiflux-ee-" in n or n.startswith("BOOT-INF/lib/axiflux-ee-")]
    if ee and EXPECT_EE is False:
        # 闭源企业版代码不得出现在社区包中；增量构建会让 jar 插件跳过重建、
        # repackage 复用旧 jar 的嵌套依赖，从而把 EE 依赖带进社区包（本项目已真实踩过）。
        FAIL.append("社区版分发却包含企业版模块 %d 个（%s）——必须 clean 重建"
                    % (len(ee), ", ".join(sorted(os.path.basename(e) for e in ee)[:3])))
    elif ee:
        OK.append("含企业版模块 %d 个（EE 分发）" % len(ee))
    elif EXPECT_EE is True:
        FAIL.append("要求企业版分发，但未包含 axiflux-ee-*.jar（请用 -Pee 构建）")
    else:
        WARN.append("未包含 axiflux-ee-*.jar → 社区版分发（企业版交付请用 -Pee 构建）")

    zf.close()
    return report()


def check_dist(path):
    print("=" * 71)
    print(" AxiFlux 离线分发包校验")
    print(" 目标: %s (%s)" % (path, human(os.path.getsize(path))))
    print("=" * 71)

    members = []
    try:
        if path.endswith((".tar.gz", ".tgz")):
            with tarfile.open(path, "r:gz") as tf:
                members = [(m.name, m.size, m.isfile()) for m in tf.getmembers()]
        elif path.endswith(".zip"):
            with zipfile.ZipFile(path) as zf:
                members = [(i.filename, i.file_size, not i.is_dir()) for i in zf.infolist()]
        else:
            print("[FAIL] 不支持的包格式（需 .tar.gz/.tgz/.zip）")
            return 2
    except Exception as e:  # noqa: BLE001
        print("[FAIL] 无法读取分发包: %s" % e)
        return 1

    names = [m[0] for m in members]
    has = lambda sub: any(sub in n for n in names)  # noqa: E731

    for must, desc in [
        ("axiflux-app-", "应用 jar"),
        ("SHA256SUMS", "SHA256 校验清单"),
        ("LICENSE", "LICENSE"),
        ("NOTICE", "NOTICE"),
        ("db/migration", "Flyway 迁移脚本"),
        ("scripts/ops/install.sh", "Linux 安装脚本"),
        ("scripts/ops/upgrade.sh", "升级脚本"),
        ("scripts/ops/backup.sh", "备份脚本"),
        ("scripts/ops/restore.sh", "恢复脚本"),
        ("scripts/ops/verify-install.sh", "交付验收脚本"),
        ("docker-compose.prod.yml", "生产 compose"),
    ]:
        (OK if has(must) else FAIL).append("%s: %s" % (desc, "已包含" if has(must) else "缺失 (%s)" % must))

    if has("axiflux-ee-") and EXPECT_EE is False:
        FAIL.append("企业版 jar: 社区版分发包不得包含 EE（清理后重打）")
    elif has("axiflux-ee-"):
        OK.append("企业版 jar: 已包含")
    elif EXPECT_EE is True:
        FAIL.append("企业版 jar: 要求 EE 分发但缺失")
    else:
        WARN.append("企业版 jar: 未包含（社区版分发包）")
    if has("runtime/jdk") or has("runtime/jre"):
        OK.append("内置 JDK: 已包含（离线可用）")
    else:
        WARN.append("内置 JDK: 未包含（要求目标机自备 JDK 25）")
    if has("images/"):
        OK.append("离线镜像: 已包含")
    else:
        WARN.append("离线镜像: 未包含（目标机需能访问镜像仓库或已预置镜像）")

    return report()


def check_dist_dir(d):
    print("=" * 71)
    print(" AxiFlux 离线分发包目录校验")
    print(" 目标: %s" % d)
    print("=" * 71)
    print("   提示：目录模式仅做存在性检查，不做 jar 内部校验（请对 jar 单独执行本脚本）")

    def has(p):
        return os.path.exists(os.path.join(d, p))

    for p, desc in [
        ("SHA256SUMS", "SHA256 校验清单"),
        ("LICENSE", "LICENSE"),
        ("NOTICE", "NOTICE"),
        ("db/migration", "Flyway 迁移脚本"),
        ("scripts/ops/install.sh", "Linux 安装脚本"),
        ("scripts/ops/verify-install.sh", "交付验收脚本"),
        ("docker-compose.prod.yml", "生产 compose"),
    ]:
        (OK if has(p) else FAIL).append("%s: %s" % (desc, "已包含" if has(p) else "缺失 (%s)" % p))
    jars = [f for f in os.listdir(d) if f.startswith("axiflux-app-") and f.endswith(".jar")]
    (OK if jars else FAIL).append("应用 jar: %s" % (", ".join(jars) if jars else "缺失"))
    ee = [f for f in os.listdir(d) if f.startswith("axiflux-ee-") and f.endswith(".jar")]
    if ee and EXPECT_EE is False:
        FAIL.append("企业版 jar: 社区版分发目录不得包含 EE（%s）" % ", ".join(ee))
    elif ee:
        OK.append("企业版 jar: %s" % ", ".join(ee))
    elif EXPECT_EE is True:
        FAIL.append("企业版 jar: 要求 EE 分发但缺失")
    else:
        WARN.append("企业版 jar: 未包含（社区版）")
    return report()


def report():
    print("-" * 71)
    for m in OK:
        print("OK   | %s" % m)
    for m in WARN:
        print("WARN | %s" % m)
    for m in FAIL:
        print("FAIL | %s" % m)
    print("-" * 71)
    print(" 结果: OK=%d WARN=%d FAIL=%d" % (len(OK), len(WARN), len(FAIL)))
    print("=" * 71)
    return 0 if not FAIL else 1


def main():
    global EXPECT_EE
    args = sys.argv[1:]
    if "--no-ee" in args:
        EXPECT_EE = False          # 社区分发包：出现 EE 即 FAIL
        args = [a for a in args if a != "--no-ee"]
    if "--require-ee" in args:
        EXPECT_EE = True           # 企业版交付包：缺 EE 即 FAIL
        args = [a for a in args if a != "--require-ee"]
    if not args:
        print(__doc__)
        return 2
    if args[0] == "--dist-dir":
        return check_dist_dir(args[1]) if len(args) > 1 else 2
    target = args[0]
    if not os.path.exists(target):
        print("[FAIL] 路径不存在: %s" % target)
        return 1
    if target.endswith((".jar",)):
        return check_jar(target)
    return check_dist(target)


if __name__ == "__main__":
    sys.exit(main())
