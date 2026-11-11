#!/usr/bin/env python3
"""Assert fresh subject outcomes and unchanged hot-path implementation."""
from pathlib import Path

here = Path(__file__).resolve().parent
root = here.parent.parent
original = root / 'verification/stp-integrity-01/evidence.py'
source = original.read_text().replace("here = Path(__file__).resolve().parent", f"here = Path({str(here)!r})")
source = source.replace('3884d49:', 'fa2d0f4:')
exec(compile(source, str(original), 'exec'))
