# 天枢 离线私有化安装手册（30 分钟验收版）

> 适用版本：tianshu-agent 0.1.0-SNAPSHOT（Flyway V26）
> 适用场景：客户内网、无外网、x86_64 或 aarch64 Linux；Windows Server 见第 6 节
> 目标：陌生运维按本文可在 **30 分钟内**完成安装，并用 `verify-install` 出具验收结论
>
> 安装后遇到任何报错（启动/数据库/模型/白屏/License），直接查
> **[《自助排障手册》](self-service-troubleshooting.md)**（面向非框架作者）。

---

## 0. 交付物清单（分发包应包含）

```
tianshu-offline-<version>/
├── tianshu-app-0.1.0-SNAPSHOT.jar     # 应用 fat jar（含前端静态资源、Flyway 迁移）
├── tianshu-ee-*.jar                   # 企业版模块（仅企业版分发；社区版无此文件）
├── runtime/jdk/                       # 内置 JDK 25（可选，离线必备）
├── images/pgvector-pg16.tar           # 离线镜像（可选，目标机无外网拉镜像时必须）
├── images/redis7.tar
├── docker-compose.prod.yml            # 生产编排
├── db/migration/                      # Flyway 迁移副本（审计用）
├── scripts/ops/                       # install / upgrade / backup / restore / verify-install
│                                      #   build-offline-package（组装本包）/ clean-install-selftest（干净机自测）
├── LICENSE                            # Apache-2.0（社区版本体）
├── LICENSE-EE.md                      # 企业版许可（仅企业版分发）
├── NOTICE                             # 版权与第三方依赖声明
└── SHA256SUMS                         # 完整性校验清单（安装前强校验）
```

交付前请用包完整性校验（防止「薄 jar / 缺前端资源 / 缺迁移」事故）：

```bash
python3 scripts/ops/check-package.py tianshu-app-0.1.0-SNAPSHOT.jar
python3 scripts/ops/check-package.py tianshu-offline-1.0.0.tar.gz
```

`FAIL` 非 0 时**禁止交付**。

---

## 1. 环境要求

| 项 | 最低 | 推荐 | 说明 |
|---|---|---|---|
| CPU | 4 核 | 8 核+ | 推理走外部 API 时为纯 IO 型 |
| 内存 | 8 GB | 16 GB | 应用堆 2 GB 起；PG/Redis 另计 |
| 磁盘 | 50 GB | 200 GB SSD | 工作区文件、向量库、日志、备份 |
| JDK | 25（内置 runtime/jdk 亦可） | — | 项目编译目标为 Java 25 |
| PostgreSQL | 16 + pgvector | 16 + pgvector | 长期记忆（向量检索）依赖 pgvector 扩展 |
| Redis | 7 | 7 | 审批状态、配额计数、工具状态、ShedLock 分布式锁 |
| 端口 | 8080/tcp | — | 应用默认端口（可用 `SERVER_PORT` 覆盖） |

> PostgreSQL 若无 pgvector：应用可启动，但 `VECTOR_PROVIDER=pgvector` 的向量表创建会失败；
> 需改用 `VECTOR_PROVIDER=none` 或加装 pgvector。

---

## 2. 安装（Docker 模式，推荐）

```bash
# 1) 解包并校验
sudo tar -xzf tianshu-offline-1.0.0.tar.gz -C /opt
cd /opt/tianshu-offline-1.0.0 && sudo sha256sum -c SHA256SUMS

# 2) 离线导入镜像（无外网时必需）
sudo docker load -i images/pgvector-pg16.tar
sudo docker load -i images/redis7.tar

# 3) 一键安装（自动：建用户/目录 → 生成随机密钥 → 起 PG+Redis → 装 systemd → 启动 → 验收）
sudo ./scripts/ops/install.sh --dir /opt/tianshu-offline-1.0.0 \
     --install-dir /opt/tianshu --port 8080 --mode docker

# 4) 首次登录
#    用户名 tianshu，口令见 /opt/tianshu/conf/initial-admin-password.txt
#    登录后立即改密
```

安装脚本特性：

- **幂等**：重复执行保留既有 `conf/tianshu.env` 与数据，仅更新程序与迁移。
- **强校验**：存在 `SHA256SUMS` 时校验失败直接终止。
- **密钥随机化**：PG/Redis 口令、`AUTH_SECRET`、SCIM token、初始管理员口令均随机生成并落 600 权限文件。
- **失败可见**：180 s 内未就绪会打印日志路径并退出非 0。

## 3. 安装（原生模式，客户已有 PG/Redis）

