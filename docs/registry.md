# Tianshu Skill Registry（技能注册中心）运行手册

> 创建：2026-09-23
> 适用：`tianshu-registry` 独立服务的构建、配置、部署与运维。
> 主应用手册见 [environments.md](environments.md)。

---

## 1. 它是什么

`tianshu-registry` 是一个**独立可部署的技能注册中心**，与 `tianshu-app`（主应用，8080）
完全解耦，默认监听 **8090**。它提供技能 zip 的：

- 发布（publish，需 Bearer 令牌）
- 搜索 / 列表 / 详情 / 最新版本（公开只读）
- 内容寻址下载（公开，带计数）
- 生命周期管理（published / deprecated，需令牌）
- Ed25519 内容签名与公钥钉扎

主应用把它当作"技能市场"的后端；**注册中心停服不影响主应用的本地技能与已装技能**。

```
  发布者 ──Bearer token──▶ Registry (8090) ──公开只读/下载──▶ 主应用 tianshu-app (8080)
                              │  PostgreSQL(tianshu_registry)
                              └  blob 目录 + Ed25519 密钥
```

---

## 2. 模块与文件结构

```
tianshu-registry/
  pom.xml
  src/main/
    java/com/gantang/tianshu/registry/
      RegistryApplication.java          # 启动类
      config/RegistryProperties.java     # tianshu.registry.* 配置 + 启动 fail-fast
      config/RegistryFlywayConfiguration.java
      entity/SkillCatalog.java SkillVersion.java
      repo/SkillCatalogRepository.java SkillVersionRepository.java
      service/PublishService.java        # 校验/解析/内容寻址落盘/版本入库
      service/CatalogService.java        # 搜索/详情/下载计数/生命周期
      service/SigningService.java        # Ed25519 签名与密钥管理
      skill/SkillFrontmatter.java        # SKILL.md frontmatter 解析
      web/RegistryController.java        # HTTP API
      web/InstallRateLimiter.java        # 安装计数端点的 per-IP 限流
    resources/
      application.yml                    # 唯一配置文件（无多 profile 拆分）
      db/migration/V1__catalog.sql
      db/migration/V2__lifecycle_signing.sql

scripts/
  registry.bat / registry.sh             # 统一启动脚本
env/
  env.registry.bat.example / .sh.example # 密钥模板（提交）
  env.registry.bat / .sh                 # 实际密钥（gitignore）
```

注册中心是**单一应用、单一 `application.yml`**，不像主应用那样分 local/dev/test/prod。
环境差异（本机 / 测试 / 生产）全部通过**环境变量**注入，不新增 profile 文件。

---

## 3. 配置项（环境变量）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `REGISTRY_PORT` | `8090` | HTTP 端口 |
| `REGISTRY_DB_URL` | `jdbc:postgresql://localhost:5432/tianshu_registry` | PostgreSQL JDBC URL |
| `REGISTRY_DB_USER` | `postgres` | DB 用户 |
| `REGISTRY_DB_PASSWORD` | *(空)* | DB 口令，**无内置默认**，生产必填 |
| `REGISTRY_BLOB_DIR` | `./registry-blobs` | 内容寻址 zip 存储目录 |
| `REGISTRY_PUBLISH_TOKEN` | `change-me` | 发布/管理 Bearer 令牌，**必填强随机值** |
| `REGISTRY_MAX_ZIP_BYTES` | `52428800`（50 MiB） | 单 zip 大小上限 |
| `REGISTRY_SIGNING_ENABLED` | `true` | 是否启用 Ed25519 签名 |
| `REGISTRY_SIGNING_KEY_FILE` | `./registry-keys/ed25519.key` | Ed25519 私钥文件（同级 `.pub` 存公钥） |
| `REGISTRY_LOG_LEVEL` | `INFO` | 本服务日志级别 |

### 3.1 启动 fail-fast（安全机制）

`RegistryProperties.requireExplicitPublishToken()` 在启动时校验：`REGISTRY_PUBLISH_TOKEN`
为空、空白、或仍是内置 `change-me` 时**直接拒绝启动**。这是为了防止任何人都能向注册中心
上传任意技能 zip。本地首次启动也必须先设一个强令牌。

### 3.2 密钥生成

