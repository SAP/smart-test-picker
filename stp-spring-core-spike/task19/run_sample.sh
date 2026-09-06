#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
selection="$root/stp-spring-core-spike/task19/evidence/selected-spring-tests.json"
raw=${TASK19_RAW_ROOT:-/private/tmp/task19-primary}
retained="$root/stp-spring-core-spike/task19/evidence/runs"
mkdir -p "$raw" "$retained"
index=0
while IFS= read -r selector; do
  index=$((index + 1))
  key=$(printf '%03d' "$index")
  printf 'TASK19 sample %s/40: %s\n' "$index" "$selector"
  "$root/stp-spring-core-spike/task19/run_and_join.sh" "$selector" "$raw/$key" "$retained/$key"
done < <(jq -r '.tests[].gradleSelector' "$selection")
