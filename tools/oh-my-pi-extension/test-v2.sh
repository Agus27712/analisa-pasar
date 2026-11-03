#!/data/data/com.termux/files/usr/bin/bash
# Termux consumer V2 - test polling + websocket
# Usage: bash tools/oh-my-pi-extension/test-v2.sh

echo "=== Analisa-Pasar Bridge V2 Test (Polling + WS) ==="

# Setup storage Termux jika belum
if [ ! -d "$HOME/storage" ]; then
    echo "Menyiapkan Termux storage access..."
    termux-setup-storage
fi

echo "[1] Health check polling (HTTP)..."
curl -s http://127.0.0.1:8765/health | jq . || curl -s http://127.0.0.1:8765/health

echo ""
echo "[2] Latest signal (polling HTTP)..."
curl -s http://127.0.0.1:8765/signals/latest | jq . || curl -s http://127.0.0.1:8765/signals/latest

echo ""
echo "[3] Test WebSocket push / SSE stream (10 detik)..."
if command -v websocat &> /dev/null; then
    echo "Mendengarkan WebSocket ws://127.0.0.1:8765/ws/signals selama 10 detik..."
    timeout 10 websocat ws://127.0.0.1:8765/ws/signals || true
else
    echo "websocat belum terpasang, menguji Server-Sent Events (SSE) via curl -N..."
    timeout 10 curl -N http://127.0.0.1:8765/events || true
fi

echo ""
echo "[4] Test ekstensi oh-my-pi..."
echo "Jalankan perintah berikut di CLI Termux:"
echo "  omp --prompt 'subscribe_signals selama 10 detik, jika ada BTCUSDT confidence >70% buatkan research log'"