```powershell
# Windows PowerShell（生成 64 位十六进制令牌）
[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

```bash
# Linux / macOS
openssl rand -hex 32
```

### 3.3 Ed25519 签名密钥

`SigningService` 首次启动若找不到密钥文件，会自动生成一对：
- 私钥 `registry-keys/ed25519.key`（PKCS#8 base64，Windows 下设只读）
- 公钥 `registry-keys/ed25519.key.pub`（X.509 base64）

私钥要像凭据一样保护；公钥经 `GET /api/registry/public-key` 对外，供客户端钉扎（TOFU 或带外分发）。

---

## 4. 本地运行步骤

### 4.1 准备数据库

注册中心需要独立的 PostgreSQL 库（可用任意 PG 16 实例；无需 pgvector）。建库：

```sql
CREATE DATABASE tianshu_registry;
```

首次启动时 Flyway 自动执行 V1/V2 建表（`baseline-on-migrate=true`）。
本机若无独立 PG，也可与主应用共用实例、仅用不同 database。

### 4.2 配置 env 文件

```bat
copy env\env.registry.bat.example env\env.registry.bat
:: 编辑：REGISTRY_DB_PASSWORD、REGISTRY_PUBLISH_TOKEN（必填）
```

```bash
cp env/env.registry.sh.example env/env.registry.sh
chmod 600 env/env.registry.sh
# 编辑：REGISTRY_DB_PASSWORD、REGISTRY_PUBLISH_TOKEN
```

### 4.3 构建

```bash
mvn clean install -DskipTests -pl tianshu-registry -am
```

`-am` 会同时构建它依赖的上游模块；只改了注册中心代码时，单独 `-pl tianshu-registry` 也可。

### 4.4 启动

```bat
scripts\registry.bat          :: 后台最小化，日志 logs\registry.log
scripts\registry.bat -f       :: 前台
```

```bash
scripts/registry.sh           # 后台，日志 logs/registry.log
scripts/registry.sh -f        # 前台
```

IDE：直接运行 `com.gantang.tianshu.registry.RegistryApplication`，在运行配置里设置上述环境变量。

### 4.5 健康检查

```bash
curl http://localhost:8090/actuator/health
# {"status":"UP"}
```

---

## 5. HTTP API 参考

基址：`http://localhost:8090`

### 5.1 发布 / 管理（需 `Authorization: Bearer <REGISTRY_PUBLISH_TOKEN>`）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/registry/publish` | multipart 上传 `file`（.zip）；返回 slug/version/sha256/downloadUrl |
| `PATCH` | `/api/registry/skills/{slug}/status` | body `{"status":"published"\|"deprecated"}` |

仅接受 `Authorization: Bearer` 头（不接受 form 字段 token，避免被访问日志记录）。

### 5.2 公开只读

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/registry/skills?q=&page=&size=` | 分页搜索（`size` 上限 100） |
| `GET` | `/api/registry/skills/{slug}` | 详情 + 全部版本 |
| `GET` | `/api/registry/skills/{slug}/latest` | 最新版本元数据（含 deprecated 标记） |
| `GET` | `/api/registry/public-key` | Ed25519 公钥（X.509 base64） |
| `GET` | `/api/registry/skills/{slug}/versions/{version}/download` | 下载指定版本 zip |
| `GET` | `/api/registry/skills/{slug}/latest/download` | 下载最新版本 zip |
| `POST` | `/api/registry/skills/{slug}/install` | 安装计数回调（per-IP 限流，超限 429） |

下载响应头：`X-Content-Sha256`、`X-Signature`（签名启用时）、`Content-Disposition`。

### 5.3 发布包要求

- zip 内**恰好一个** `SKILL.md`（位于包根或唯一一级子目录）；多个则 400；
- 拒绝绝对路径与 `..` 路径条目（zip-slip 防护）；
- frontmatter 必填 `name`；`version` 缺省 `0.1.0`，须符合 semver；
- 可解析字段：`name` / `version` / `description` / `author` / `tags` /
  `triggers`(或 `trigger_words`) / `required_tools`(或 `requiredTools`)；
- 同 `slug@version` 且 sha256 相同 → 幂等返回（`alreadyExisted=true`）；
  同版本但内容不同 → 409，需升版本号；
- zip 大小 ≤ `REGISTRY_MAX_ZIP_BYTES`。

---

## 6. 快速验证（发布→搜索→下载）

```bash
# 1) 发布（替换 <token> 和 your-skill.zip）
curl -X POST http://localhost:8090/api/registry/publish \
  -H "Authorization: Bearer <token>" \
  -F "file=@your-skill.zip"

