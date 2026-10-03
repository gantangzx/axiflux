# AxiFlux 离线 / 内网安装手册（30 分钟验收版）

> 适用版本：axiflux-agent 0.1.0-SNAPSHOT
> 适用场景：内网、无外网、x86_64 或 aarch64 Linux；Windows Server 见第 6 节
> 目标：按本文可在 **30 分钟内**完成安装，并用 `verify-install` 出具验收结论
>
> 安装后遇到任何报错（启动/数据库/模型/白屏），直接查
> **[《自助排障手册》](self-service-troubleshooting.md)**。

---

## 0. 分发包清单

```
axiflux-offline-<version>/
├── axiflux-app-0.1.0-SNAPSHOT.jar     # 应用 fat jar（含前端静态资源、Flyway 迁移）
├── runtime/jdk/                       # 内置 JDK 25（可选，离线必备）
├── images/pgvector-pg16.tar           # 离线镜像（可选，目标机无外网拉镜像时必须）
├── images/redis7.tar
├── docker-compose.prod.yml            # 生产编排
├── db/migration/                      # Flyway 迁移副本（审计用）
├── scripts/ops/                       # install / upgrade / backup / restore / verify-install
│                                      #   build-offline-package（组装本包）/ clean-install-selftest（干净机自测）
├── LICENSE                            # MIT（本体许可）
├── NOTICE                             # 版权与第三方依赖声明
└── SHA256SUMS                         # 完整性校验清单（安装前强校验）
```

安装前可用包完整性校验（防止「薄 jar / 缺前端资源 / 缺迁移」）：

```bash
python3 scripts/ops/check-package.py axiflux-app-0.1.0-SNAPSHOT.jar
python3 scripts/ops/check-package.py axiflux-offline-1.0.0.tar.gz
```

`FAIL` 非 0 时请勿继续安装。

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
sudo tar -xzf axiflux-offline-1.0.0.tar.gz -C /opt
cd /opt/axiflux-offline-1.0.0 && sudo sha256sum -c SHA256SUMS

# 2) 离线导入镜像（无外网时必需）
sudo docker load -i images/pgvector-pg16.tar
sudo docker load -i images/redis7.tar

# 3) 一键安装（自动：建用户/目录 → 生成随机密钥 → 起 PG+Redis → 装 systemd → 启动 → 验收）
sudo ./scripts/ops/install.sh --dir /opt/axiflux-offline-1.0.0 \
     --install-dir /opt/Axiflux --port 8080 --mode docker

# 4) 首次登录
#    用户名 Axiflux，口令见 /opt/Axiflux/conf/initial-admin-password.txt
#    登录后立即改密
```

安装脚本特性：

- **幂等**：重复执行保留既有 `conf/axiflux.env` 与数据，仅更新程序与迁移。
- **强校验**：存在 `SHA256SUMS` 时校验失败直接终止。
- **密钥随机化**：PG/Redis 口令、`AUTH_SECRET`、SCIM token、初始管理员口令均随机生成并落 600 权限文件。
- **失败可见**：180 s 内未就绪会打印日志路径并退出非 0。

## 3. 安装（原生模式，已有 PG/Redis）

```bash
sudo ./scripts/ops/install.sh --dir /opt/axiflux-offline-1.0.0 \
     --mode native --pg-host 10.0.0.11 --pg-user Axiflux --pg-password '***' --pg-db Axiflux \
     --redis-host 10.0.0.12 --redis-password '***' --public-url https://agent.customer.cn
```

原生模式要求目标库**已建库**且安装了 pgvector：

```sql
CREATE DATABASE Axiflux ENCODING 'UTF8';
\c Axiflux
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- 模糊检索（可选）
```

---

## 4. 30 分钟验收（硬性）

```bash
/opt/Axiflux/scripts/ops/verify-install.sh --url http://127.0.0.1:8080 \
     --user Axiflux --password '<管理员口令>' \
     --json /opt/Axiflux/logs/verify-install.json
```

| 检查项 | 判定 | 说明 |
|---|---|---|
| actuator-liveness / readiness | FAIL | 200 为通过 |
| actuator-health | FAIL/WARN | 401/403 记为 WARN（安全加固后的正常保护） |
| console-index | FAIL | 控制台首页 200 |
| login | FAIL | 返回 token（`data.token`） |
| api-agents / tools / templates / sessions / approvals / scheduler / skills | FAIL | 核心接口 200；401/403 记 WARN（账号缺 scope） |
| api-audits / orgs / usage / config | FAIL | 运营与治理接口 |
| admin-api-keys | FAIL | 管理接口（需 admin 账号） |
| flyway-schema-version | WARN | 打印实际迁移版本 |
| long-term-memory | WARN | 长期记忆可用性 |
| ntp-synchronized | WARN | 审计时间线依赖时钟同步 |

验收结论保存在 `logs/verify-install.json`。

---

## 5. 必配项（验收后立即完成）

编辑 `/opt/Axiflux/conf/axiflux.env`：

```ini
SPRING_PROFILES_ACTIVE=prod
SERVER_PORT=8080
AXIFLUX_PUBLIC_URL=http://agent.customer.cn
PG_URL=jdbc:postgresql://127.0.0.1:5432/Axiflux
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
```

改完 `sudo systemctl restart Axiflux`，并重跑验收脚本。

---

## 6. Windows Server 安装

```powershell
powershell -ExecutionPolicy Bypass -File scripts\ops\axiflux-ops.ps1 -Action Install `
    -Package D:\pkg\axiflux-offline-1.0.0.zip -InstallDir D:\Axiflux
powershell -ExecutionPolicy Bypass -File scripts\ops\axiflux-ops.ps1 -Action Verify `
    -BaseUrl http://127.0.0.1:8080 -InstallDir D:\Axiflux -User Axiflux -Password '***'
```

注意：

- Windows 下同样需要 JDK 25（或使用分发包内 `runtime\jdk`）与 PG(pgvector)、Redis。
- 生产建议将启动命令注册为 Windows 服务（`sc create` 或 NSSM），不要用前台窗口。
- `axiflux-ops.ps1` 已覆盖 Install / Upgrade / Backup / Restore / Verify 五个子命令。

---

## 7. 升级与回滚

```bash
sudo ./scripts/ops/upgrade.sh --package axiflux-offline-1.1.0.tar.gz
```

升级流程：备份 → 停服 → 替换 jar（旧版保留 `.prev`）→ 启动 → 等待 Flyway → 探活 → 验收 → 写版本记录。

失败自动回滚程序；**数据库不做自动回退**（Flyway 社区版无 undo），需要整体回退时用 `restore.sh` 恢复备份。

---

## 9. 常见问题

| 现象 | 根因 | 处理 |
|---|---|---|
| 启动报 `SCRAM-based authentication, no password was provided` | 未加载 env（裸 `java -jar`） | 用 `scripts/start.bat` / systemd `EnvironmentFile` |
| 启动报 `UnsupportedClassVersionError` | 默认 java 是 8/17 | 指定 JDK 25 |
| `GET /` 超时或 404 | 部署了薄 jar 或静态资源未打包 | `check-package.py` 校验；停服后 `clean package` |
| `/actuator/health` 400 `'value' must not be null` | 历史版本健康指示器传 null detail | 升级到含修复的版本（0.1.0-SNAPSHOT 已修） |
| 登录返回 401 且控制台显示登录门 | 前端探测登录态的预期行为 | 正常；登录后即可 |
