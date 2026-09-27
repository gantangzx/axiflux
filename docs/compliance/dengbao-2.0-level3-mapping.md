# 天枢 等保 2.0 三级 对照说明（技术与产品侧）

> 用途：客户/测评机构做等保 2.0 三级测评时的产品能力对照材料。
> 声明：本文仅列出**产品可提供的技术能力与证据**，不构成测评结论；最终结论由测评机构依据现场部署给出。

---

## 1. 身份鉴别（8.1.4.1）

| 控制项 | 天枢能力 | 证据/配置 | 状态 |
|---|---|---|---|
| 身份标识唯一 | 用户/账号唯一 ID（ULID），组织内 RBAC | `user_account`、`/api/v1/orgs/*` | ✅ |
| 口令复杂度与更换周期 | 本地账号登录（`/api/v1/auth/login`）；初始口令随机生成且仅打印一次 | `install.sh` 生成 `initial-admin-password.txt`(600) | 🟡 复杂度策略需按客户策略配置/外接 IdP |
| 登录失败处理与超时退出 | JWT 有过期（`expiresInSeconds`）；连续失败锁定需在 IdP/网关侧 | — | 🟡 建议由 IdP/网关兜底 |
| 远程管理防窃听 | 全站 HTTPS（LB 终结）+ WS 支持 `?token=` 提升 | `ha-deployment.md` | 🟡 需客户证书 |
| 双因子/单点登录 | OIDC 授权码 + PKCE（SSO）；支持对接 Okta/Azure AD/Keycloak 等 IdP | `/api/v1/sso/start` | 🟡 需按 IdP 现场联调 |

## 2. 访问控制（8.1.4.2）

| 控制项 | 天枢能力 | 证据 | 状态 |
|---|---|---|---|
| 主体/客体授权 | 6 层工具策略链（ALLOW/ASK/DENY）、Scope 最小权限、组织 RBAC（OWNER/ADMIN/MEMBER） | `ToolPolicyChain`、`ScopePolicy`、`/api/v1/orgs/{id}/governance` | ✅ |
| 默认拒绝 | 安全链白名单制（`anyExchange().authenticated()`），策略链 fail-closed（异常即 DENY） | `SecurityConfig`、策略链测试 | ✅ |
| 敏感操作二次确认 | `ASK` 级工具需人工审批；`DESTRUCTIVE`（如 spawn_task）永不自动批准 | `/api/v1/approvals` | ✅ |
| 权限最小化 | Agent 权限只能收窄（父代理权限为上限），子代理深度 ≤2、并发 ≤8 | `MAX_SPAWN_DEPTH`、`SpawnTaskTool` | ✅ |
| 数据面隔离 | 组织数据按 `orgId` 隔离；治理策略含 `allowedTools`、`dataRegion` | `/api/v1/orgs/{id}/governance` | ✅ |

## 3. 安全审计（8.1.4.3）

| 控制项 | 天枢能力 | 证据 | 状态 |
|---|---|---|---|
| 审计覆盖 | 登录、审批决定、配置热更、订阅状态、配额发放、工具执行、组织治理变更 | `AuditService`、`audit` 表、`/api/v1/audits/*` | ✅ |
| 审计记录要素 | 时间、主体（user/org）、动作、对象、结果 | `AuditController.tool-executions`/`alerts` | ✅ |
| 审计日志保护 | 库内审计表 + 应用日志（按天滚动） | `logback-spring.xml` | 🟡 防篡改需 DB 侧权限/只读归档 |
| 审计外送（SIEM/Syslog） | 可通过日志归档或外部采集器对接 Syslog/SIEM | `logback-spring.xml` | 🟡 需按现场 SIEM 配置 |
| 审计留存 ≥ 6 个月 | 依赖备份策略与库容量规划 | `backup-restore.md` | 🟡 需客户容量确认 |

## 4. 入侵防范（8.1.4.4）

