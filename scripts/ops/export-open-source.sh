#!/usr/bin/env bash
#
# export-open-source.sh — 从开发 monorepo 导出「仅开源」快照，供推送到 public 仓。
#
# open-core 模式：私有开发仓包含全部模块；公开仓只允许出现开源框架/SDK。
# 本脚本生成一个工作快照（不含 .git），由你检查后再推送到公开仓的 git 历史。
# 闭源模块 / 官网 / 内部销售与法律材料在文件层被排除——Rollup 式的物理隔离，
# 不依赖评审自觉。
#
# 用法：
#   bash scripts/ops/export-open-source.sh --out ../axiflux-opensource
#   # 检查快照后自行：cd <out> && git init && git add . && git commit ...
#
# 注意：默认不会推送任何内容；公开动作（git remote / push）由你显式执行。
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

[ -n "$OUT" ] || { echo "missing --out <dir>" >&2; exit 2; }
OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"

echo "source : $SRC"
echo "target : $OUT"

# rsync 排除清单：闭源模块与不应进入公开仓的目录/文件。
EXCLUDES=(
  --exclude=.git
  --exclude=.codebuddy
  --exclude=.idea
  --exclude=.vscode
  --exclude=target
  --exclude=node_modules
  --exclude=tmp
  --exclude='boot-*.log'
  --exclude='boot-*.err'
  --exclude='smoke-*.bat'
  # 编辑器/构建残留备份文件
  --exclude='*.bak'
  # 闭源模块（商业层 + EE）
  --exclude=axiflux-commercial
  --exclude=axiflux-ee-core
  --exclude=axiflux-ee-sso
  --exclude=axiflux-ee-audit
  # 官网营销页（README 已含商业合作入口，不需要独立官网）
  --exclude=docs/site
  # 内部销售 / BP / 价格 / POC / 法律内部清单
  --exclude=docs/sales
  --exclude=docs/legal
  # 内部商业台账 / 路线图 / 本地 Stripe 调试记录（不对外）
  --exclude=docs/commercialization-ledger.md
  --exclude='docs/commercial-roadmap-*.md'
  --exclude='docs/billing-local-stripe-setup-*.md'
  # 企业版专属能力文档（审计外送 / SAML·SCIM / EE 验证记录）
  --exclude='docs/ee-*.md'
  # 早期内部架构审计快照（含 infra/data/authz 内部评估，不对外）
  --exclude=docs/audit-20260913
  # 空的 / 运行时残留目录（rsync 不读 .gitignore，这里显式排除）
  --exclude=.github
  --exclude=data
  --exclude=tools
  # License 测试签发私钥与样例凭证（公开即可被用来伪造企业 License）
  --exclude=data/license
  # 本地 registry 运行态：签名私钥、blob 存储与测试目录
  --exclude=registry-keys
  --exclude=registry-keys2
  --exclude=registry-blobs
  --exclude=registry-blobs2
  # 本地运行日志
  --exclude=logs
  # 真实环境密钥（仅保留 *.example 模板）
  --exclude='env/env.*.bat'
  --exclude='env/env.*.sh'
)

command -v rsync >/dev/null 2>&1 || { echo "rsync not found" >&2; exit 3; }
rsync -a --delete "${EXCLUDES[@]}" "$SRC"/ "$OUT"/

echo
echo "export complete. verify before publishing:"
echo "  cd \"$OUT\" && find . -maxdepth 1 -type d | sort"
echo "  grep -RIn 'axiflux-commercial\\|StripeClient' . || echo 'no commercial refs'"
echo
echo "next (you run explicitly):"
echo "  cd \"$OUT\" && git init && git add . && git commit -m 'open-source Axiflux framework'"
