# 天枢 高可用与水平扩展部署指南

> 目标：应用无状态化 + 共享态外置，支持 2~N 实例水平扩展、滚动升级、故障自动摘除。
> 状态说明：本文的拓扑与配置项**已在单机验证**；**多实例同时在线尚未实机验证**（见第 6 节诚实清单）。

---

## 1. 无状态化设计

应用进程内**不保存**业务必需状态，可随时重启/扩缩：

| 状态类别 | 存放位置 | 说明 |
|---|---|---|
| 会话与消息 | PostgreSQL（`SESSION_PROVIDER=jpa`）或 Redis | `auto` = jpa → redis → inmemory 按可用性降级 |
| 审批（ASK/审批链） | Redis（`RedisApprovalStore`） | 多实例共享同一审批状态 |
| 配额计数 | Redis（`RedisQuotaCounter`） | 跨实例原子累加，避免配额超发 |
| 工具调用状态（限流/死循环检测） | Redis（`RedisToolState`） | 跨实例统一判定 |
| 定时任务互斥 | ShedLock（Redis lock provider） | 保证同一任务同时只有一个实例执行 |
| 长期记忆（向量） | PostgreSQL + pgvector | 多实例共享 |
| 工作区文件 / 技能 | 本地目录 + 技能市场联邦 | 多实例需共享存储（NFS/对象存储）或私有技能源 |

> 因此**Redis 与 PostgreSQL 是多实例部署的强依赖**：任一不可用时，应用会降级（进程内状态），
> 此时不应横向扩容（会出现审批/配额不一致）。生产必须监控 Redis 可用性。

---

## 2. 参考拓扑

```
                 ┌──────────────┐
   Client ──────►│  LB / Nginx  │  健康检查 /actuator/health/readiness
                 └──────┬───────┘
             ┌──────────┴──────────┐
        ┌────▼────┐          ┌────▼────┐
        │ app #1  │          │ app #2  │   (N 个，无状态)
        └────┬────┘          └────┬────┘
             └────────┬───────────┘
              ┌───────▼────────┐   ┌────────────┐
              │ PostgreSQL 16  │   │  Redis 7   │
              │ (+pgvector)    │   │ (共享态)   │
              │ 主 + 流复制备  │   │ 主 + 哨兵  │
              └────────────────┘   └────────────┘
```

关键点：

- **WebSocket**（`/tianshu/ws`）：LB 需开启会话保持（sticky）或使用支持 WS 的长连接 LB；握手支持 `?token=` 由 `BearerTokenHoistFilter` 提升为 Authorization 头。
- **SSE**（`/chat/stream`、子代理事件）：同样需要长连接与足够 idle timeout（建议 ≥ 300 s）。
- **健康检查**：LB 用 `/actuator/health/readiness`（200=UP），liveness 用 `/actuator/health/liveness`。
- **上传/长任务**：设置 `proxy_read_timeout`、请求体上限与 `X-Forwarded-*` 透传。

---

## 3. Nginx 参考配置

```nginx
upstream tianshu_app {
    ip_hash;                                  # WS/SSE 需保持同一实例
    server 10.0.0.21:8080 max_fails=3 fail_timeout=10s;
    server 10.0.0.22:8080 max_fails=3 fail_timeout=10s;
    keepalive 64;
}

server {
    listen 443 ssl http2;
    server_name agent.customer.cn;
    ssl_certificate     /etc/nginx/certs/agent.crt;
    ssl_certificate_key /etc/nginx/certs/agent.key;
    client_max_body_size 100m;

    location / {
        proxy_pass http://tianshu_app;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade           $http_upgrade;   # WS
        proxy_set_header Connection        "upgrade";
        proxy_read_timeout  300s;
        proxy_send_timeout  300s;
    }

    location /actuator/health/readiness { proxy_pass http://tianshu_app; access_log off; }
}
```

多实例部署时 `TIANSHU_PUBLIC_URL`、`AUTH_SECRET`、数据库/Redis 连接必须**完全一致**。

---

## 4. 数据库高可用

**推荐**：PostgreSQL 16 流复制 + 自动故障切换（Patroni / repmgr / 云 RDS 多可用区）。

要点：

- 应用连**读写入口**（主）；只读报表可另配只读副本，但当前版本无读写分离开关，需 LB/中间件层实现。
- `wal_level=replica`（`docker-compose.prod.yml` 已设置）、`max_wal_senders=5`。
- 连接池：`max_connections=300` 是为 2~3 实例预留；更多实例需同步上调或引入 PgBouncer。
- **pgvector** 扩展必须在主备双方都可用；升级/迁移前先在备库验证。
- 灾备：见 `backup-restore.md`（每日全量 + WAL 归档）。

## 5. Redis 高可用

- 主从 + Sentinel（3 哨兵）或 Redis Cluster（≥6 节点）。
- 必须开启 AOF（`appendonly yes`，`appendfsync everysec`）：审批/配额状态丢失会导致门禁失效或配额重放。
- `maxmemory-policy noeviction`：共享态不允许被淘汰。
- 内存建议 ≥ 2 GB；监控 `evicted_keys=0`、`blocked_clients`。

---

## 6. 验证清单（诚实版）

| 项 | 状态 | 说明 |
|---|---|---|
| 单实例启动 + 健康检查 + 静态资源 | ✅ 已验证 | 本机 local profile，`/` 48 ms |
| PG + Redis 同时可用时不降级 | ✅ 已验证 | 启动日志无 Redis 降级告警 |
| Redis 不可用时降级启动 | ✅ 已验证 | 进程内状态 + `RedisAvailability` 告警 |
| 2 实例同时在线、审批/配额/定时任务跨实例一致 | ⚠️ **待实机验证** | 需 2 实例 + LB 环境；建议交付前用 `docker compose up --scale tianshu=2` 补测 |
| 滚动升级不中断（LB 摘流 → 升级 → 回挂） | ⚠️ 待实机验证 | 依赖 LB 健康检查配置 |
| PG 主备切换 | ⚠️ 待客户环境验证 | 需客户既有 PG HA 能力 |
| WS/SSE 长连接穿越 LB | ⚠️ 待实机验证 | `ip_hash` + upgrade 头已给出参考配置 |

补测脚本建议（交付前执行，用于把上表 ⚠️ 变为 ✅）：

```bash
docker compose -f docker-compose.prod.yml up -d --scale tianshu=2
# 1) 并发登录两个实例，验证 token 互认（AUTH_SECRET 一致）
# 2) 实例 A 发起审批，实例 B 查询 /api/v1/approvals 应能看到同一条
# 3) 触发一次配额消耗，检查 Redis 中计数为两实例之和
# 4) 停实例 A，验证定时任务由 B 接管（ShedLock）且 LB 摘除 A
```