| 控制项 | 天枢能力 | 证据 | 状态 |
|---|---|---|---|
| 最小化服务与端口 | 仅 8080（应用）+ PG/Redis 内网 | `docker-compose.prod.yml` 不暴露 DB 端口 | ✅ |
| 工具执行沙箱 | `CodeExecutor` 环境变量清洗（`scrubEnvironment`）、命令沙箱、工作区白名单 | `CommandSandboxTest`(30 项)、`file-allowed-roots` | ✅ |
| 越权与注入防护 | 鉴权过滤器逐请求校验；Bearer 提升；会话防 IDOR（per-user 会话） | `AuthWebFilter`、`SessionController` | ✅ |
| 死循环/滥用防护 | `ToolCallThrottlePolicy`（重复调用 DENY、突发 ASK、滑动窗口） | 策略链测试 | ✅ |
| MCP 端点防护 | `/mcp` 需 JWT；MCP 无交互审批 → 一律拒绝 ASK/DENY | `DefaultMcpEndpoint` + `ToolPolicyChain` | ✅ |
| 依赖漏洞管理 | 依赖清单与版本锁定；`THIRD-PARTY-LICENSES.md` | `mvn dependency:tree` | 🟡 需周期性 SCA 扫描（建议 OWASP DC/Trivy） |

## 5. 数据完整性与保密性（8.1.4.7/8）

| 控制项 | 天枢能力 | 证据 | 状态 |
|---|---|---|---|
| 传输加密 | HTTPS/WSS（LB 终结）；PG/Redis 可启 TLS | `ha-deployment.md` | 🟡 需客户证书与 PG/Redis TLS 配置 |
| 存储加密 | 依赖磁盘/库层加密（LUKS、PG TDE、云盘加密） | — | 🟡 需客户基础设施 |
| 敏感字段处理 | API Key 仅存 SHA-256（明文仅创建时返回一次） | `AdminApiKeyController` | ✅ |
| 数据备份与恢复 | 每日备份 + 加密 + 校验 + 恢复演练 | `backup-restore.md` | ✅ |
| 个人信息保护 | 数据仅存客户内网；LLM 调用可切内网模型 | `xinchuang-adaptation.md` §5 | ✅ |
| 剩余信息保护 | 会话删除/清历史接口；Redis 共享态可 TTL 过期 | `/api/v1/sessions` | 🟡 需按客户要求配置 TTL |

## 6. 剩余管理要求（产品可支撑部分）

| 控制项 | 能力 | 状态 |
|---|---|---|
| 安全管理制度/机构/人员 | 非产品能力 | ⛔ 客户侧 |
| 供应链与第三方 | `THIRD-PARTY-LICENSES.md` + 依赖锁定 | 🟡 |
| 变更管理 | `upgrade.sh`（备份→灰度→探活→回滚）+ `logs/upgrade-history.txt` | ✅ |
| 应急预案与演练 | `backup-restore.md` §6 演练要求 | ✅ |
| 授权与许可 | 开源版采用 MIT 许可，不内置授权强制 | ✅ |

---

## 7. 测评准备材料清单（交付包）

1. 本对照说明（含版本号与部署拓扑）
2. 部署架构图与数据流图（`ha-deployment.md` + 现场拓扑）
3. 安全配置基线（`conf/tianshu.env` 脱敏副本 + LB/Nginx 配置 + 安全加固清单）
4. 审计能力演示脚本（登录/审批/配置变更 → `/api/v1/audits/*` 查询）
5. 备份恢复演练报告（实测 RPO/RTO）
6. 依赖与许可清单（`THIRD-PARTY-LICENSES.md`）
7. SCA/漏扫报告（建议 Trivy/OWASP DC 输出）
8. 渗透测试报告（可由第三方出具）

> 测评前务必对齐：**测评对象范围**（仅应用 or 含 PG/Redis/OS）、**是否要求国密算法**（当前依赖 JSSE 与客户证书体系）、
> **是否要求双因子**（可对接支持 MFA 的外部 IdP）。
