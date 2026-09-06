#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
if (( $# < 3 || $# > 4 )); then printf '%s\n' 'usage: run_and_join.sh SELECTOR RUN_DIR OUTPUT_DIR [--global-mode]' >&2; exit 2; fi
root=$(cd "$(dirname "$0")/../.." && pwd)
spring=${TASK19_SPRING_CHECKOUT:-/private/tmp/stp-round9-spring}
"$root/stp-spring-core-spike/task19/run_spring_test.sh" "$1" "$2" jacoco-asm
printf '%s\n' "$1" > "$2/selector.txt"
exec_file=$(find "$2/jacoco" -name 'session_*.exec' | head -1)
"$root/stp-spring-core-spike/task19/jacoco/decode.sh" "$exec_file" "$2/jacoco-methods.txt" "$2/jacoco/classes"
python3 "$root/stp-spring-core-spike/task19/finalize_raw_run.py" "$2"
python3 "$root/stp-spring-core-spike/task19/analyze_run.py" --run-dir "$2" \
  --selection "$root/stp-spring-core-spike/task19/evidence/selected-spring-tests.json" \
  --classes-root "$spring/spring-core/build/classes" --output-dir "$3" ${4:-}