```bash
sudo ./scripts/ops/install.sh --dir /opt/tianshu-offline-1.0.0 \
     --mode native --pg-host 10.0.0.11 --pg-user tianshu --pg-password '***' --pg-db tianshu \
     --redis-host 10.0.0.12 --redis-password '***' --public-url https://agent.customer.cn
```

原生模式要求目标库**已建库**且安装了 pgvector：

```sql
CREATE DATABASE tianshu ENCODING 'UTF8';
\c tianshu
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- 模糊检索（可选）
```

---

## 4. 30 分钟验收（硬性）

```bash
/opt/tianshu/scripts/ops/verify-install.sh --url http://127.0.0.1:8080 \
     --user tianshu --password '<管理员口令>' \
     --json /opt/tianshu/logs/verify-install.json [--expect-ee]
```

| 检查项 | 判定 | 说明 |
|---|---|---|
| actuator-liveness / readiness | FAIL | 200 为通过 |
| actuator-health | FAIL/WARN | 401/403 记为 WARN（安全加固后的正常保护） |
| console-index | FAIL | 控制台首页 200 |
| login | FAIL | 返回 token（`data.token`） |
| api-agents / tools / templates / sessions / approvals / scheduler / skills | FAIL | 核心接口 200；401/403 记 WARN（账号缺 scope） |
| api-audits / orgs / billing / usage / config | FAIL | 运营与治理接口 |
| api-license / admin-api-keys | FAIL | 管理接口（需 admin 账号） |
| flyway-schema-version | WARN | 打印实际版本，应等于交付版本 V26 |
| ee-edition-endpoint | FAIL（仅 `--expect-ee`） | 企业版交付必须 200 |
| long-term-memory | WARN | 402 表示当前套餐未含 long_term_memory（产品门禁，非故障） |
| ntp-synchronized | WARN | license 有效期与审计时间线依赖时钟 |

验收通过与客户签字，报告在 `logs/verify-install.json`。

---

## 5. 必配项（验收后立即完成）

编辑 `/opt/tianshu/conf/tianshu.env`：

```ini
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080
TIANSHU_PUBLIC_URL=http://agent.customer.cn
PG_URL=jdbc:postgresql://127.0.0.1:5432/tianshu
PG_USER=postgres
PG_PASSWORD=***
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=***
AUTH_ENABLED=true
AUTH_SECRET=***                # 多实例必须一致
SESSION_PROVIDER=jpa           # 多实例推荐 jpa（共享 PG）
VECTOR_PROVIDER=pgvector
ARK_API_KEY=***                # 平台默认 LLM（或改用控制台 sysConfig）
EMBED_API_KEY=***              # 向量化服务密钥
# 企业版（未购买保持 false）
TIANSHU_EE_ENABLED=false
TIANSHU_EE_SAML_ENABLED=false
TIANSHU_EE_SCIM_ENABLED=false
TIANSHU_EE_AUDIT_ENABLED=false
```

改完 `sudo systemctl restart tianshu`，并重跑验收脚本。

---

## 6. Windows Server 安装

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ops\tianshu-ops.ps1 -Action Install `
    -Package D:\pkg\tianshu-offline-1.0.0.zip -InstallDir D:\tianshu
powershell -ExecutionPolicy Bypass -File scripts\ops\tianshu-ops.ps1 -Action Verify `
    -BaseUrl http://127.0.0.1:8080 -InstallDir D:\tianshu -User tianshu -Password '***'
```

注意：

- Windows 下同样需要 JDK 25（或使用分发包内 `runtime\jdk`）与 PG(pgvector)、Redis。
- 生产建议将启动命令注册为 Windows 服务（`sc create` 或 NSSM），不要用前台窗口。
- `tianshu-ops.ps1` 已覆盖 Install / Upgrade / Backup / Restore / Verify 五个子命令。

---

## 7. 升级与回滚

```bash
sudo ./scripts/ops/upgrade.sh --package tianshu-offline-1.1.0.tar.gz
sudo ./scripts/ops/upgrade.sh --package tianshu-offline-1.1.0-ee.tar.gz --ee
```

升级流程：备份 → 停服 → 替换 jar（旧版保留 `.prev`）→ 启动 → 等待 Flyway → 探活 → 验收 → 写版本记录。

失败自动回滚程序；**数据库不做自动回退**（Flyway 社区版无 undo），需要整体回退时用 `restore.sh` 恢复备份。

---

## 8. 发布构建检查清单（交付方内部）

```bash
# 1) 前端（必须先于打包；Vite 不做类型检查，必须单独 tsc）
cd tianshu-app/ui && npx tsc --noEmit && npm run build

