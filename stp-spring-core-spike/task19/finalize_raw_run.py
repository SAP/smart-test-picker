#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
import argparse
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("run_dir", type=Path)
args = parser.parse_args()
run = args.run_dir
events = [json.loads(line) for line in (run / "oracle-method-events.jsonl").read_text().splitlines()]
lifecycle = [json.loads(line) for line in (run / "oracle-lifecycle.jsonl").read_text().splitlines()]
identity = next(event["testIdentity"] for event in lifecycle if event["phase"] == "LEAF_OWNERSHIP_START")
(run / "jacoco-map.json").write_text(json.dumps({"testIdentity": identity,
    "methods": sorted(set((run / "jacoco-methods.txt").read_text().splitlines()))}, indent=2, sort_keys=True) + "\n")
(run / "oracle-method-events.json").write_text(json.dumps(events, separators=(",", ":")) + "\n")
(run / "oracle-lifecycle.json").write_text(json.dumps(lifecycle, indent=2, sort_keys=True) + "\n")
