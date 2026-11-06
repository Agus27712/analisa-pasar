#!/data/data/com.termux/files/usr/bin/bash
# SETUP SCRIPT - memasang ekstensi ke direktori konfigurasi oh-my-pi di Termux
# usage: bash tools/oh-my-pi-extension/APPLY_BRIDGE_V2.sh

set -e

TARGET_DIR="$HOME/.omp/agent/extensions/analisa-pasar"
ALT_DIR="$HOME/.omp/agent/extension/analisa-pasar"
mkdir -p "$TARGET_DIR" "$ALT_DIR"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cp "$SCRIPT_DIR/index.ts" "$TARGET_DIR/index.ts"
cp "$SCRIPT_DIR/index.ts" "$ALT_DIR/index.ts"

# Token endpoint tulis (2026-11-06): rute api/v1/trading menuntut header X-Agent-Token.
# Token itu sendiri dibuat aplikasi dan tampil di dokumen Pengaturan; salin ke sini
# lewat env supaya ekstensi bisa membacanya: ANALISA_PASAR_TOKEN=... bash APPLY_BRIDGE_V2.sh
if [ -n "$ANALISA_PASAR_TOKEN" ]; then
  printf '%s' "$ANALISA_PASAR_TOKEN" > "$TARGET_DIR/agent-bridge-token.txt"
  cp "$TARGET_DIR/agent-bridge-token.txt" "$ALT_DIR/agent-bridge-token.txt"
  echo "✓ Token bridge ditulis ke kedua direktori (agent-bridge-token.txt)"
else
  echo "⚠ Tanpa token: order dari OMP akan ditolak 401 hingga token diinstal"
  echo "  (jalankan ulang pemasangan dari Pengaturan app, atau ketik ulang:"
  echo "  ANALISA_PASAR_TOKEN=<token> bash $0)"
fi

echo "✓ Ekstensi analisa-pasar-bridge berhasil disalin ke:"
echo "  - $TARGET_DIR/index.ts"
echo "  - $ALT_DIR/index.ts"
echo "Untuk menguji koneksi bridge, jalankan:"
echo "  bash $SCRIPT_DIR/test-v2.sh"
