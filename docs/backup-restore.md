# AxiFlux 备份、恢复与灾备预案

> 目标：RPO ≤ 24 h（每日备份）/ ≤ 5 min（开启 WAL 归档）；RTO ≤ 30 min（单机恢复）。
> 工具：`scripts/ops/backup.sh`、`scripts/ops/restore.sh`（Windows 对应 `axiflux-ops.ps1 -Action Backup/Restore`）。

---

## 1. 备份范围（缺一不可）

| 对象 | 内容 | 丢失后果 |
|---|---|---|
| PostgreSQL | 全库（含 Flyway 版本表、会话、审批审计、组织/用户、配额、向量表） | 业务数据全失 |
| `data/workspace` | Agent 工作区文件、上传物 | Agent 记忆/产物丢失 |
| `data/skills` | 私有技能 | 私有技能需重装 |
| `data/license/license.lic` | 授权文件 | 授权需重新签发 |
| `registry-blobs` / `registry-keys*` | 技能市场联邦的包与密钥 | 联邦同步需重建 |
| `conf/axiflux.env` | 配置与密钥（**备份件内脱敏**，原件另存保险柜） | 需重新配置 |

---

## 2. 手工备份

```bash
sudo /opt/Axiflux/scripts/ops/backup.sh --install-dir /opt/Axiflux \
     --out /backup/Axiflux --keep 14 --encrypt-recipient ops@customer.cn
```

产物：`/backup/Axiflux/axiflux-backup-20260924-120000.tar.gz`（含 `db/axiflux.dump`、`MANIFEST.txt`、`SHA256SUMS`）+ `.sha256`。

- `--encrypt-recipient`：用 GPG 公钥加密（合规要求「备份介质加密」时必选）。
- `--keep 14`：保留最近 14 份并自动清理更早的。
- `MANIFEST.txt`：记录备份时间、主机名、应用 jar、Flyway 版本、PG 连接目标 —— 恢复时的第一手核对材料。

## 3. 定时备份（cron）

```cron
# 每日 02:30 全量备份，保留 14 天，加密到运维公钥
30 2 * * * /opt/Axiflux/scripts/ops/backup.sh --install-dir /opt/Axiflux \
           --out /backup/Axiflux --keep 14 --encrypt-recipient ops@customer.cn \
           >> /var/log/axiflux-backup.log 2>&1

# 每周日 03:30 校验最近一份备份可解包 + 清单完整（防「备份不可用」）
30 3 * * 0 /opt/Axiflux/scripts/ops/restore.sh --file "$(ls -1t /backup/Axiflux/*.tar.gz* | head -1)" --skip-db --yes >> /var/log/axiflux-backup-verify.log 2>&1
```

> `restore.sh --skip-db` 仅校验包结构与清单，不动数据库；用于「备份可用性」巡检。

## 4. 恢复

```bash
# 1) 停服（restore 脚本会自行停服，亦可手动）
sudo systemctl stop Axiflux

# 2) 恢复（脚本会：校验和 → 自动快照当前状态 → pg_restore --clean → 还原数据卷 → 启动 → 探活）
sudo /opt/Axiflux/scripts/ops/restore.sh --file /backup/Axiflux/axiflux-backup-20260924-120000.tar.gz \
     --install-dir /opt/Axiflux --yes
```

恢复后必须：

1. 重跑验收：`scripts/ops/verify-install.sh`（PASS=0 FAIL 才算成功）；
2. 核对 Flyway 版本与 `MANIFEST.txt` 一致；
3. 抽查登录、Agent 列表、定时任务、审批记录；
4. 记录恢复演练报告（时间点、RPO/RTO 实测）。

> `pg_restore` 返回非 0（对象已存在等）属常见，脚本会告警但不中断；必须人工核对日志。

---

## 5. 灾备分级

| 等级 | 手段 | RPO | RTO | 适用 |
|---|---|---|---|---|
| L1 单机备份 | 每日 `backup.sh` | ≤ 24 h | ≤ 30 min | 中小客户 |
| L2 备份 + WAL 归档 | `archive_mode=on` + `pg_basebackup` 周期 | ≤ 5 min | ≤ 30 min | 关键业务 |
| L3 流复制热备 | 主备 + 自动切换 | ≈ 0（异步）/ 0（同步） | ≤ 5 min | 高可用要求 |
| L4 异地容灾 | L3 + 异地备份复制 | ≤ 5 min | ≤ 1 h | 合规/等保三级 |

WAL 归档（L2）要点：

```ini
archive_mode = on
archive_command = 'test ! -f /backup/wal/%f && cp %p /backup/wal/%f'
```

---

## 6. 演练要求（交付与年度维保）

| 频次 | 内容 | 记录 |
|---|---|---|
| 安装交付时 | 现场执行一次备份 → 恢复演练（空库恢复） | 双方签字确认 RTO 实测 |
| 每月 | 备份可用性巡检（解包 + 清单校验） | 运维日志 |
| 每季度 | 全量恢复演练到独立环境 | 演练报告（附实测 RPO/RTO） |
| 变更前 | 强制备份（`upgrade.sh` 自动执行） | 升级记录 |

## 7. 风险提示

- **密钥备份**：`conf/axiflux.env` 内的 `AUTH_SECRET`、`PG_PASSWORD` 若丢失且无原件备份，恢复后需重配；建议纳入企业密码库（KMS/保险柜）。
- **Redis 不备份**：Redis 内是瞬时共享态（审批/配额/锁），恢复后重新累积；但 AOF 必须开启以降低运行期丢失。
- **向量数据**：向量表在 PG 内，随库备份；备份体积随记忆量增长，需评估磁盘与备份窗口。
