#!/usr/bin/env python3
"""Reuse PERF's unchanged subject/gate commands with fresh integrity-task outputs."""
from pathlib import Path

here = Path(__file__).resolve().parent
root = here.parent.parent
(here / '.work').mkdir(exist_ok=True)
(here / 'raw/implementation').mkdir(parents=True, exist_ok=True)
original = root / 'verification/stp-perf-01/validate.py'
source = original.read_text()
source = source.replace("here = Path(__file__).resolve().parent", f"here = Path({str(here)!r})")
source = source.replace("cache = here / '.work/petclinic-before/m2'",
                        "cache = root / 'verification/stp-perf-01/.work/petclinic-before/m2'")
source = source.replace("str(here / '.work/petclinic-source')",
                        "str(root / 'verification/stp-perf-01/.work/petclinic-source')")
exec(compile(source, str(original), 'exec'))
