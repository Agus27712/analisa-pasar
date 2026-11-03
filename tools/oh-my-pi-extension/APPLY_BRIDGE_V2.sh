#!/data/data/com.termux/files/usr/bin/bash
# SETUP SCRIPT - memasang ekstensi ke direktori konfigurasi oh-my-pi di Termux
# usage: bash tools/oh-my-pi-extension/APPLY_BRIDGE_V2.sh

set -e

TARGET_DIR="$HOME/.config/oh-my-pi/extensions/analisa-pasar"
mkdir -p "$TARGET_DIR"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cp "$SCRIPT_DIR/index.ts" "$TARGET_DIR/index.ts"

echo "✓ Ekstensi analisa-pasar-bridge-v2 berhasil disalin ke: $TARGET_DIR/index.ts"
echo "Untuk menguji koneksi bridge, jalankan:"
echo "  bash $SCRIPT_DIR/test-v2.sh"