# 2) 搜索
curl "http://localhost:8090/api/registry/skills?q=your"

# 3) 下载并核对 sha256
curl -OJ http://localhost:8090/api/registry/skills/<slug>/latest/download

# 4) 下架（管理端）
curl -X PATCH http://localhost:8090/api/registry/skills/<slug>/status \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"status":"deprecated"}'
```

主应用侧：配置 `tianshu.skills.registry-url=http://localhost:8090` 后，控制台"技能市场"
即可浏览安装；安装来源写作 `registry:<slug>[@version]`。

---

## 7. 生产部署

### 7.1 要点

- 用**独立**的生产 PostgreSQL（库 `tianshu_registry`），定期备份数据库；
- `REGISTRY_PUBLISH_TOKEN` 用强随机值并通过 secret manager / 容器 env 注入，不落明文文件；
- `REGISTRY_DB_PASSWORD` 必填且与其他环境不同；
- 持久化并备份 `REGISTRY_BLOB_DIR`（丢 blob 会导致已发布版本 410 GONE）；
- Ed25519 私钥文件持久化并限制权限——**换密钥会使所有已发布版本的旧签名失效**；
- 在网关层终结 TLS，对外仅暴露 443 → 8090；`/actuator` 只暴露 health/info。

### 7.2 容器运行示例

```bash
docker run -d --name tianshu-registry \
  -e REGISTRY_DB_URL='jdbc:postgresql://db:5432/tianshu_registry' \
  -e REGISTRY_DB_USER='tianshu' \
  -e REGISTRY_DB_PASSWORD='<strong-db-password>' \
  -e REGISTRY_PUBLISH_TOKEN='<strong-random-token>' \
  -e REGISTRY_BLOB_DIR='/data/blobs' \
  -e REGISTRY_SIGNING_KEY_FILE='/data/keys/ed25519.key' \
  -v registry-data:/data \
  -p 8090:8090 tianshu-registry:latest
```

> 现状：注册中心目前以同仓模块 + 本机/单机运行为主，当前 blob 仅支持本地文件系统；
> 生产镜像、对象存储（S3/COS）适配属于后续工作。

---

## 8. 运维与故障排查

| 现象 | 原因 / 处理 |
|---|---|
| 启动即退出，报 `publish-token is not set` | 令牌为空或仍是 `change-me`；设置强随机 `REGISTRY_PUBLISH_TOKEN` |
| 启动报 DB 连接/认证失败 | `REGISTRY_DB_URL/USER/PASSWORD` 未注入或库不存在；确认库已 `CREATE` |
| Flyway/校验报错（`ddl-auto=validate`） | 表结构与实体不一致；确认迁移 V1/V2 已执行，勿手动改表 |
| 发布返回 401 | 未带 `Authorization: Bearer` 或令牌错误 |
| 发布返回 400（多个 SKILL.md / 非法路径） | 包结构不合规；一个 zip 只能含一个 SKILL.md，去掉 `..`/绝对路径 |
| 发布返回 409 | 同 slug@version 已存在但内容不同；升版本号 |
| 下载返回 410 `blob 文件已丢失` | blob 目录文件缺失；从备份恢复 `REGISTRY_BLOB_DIR` |
| install 回调返回 429 | 同 IP 每分钟 >10 次；代理后应在代理层限流 |
| 下载计数不动 | 计数在下载事务内更新；确认走的是注册中心下载端点而非直连 blob |

---

## 9. 安全善后

历史上注册中心的 `application.yml` 曾把一个弱数据库口令默认值提交进 Git，本次已去除
默认值改为环境变量。建议：

1. 更换该 PostgreSQL 口令；
2. 用 `git log -p -- tianshu-registry/src/main/resources/application.yml` 复查历史；
3. 生产上线前确认没有任何环境仍在使用旧口令。
