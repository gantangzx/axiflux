# AxiFlux 自助排障手册

> 面向**非框架作者**：你不需要懂 Spring/Reactor，照着「定位命令 → 修复步骤」做即可。
> 问题按安装自测中**真实出现的频率**从高到低排序；先从第 1 条开始。
>
> 配套：首次安装看 [`offline-install.md`](offline-install.md)；环境四档（local/dev/test/prod）
> 看 [`environments.md`](environments.md)。
>
> 最后更新：2026-09-26

---

## 0. 先做这 3 步（90% 的问题从这里定位）

```bash
# 1) 进程在不在、端口通不通（默认 8080；自测脚本用 18080/18090）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/health

# 2) 健康详情（会指出是哪一类组件 DOWN：db / redis …）
curl -s http://127.0.0.1:8080/actuator/health

# 3) 看日志最后 100 行（按你的部署方式选一个路径）
tail -n 100 logs/boot-local.log          # start.bat 启动
#   或 systemd：journalctl -u Axiflux -n 100 --no-pager
```

- 返回 `200` 且 `status:"UP"`：后端正常，问题多在浏览器/网络，跳到 **第 5 条**。
- 返回 `503/DOWN`：看 `components` 里是谁 DOWN，跳到对应条目（db→第 2，redis→第 3）。
- 连不上（超时/拒绝）：进程没起来或端口不对，看 **第 1 条**。

> 排障时请**保留现场**：安装自测加 `--keep`，不要急着删 `/tmp/axiflux-*` 目录。

---

## 1. 服务启动失败 / 进程起不来

**现象**：执行启动命令后端口无监听；或窗口一闪而过；`curl` 连接被拒绝。

**一条定位命令**

```bash
tail -n 80 logs/boot-local.log        # 找第一个 ERROR / Caused by / APplication failed
```

**常见原因（按概率）**

1. **8080 已被占用**（旧实例没停，Windows 下还会导致打包出薄 jar）。
2. JDK 版本不对（本项目要求 **Java 25**；用了 Java 17/21 会报 class file version 错误）。
3. jar 不存在或构建不完整（薄 jar：无 `BOOT-INF/lib`）。
4. 必填环境变量没注入（如 prod 的 `PG_PASSWORD`）。

**自助修复**

```bash
# ① 查端口占用并停掉旧的“AxiFlux”进程（注意别误杀 IDEA 的 java 进程）
#    Windows PowerShell：
Get-NetTCPConnection -LocalPort 8080 -State Listen
#    确认命令行里含 axiflux-app 再杀：
#    taskkill /PID <pid> /F

# ② 确认 Java 是 25
java -version

# ③ 停服后重新“干净”全量构建（不要只 package）
#    Windows：（JAVA_HOME 指向本机 JDK 25 安装目录）
set JAVA_HOME=<JDK25 安装路径>
mvn clean install -DskipTests

# ④ 重新启动
scripts\start.bat local
```

> 重新打包**必须先停服务**（Windows jar 文件锁），并优先用 `clean install`；
> 只改了依赖模块时增量构建可能继续内嵌旧 jar——这是真实踩过的坑。

---

## 2. 连不上 PostgreSQL

**现象**：健康检查 `components.db` 为 `DOWN`；日志出现 `Connection refused` /
`password authentication failed` / `database "Axiflux" does not exist` /
`could not translate host name`。

**一条定位命令**

```bash
# 把 <host>/<port> 换成配置里的值（本机默认 127.0.0.1:5432）
pg_isready -h 127.0.0.1 -p 5432
```

**常见原因**

1. PG 没启动，或应用和数据库不在同一主机/端口。
2. 库 `Axiflux` 没建，或没装 `pgvector` 扩展。
3. 用户名/口令/库名不对；连接串写错。
4. 网络策略/防火墙挡了 5432。

**自助修复**

```bash
# ① 启动 PostgreSQL（按你的安装方式；systemd）
sudo systemctl start postgresql

# ② 建库 + 装向量扩展（只需一次）
createdb Axiflux
psql -d Axiflux -c 'CREATE EXTENSION IF NOT EXISTS vector;'

# ③ 核对应用配置（环境变量优先）
#    PG_URL / PG_USER / PG_PASSWORD
#    例：jdbc:postgresql://127.0.0.1:5432/Axiflux

# ④ 验证能登录
psql "host=127.0.0.1 port=5432 dbname=Axiflux user=Axiflux" -c 'select 1;'
```

> 表结构由 Flyway 在启动时自动迁移（V1–V20+），无需手动建表；
> 若迁移失败，日志会有 `Flyway` / `Migration` 字样，按提示修复后重启即可。

---

## 3. Redis 不可达

**现象**：健康检查 `components.redis` 为 `DOWN`；日志 `Unable to connect to Redis` /
`NOAUTH Authentication required` / `WRONGPASS`。

**一条定位命令**

```bash
redis-cli -h 127.0.0.1 -p 6379 ping        # 有口令则加 -a "$REDIS_PASSWORD"
# 期望输出：PONG
```

**常见原因**

1. Redis 没启动。
2. 开了 `requirepass` 但应用没配口令，或口令不一致。
3. 端口/主机不对，或 sentinel/cluster 模式配置错误。

**自助修复**

```bash
# ① 启动 Redis
sudo systemctl start redis-server        # 或 redis

# ② 核对应用配置：REDIS_HOST / REDIS_PORT / REDIS_PASSWORD
# ③ 带口令验证
redis-cli -h 127.0.0.1 -p 6379 -a "$REDIS_PASSWORD" ping
```