# 2) 后端（必须 clean：增量构建会掩盖接口/测试签名不一致）
mvn -B -pl tianshu-app -am -DskipTests clean package          # 社区版
mvn -B -Pee -DskipTests clean package                          # 企业版（含 tianshu-ee-*.jar）

# 3) 质量门
mvn test                                                       # 1226 tests
# 社区包必须断言「不含 EE」；企业包必须断言「含 EE」（防闭源代码误入社区包）
python3 scripts/ops/check-package.py tianshu-app/target/tianshu-app-0.1.0-SNAPSHOT.jar --no-ee      # 社区版
python3 scripts/ops/check-package.py tianshu-app/target/tianshu-app-0.1.0-SNAPSHOT.jar --require-ee # 企业版

# 4) 组装离线包（自动再过一次质量门 + 生成 SHA256SUMS；可 --jdk 内置 JDK）
bash scripts/ops/build-offline-package.sh \
     --jar tianshu-app/target/tianshu-app-0.1.0-SNAPSHOT.jar --version 1.0.0

# 5) 干净机自测（交付前强制；WSL/无 root 亦可，约 30s 出结论）
bash scripts/ops/clean-install-selftest.sh \
     --jar tianshu-app/target/tianshu-app-0.1.0-SNAPSHOT.jar
# 期望末行「未发现卡点」且结果 PASS、FAIL=0；有 FAIL 或卡点则禁止交付。

