# Bundled omp runtime skills

Skill runtime yang dibutuhkan adapter `tv-tokocrypto`:
- `_trader-lib/venue.py` — akses pasar Binance vision + cache + fee
- `trader-risk/check.py` — validasi risiko ticket
- `trader-broker/ticket.py` — ticket/order helper
- `trader-timemachine/timemachine.py` — first-passage barrier gate
- `tv-lens-tokocrypto-adapter/SKILL.md` — managed skill omp

Salinan ini berasal dari `~/.agents/skills` dan dipulihkan oleh `restore.sh`.
Jangan commit token, cache, atau state runtime.
