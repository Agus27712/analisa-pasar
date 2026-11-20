#!/usr/bin/env bash
# Start AI Agent Lens receiver. Token: TV_LENS_TOKEN env, --token, or tv-lens-token.txt.
set -euo pipefail
cd "$(dirname "$0")"
exec python3 receiver.py --host "${TV_LENS_HOST:-127.0.0.1}" --port "${TV_LENS_PORT:-8080}"