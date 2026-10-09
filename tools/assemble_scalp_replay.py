#!/usr/bin/env python3
"""Decode tools/_hex/scalp_replay.*.hex -> tools/scalp_replay.py"""
from pathlib import Path
import binascii, zlib
HERE = Path(__file__).resolve().parent
parts = sorted((HERE / "_hex").glob("scalp_replay.*.hex"))
raw = binascii.unhexlify("".join(p.read_text().strip() for p in parts))
text = zlib.decompress(raw).decode("utf-8")
out = HERE / "scalp_replay.py"
out.write_text(text)
print(f"restored {out} ({len(text)} bytes)")
