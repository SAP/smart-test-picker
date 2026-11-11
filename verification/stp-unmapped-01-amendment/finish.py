#!/usr/bin/env python3
"""Retain new suite/regression output and verify gate/test artifact provenance."""
from pathlib import Path

here = Path(__file__).resolve().parent
root = here.parent.parent
original = root / 'verification/stp-unmapped-01/finish.py'
source = original.read_text().replace("here = Path(__file__).resolve().parent", f"here = Path({str(here)!r})")
source = source.replace("'verification/stp-integrity-01/raw/suite-counts.json'",
                        "'verification/stp-unmapped-01/raw/suite-counts.json'")
source = source.replace("'unsupported', 'unowned-error'):",
                        "'unsupported', 'unowned-error', 'runtime-uninitialized', 'transformation-failure', "
                        "'method-id-collision', 'owned-error-unhealthy'):")
exec(compile(source, str(original), 'exec'))
