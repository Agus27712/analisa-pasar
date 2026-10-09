#!/usr/bin/env python3
"""scalp_replay bootstrap — auto-restore from tools/_hex if needed, then run."""
from pathlib import Path
import binascii, zlib, sys, runpy

HERE = Path(__file__).resolve().parent
SELF = Path(__file__).resolve()

def needs_restore(text: str) -> bool:
    return "def resolve_trade" not in text or "def run_replay" not in text

def restore() -> str:
    parts = sorted((HERE / "_hex").glob("scalp_replay.*.hex"))
    if not parts:
        sys.stderr.write("ERROR: tools/_hex/scalp_replay.*.hex missing \u2014 cannot restore scalp_replay.py\n")
        sys.exit(1)
    raw = binascii.unhexlify("".join(p.read_text().strip() for p in parts))
    text = zlib.decompress(raw).decode("utf-8")
    SELF.write_text(text)
    return text

text = SELF.read_text()
if needs_restore(text):
    text = restore()
    sys.argv[0] = str(SELF)
    runpy.run_path(str(SELF), run_name="__main__")
    raise SystemExit(0)
