#!/usr/bin/env bash
# Optional: expose local receiver to TradingView alerts (HTTPS + port 443).
# Requires cloudflared (pkg install cloudflared). Example:
#   bash start_tunnel.sh
# Then set TradingView alert webhook to:
#   https://<cloudflare-host>/webhook?token=$(cat tv-lens-token.txt)
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v cloudflared >/dev/null 2>&1; then
  echo "cloudflared not found. Install it (pkg install cloudflared) or use another HTTPS tunnel." >&2
  exit 1
fi
exec cloudflared tunnel --url "http://127.0.0.1:${TV_LENS_PORT:-8080}"