#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
if (( $# != 1 )); then printf '%s\n' 'usage: run_selected.sh RAW_ROOT' >&2; exit 2; fi
root=$(cd "$(dirname "$0")/../.." && pwd)
raw_root=$1
selection="$root/stp-spring-core-spike/task20/evidence/selected-fn-cases.json"
if [[ -e "$raw_root" ]] && find "$raw_root" -mindepth 1 -print -quit | grep -q .; then
    printf 'refusing non-empty raw root: %s\n' "$raw_root" >&2
    exit 2
fi
mkdir -p "$raw_root"
export TASK20_CAPTURE_DUMP_TIMING=true
while IFS=$'\t' read -r case_id selector; do
    run="$raw_root/canonical/$case_id"
    mkdir -p "$run"
    "$root/stp-spring-core-spike/task19/run_spring_test.sh" "$selector" "$run" jacoco-asm
    printf '%s\n' "$selector" > "$run/selector.txt"
    exec_file=$(find "$run/jacoco" -name 'session_*.exec' | head -1)
    "$root/stp-spring-core-spike/task19/jacoco/decode.sh" "$exec_file" "$run/jacoco-methods.txt" "$run/jacoco/classes"
    python3 "$root/stp-spring-core-spike/task19/finalize_raw_run.py" "$run"
done < <(jq -r '.cases[] | [.caseId,.gradleSelector] | @tsv' "$selection")

# Mandatory focused contrast: the two enum cases use their own fresh JVMs.
while IFS=$'\t' read -r case_id selector; do
    run="$raw_root/asm-first/$case_id"
    mkdir -p "$run"
    "$root/stp-spring-core-spike/task19/run_spring_test.sh" "$selector" "$run" asm-jacoco
    printf '%s\n' "$selector" > "$run/selector.txt"
    exec_file=$(find "$run/jacoco" -name 'session_*.exec' | head -1)
    "$root/stp-spring-core-spike/task19/jacoco/decode.sh" "$exec_file" "$run/jacoco-methods.txt" "$run/jacoco/classes"
    python3 "$root/stp-spring-core-spike/task19/finalize_raw_run.py" "$run"
done < <(jq -r '.cases[0:2][] | [.caseId,.gradleSelector] | @tsv' "$selection")
