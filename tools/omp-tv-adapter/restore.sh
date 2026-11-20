#!/usr/bin/env bash
# Restore/install the TradingView -> Tokocrypto (omp) adapter from this repo package.
# Idempotent: safe to run again after an app/environment reinstall.
# Does NOT copy or print bridge tokens; receiver webhook token is generated locally if absent.
set -euo pipefail

PKG_DIR="$(cd "$(dirname "$0")" && pwd)"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
TRADER_DIR="${TV_TRADER_DIR:-$HOME_DIR/trader}"
ADAPTER_DST="$TRADER_DIR/tv-adapter"
SKILLS_DST="$HOME_DIR/.agents/skills"
MANAGED_DST="$HOME_DIR/.omp/agent/managed-skills/tv-lens-tokocrypto-adapter"
MCP_JSON="$HOME_DIR/.omp/agent/mcp.json"

say() { printf '[restore] %s\n' "$*"; }

say "package: $PKG_DIR"
mkdir -p "$TRADER_DIR" "$SKILLS_DST" "$HOME_DIR/.omp/agent"

# 1) Adapter code (preserve existing packets/history/token if present)
mkdir -p "$ADAPTER_DST/examples" "$ADAPTER_DST/packets/latest"
for f in common.py receiver.py mcp_server.py agent_prompt.md README.md start_receiver.sh start_tunnel.sh; do
  cp "$PKG_DIR/tv-adapter/$f" "$ADAPTER_DST/$f"
done
cp "$PKG_DIR/tv-adapter/examples/sample_packet.json" "$ADAPTER_DST/examples/sample_packet.json"
chmod +x "$ADAPTER_DST/start_receiver.sh" "$ADAPTER_DST/start_tunnel.sh" "$ADAPTER_DST/receiver.py" "$ADAPTER_DST/mcp_server.py" 2>/dev/null || true

# 2) Runtime trader skills required by tv_gate / tv_prepare_order / tv_risk_check / tv_broker_*
for s in _trader-lib trader-risk trader-broker trader-timemachine; do
  mkdir -p "$SKILLS_DST/$s"
  cp "$PKG_DIR/agent-skills/$s/"* "$SKILLS_DST/$s/"
  chmod +x "$SKILLS_DST/$s"/*.py 2>/dev/null || true
done

# 3) Managed omp skill (discoverable by the agent in future sessions)
mkdir -p "$MANAGED_DST"
cp "$PKG_DIR/agent-skills/tv-lens-tokocrypto-adapter/SKILL.md" "$MANAGED_DST/SKILL.md"

# 4) Register/merge MCP server tv-tokocrypto in ~/.omp/agent/mcp.json
python3 - "$MCP_JSON" "$ADAPTER_DST/mcp_server.py" "$TRADER_DIR" <<'PY'
import json, os, sys
path, server_py, trader_dir = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(os.path.dirname(path), exist_ok=True)
data = {}
if os.path.exists(path):
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh) or {}
    except Exception:
        data = {}
servers = data.setdefault("mcpServers", {})
servers["tv-tokocrypto"] = {
    "command": "python3",
    "args": [server_py],
    "env": {"TV_TRADER_DIR": trader_dir},
    "instructions": True,
}
if "$schema" not in data:
    data["$schema"] = "https://raw.githubusercontent.com/can1357/oh-my-pi/main/packages/coding-agent/src/config/mcp-schema.json"
tmp = path + ".tmp"
with open(tmp, "w", encoding="utf-8") as fh:
    json.dump(data, fh, indent=2, ensure_ascii=False)
    fh.write("\n")
os.replace(tmp, path)
print(path)
PY

# 5) Webhook token (receiver): generate only if missing; never print an existing secret
TOKEN_FILE="$ADAPTER_DST/tv-lens-token.txt"
if [ ! -s "$TOKEN_FILE" ]; then
  python3 - <<'PY' > "$TOKEN_FILE"
import secrets
print(secrets.token_hex(24), end="")
PY
  chmod 600 "$TOKEN_FILE"
  say "created receiver webhook token: $TOKEN_FILE (keep private)"
else
  chmod 600 "$TOKEN_FILE" 2>/dev/null || true
  say "receiver webhook token exists: $TOKEN_FILE"
fi

# 6) Quick syntax check
python3 -m py_compile \
  "$ADAPTER_DST/common.py" "$ADAPTER_DST/receiver.py" "$ADAPTER_DST/mcp_server.py" \
  "$SKILLS_DST/trader-risk/check.py" "$SKILLS_DST/trader-broker/ticket.py" \
  "$SKILLS_DST/trader-timemachine/timemachine.py" "$SKILLS_DST/_trader-lib/venue.py"
say "python syntax OK"

say "DONE"
say "Next: restart the omp session to load MCP 'tv-tokocrypto'. Read: $PKG_DIR/CHECKPOINT_OMP.md"