# 6) 运行期冒烟（停服务后重打包，避免 jar 被占用导致 repackage 失败）
scripts/start.bat local   # Windows；Linux 用 systemctl restart tianshu
```

### 8.1 干净机自测说明（M2-1）

`clean-install-selftest.sh` 以「陌生用户 + 隔离目录」身份跑完整链路，无需
root/systemd/docker：自动组装离线包 → `initdb` 独立 PG 集群并建 `vector` 扩展
→ 独立 Redis → 生成 600 配置 → 起 jar（Flyway 迁移）→ `verify-install.sh` 出结论，
全程计时并把卡点写入 `ISSUES.txt`。

- 目标机需要 PG 服务端（含 pgvector）与 Redis；脚本会自动发现
  `/usr/lib/postgresql/*/bin/postgres`，缺失时打印对应安装命令。
- 可加 `--jdk <tar.gz|URL>` 内置 JDK；`--keep` 保留现场便于排障。
- 有 root/systemd 的正式机器，请仍以第 2/3 节的 `install.sh` 复测一遍。

> **2026-09-24 自测曾并修复的真实事故**（均已闭环）：
> 1. **登录后所有接口 500**——`application-prod.yml` 给 `OIDC_ISSUER_URI` 设了非空
>    占位默认值（`idp.example.com`），导致私有化部署被强制按外部 OIDC 解码本地 HS256
>    令牌、DNS 解析失败。已改为默认空（本地账号模式），仅在显式注入时对接外部 IdP。
> 2. **验收脚本误报**——`json_get` 多传占位参数使 `status`/`data.token` 解析为空；
>    未启用的 license 端点 404 被误判 FAIL。均已修正。
> 3. **引导管理员口令环境变量名错误**——`install.sh` 使用了
>    `TIANSHU_BOOTSTRAP_ADMIN_PASSWORD`（无效，口令停留默认 admin），已更正为
>    `TIANSHU_AUTH_BOOTSTRAPADMIN_PASSWORD`。

### 8.2 离线企业 License：启用 / 签发 / 续签（M2-2）

私有化企业版通过**离线文件 License**控制授权，不落库、不联网。License 是
RS256 签名的 JWT，与「部署 ID」绑定，运行时有 6 态：

| 状态 | 含义 | ENFORCE 下 |
|---|---|---|
| `VALID` | 在有效期内 | 放行 |
| `GRACE` | 已过期、仍在宽限期（默认 7 天） | **放行**，便于续签 |
| `EXPIRED` | 越过宽限期 | **503 拦截** |
| `INVALID` | 签名/绑定/字段校验失败 | **503 拦截** |
| `MISSING` | 未安装（或缺公钥） | **503 拦截** |
| `DISABLED` | 功能未启用 | 放行 |

**厂商侧（天枢，离线保管私钥）**——用 fat jar 内的 `LicenseTool`：

```bash
# 1) 生成密钥对：public.pem 随部署分发，private.pem 离线保密
java -cp tianshu-app.jar com.gantang.tianshu.spring.license.LicenseTool keygen --out-dir keys
# 2) 签发（validity-seconds 支持负数，用于造已过期证；常规用 --validity-days 365）
java -cp tianshu-app.jar com.gantang.tianshu.spring.license.LicenseTool issue \
     --private-key keys/private.pem --out acme.lic \
     --customer "Acme" --deployment-id <客户部署ID> --edition enterprise --seats 50 \
     --entitlements "scheduler,audit.export" --validity-days 365
# 3) 续签（保留全部绑定，仅延长有效期）
java -cp tianshu-app.jar com.gantang.tianshu.spring.license.LicenseTool renew \
     --private-key keys/private.pem --old-license acme.lic --out acme-new.lic --validity-days 365
```

**客户侧（控制台自助）**：登录后进入「离线授权」页（需 `license:admin`）：
复制页面上的**部署 ID** → 发给厂商 → 取回 License Key → 「应用 License」粘贴即可，
有效期内或宽限期内均可应用、无需停机。也可调用 REST：

```bash
# 状态 / 应用 / 撤销
curl -H "Authorization: Bearer $T" .../api/v1/admin/license
curl -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
     -d '{"licenseKey":"<JWT>"}' .../api/v1/admin/license
curl -H "Authorization: Bearer $T" -X DELETE .../api/v1/admin/license
```

启用需配置（env，全部可走 `TIANSHU_LICENSE_*` 环境变量）：

```properties
tianshu.license.enabled=true
tianshu.license.path=/opt/tianshu/data/license/enterprise.lic
tianshu.license.public-key-path=/opt/tianshu/conf/public.pem
tianshu.license.deployment-id=<固定部署ID>
tianshu.license.enforcement=enforce   # off / warn / enforce
tianshu.license.grace-days=7
```

> 设计要点（M2-2 实测确认）：
> 1. **登录/SSO 等认证获取端点豁免 License 检查**——否则无证时无法登录、形成
>    恢复死锁；License 只管认证之后的业务面。
> 2. **运行期撤销在 ENFORCE 下立即 fail-closed**（状态转 MISSING→503），而非
>    旧实现的 `DISABLED`（全量放行）；OFF/WARN 下撤销仍放行。
> 3. **WARN 档**对坏证照常放行，但每响应带 `X-License-State` 头并按分钟节流告警，
>    不再与 OFF 行为完全相同。

交付前用脚本做三态实机验证（约 3 分钟，自动起隔离 PG/Redis、签发各态凭证）：

```bash
bash scripts/ops/license-enforce-selftest.sh \
     --jar tianshu-app/target/tianshu-app-0.1.0-SNAPSHOT.jar
# 实测（2026-09-26）：7 场景 18 断言全 PASS、FAIL=0
```

> 已知事故（务必遵守）：若打包时应用进程仍在运行，`spring-boot-maven-plugin` 的 repackage
> 无法把 jar 重命名为 `.original`，产物会停留在**薄 jar**（无 `BOOT-INF/lib`、无静态资源）。
> 表现为：服务能启动、`/actuator/health/liveness` 正常，但控制台 `GET /` 超时/静态资源 404。
> `check-package.py` 会直接判定 FAIL。

> 已知事故 2（**非 clean 构建会复用上一次的嵌套依赖**）：`maven-jar-plugin` 在增量构建时
> 可能跳过重建 jar，`repackage` 便在旧 fat jar 上重新嵌套依赖——于是**上一次 `-Pee` 的 EE 依赖
> 会原样进入本次「社区包」**，`BUILD SUCCESS` 但产物违约（闭源 EE 泄漏）。
> 因此：发布一律 `clean`，且社区包必须过 `--no-ee` 闸门（见 `docs/ee-verification-2026-09-24.md` §9）。

---

## 9. 常见问题

| 现象 | 根因 | 处理 |
|---|---|---|
| 启动报 `SCRAM-based authentication, no password was provided` | 未加载 env（裸 `java -jar`） | 用 `scripts/start.bat` / systemd `EnvironmentFile` |
| 启动报 `UnsupportedClassVersionError` | 默认 java 是 8/17 | 指定 JDK 25 |
| `GET /` 超时或 404 | 部署了薄 jar 或静态资源未打包 | `check-package.py` 校验；停服后 `clean package` |
| 社区包 `BOOT-INF/lib` 里出现 `tianshu-ee-*.jar` | 非 clean 构建复用了上次 `-Pee` 产物的嵌套依赖 | `clean package` 重建；`check-package.py --no-ee` 必须 FAIL=0 |
| `/actuator/health` 400 `'value' must not be null` | 历史版本健康指示器传 null detail | 升级到含修复的版本（0.1.0-SNAPSHOT 已修） |
| `/api/v1/admin/license` 404 | license 子系统未启用 | `application-local.yml`/env 配置 `tianshu.license.enabled=true` |
| 登录返回 401 且控制台显示登录门 | 前端探测登录态的预期行为 | 正常；登录后即可 |