> Redis 主要用于分布式锁（ShedLock）、配额原子计数等。短暂不可用时多数功能
> 会**降级放行**（可用性优先），但建议尽快恢复以保证配额与定时任务在多节点下正确。

---

## 4. 模型调用超时或 401/403

**现象**：发消息时长时间无响应最后报错；聊天气泡提示模型不可用；日志出现
`401 Unauthorized` / `403` / `timeout` / `connect timed out` / 认证失败。

**一条定位命令**

```bash
# 直接打一次模型端点（用你配置的 baseUrl / key / model），绕开应用看是模型还是网络问题
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Authorization: Bearer $ARK_API_KEY" \
  https://ark.cn-beijing.volces.com/api/plan/v3/chat/completions
```

**常见原因**

1. **API Key 没配 / 配错 / 已过期**（401/403）。
2. **出网需要代理**但没设置（内网/离线环境常见；连接超时）。
3. 模型名或 baseUrl 写错；所选模型在该端点不可用。
4. 模型确实慢——单次调用看门狗默认 **120 秒**，超时会自动切下一个 provider。

**自助修复**

```bash
# ① 核对密钥与端点（环境变量优先；不要把 key 写进会提交的文件）
#    ARK_API_KEY / OPENAI_API_KEY / OPENAI_BASE_URL / OPENAI_MODEL

# ② 需要代理时配置（按你的代理地址）
#    Windows curl 出网示例：
#    curl -x http://127.0.0.1:7890 --ssl-no-revoke ...

# ③ 多 provider 场景：确认至少有一个可用 provider，
#    超时/认证失败时代理会自动 fallback；若全部不可用则回合报错。
```

> 说明：流式输出在**第一个字返回之前**失败（含超时/401/网络）会尝试下一个
> provider；已经开始输出后断线则直接报错，这是预期行为。

---

## 5. 页面打不开 / 白屏 / 静态资源 404

**现象**：浏览器访问控制台转圈、白屏、F12 里一堆 js/css 404；但 health 是 UP。

**一条定位命令**

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/
curl -s http://127.0.0.1:8080/ | grep -o '/assets/[^"]*\.js' | head
```

**常见原因**

1. **打包成了薄 jar**：fat jar 里没有静态资源（典型于“服务运行时打包”）。
2. 只跑了 `mvn package`，但**没先构建前端**，或前端资源没同步进 `static/`。
3. 浏览器缓存了旧版本（hash 文件名变了仍引用旧的）。
4. 反向代理（Nginx）没配静态资源或 WebSocket/SSE 转发。

**自助修复**

```bash
# ① 先停服务，再在前端工程里构建（Windows 侧 node/npm）
cd axiflux-app/ui
npx tsc --noEmit          # 类型检查
npm run build             # 产物直接输出到 ../src/main/resources/static

# ② 回到根目录，停服后干净重打包
mvn clean install -DskipTests

# ③ 浏览器强制刷新（Ctrl+F5）或清缓存后重开
```

> 顺序铁律：**先 `npm run build`，再 `mvn`**；打包前确保旧服务已停。
> 交付前可用 `scripts/ops/check-package.py` 检查 fat jar 是否含 `BOOT-INF/lib` 与静态资源。

---

## 6. 向量维度不匹配（记忆检索异常）

**现象**：日志出现 `Embedding dimension mismatch` /
`memory_items.embedding is vector(N) but axiflux.vector.dimension=M`；记忆写入被停用、
检索退化为关键词匹配。

**一条定位命令**

```bash
# 看当前列维度（N）。atttypmod = N+4；-1 表示无维度
psql -d Axiflux -c "SELECT atttypmod-4 AS dims FROM pg_attribute
  WHERE attrelid='memory_items'::regclass AND attname='embedding';"
# 再对照配置 axiflux.vector.dimension（默认 1536；方舟多模态为 2048）
```

**常见原因**

更换了 embedding 模型（维度随之变化），但沿用了旧表；或配置维度与模型实际返回不一致。

**自助修复（会清空已存向量记忆，按需先备份）**

```bash
# ① 确认新模型实际返回的维度，并让配置与之对齐：
#    axiflux.vector.dimension
# ② 维度变更需要重建表/列，例如：
psql -d Axiflux -c 'DROP TABLE IF EXISTS memory_items;'
#    然后重启应用，让启动 DDL 按新维度重建（迁移会自动建表）。
# ③ 若只是临时排障，系统已自动降级为关键词检索，不影响对话主流程。
```

> 参考实测：方舟 `doubao-embedding-vision-251215` 为 **2048 维**；
> 换模型维度变了必须重建 `memory_items`。

---

## 附：求助前请准备好这些（可显著缩短排查时间）

```bash
# 1) 健康详情      curl -s http://127.0.0.1:8080/actuator/health
# 2) 版本与环境    java -version ; echo $SPRING_PROFILES_ACTIVE
# 3) 出错时间点前后的日志（约 50 行）
# 4) 浏览器 F12 → Network/Console 的报错截图（页面类问题）
# 5) License 状态（如涉及授权）
```

> 提交日志前请**脱敏**：去掉 API Key、口令、`Authorization` 头与内网地址。
> 新遇到的卡点会在当周回填本手册与安装脚本，避免出现第二次。
