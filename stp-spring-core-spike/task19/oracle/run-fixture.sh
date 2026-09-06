#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
root=$(cd "$(dirname "$0")" && pwd)
jdk=${TASK19_JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
"$root/build-native.sh" >/dev/null
mkdir -p "$root/build/fixture-classes" "$root/../evidence"
"$jdk/bin/javac" --release 21 -d "$root/build/fixture-classes" \
  "$root/fixtures/java/task19/fixtures/OracleFixtureMain.java"
events="$root/build/fixture-method-events.jsonl"
"$jdk/bin/java" -agentpath:"$root/build/libtask19_oracle.dylib=output=$events,include=Ltask19/fixtures/" \
  -cp "$root/build/fixture-classes" task19.fixtures.OracleFixtureMain
python3 "$root/fixtures/verify_fixture.py" "$events" > "$root/../evidence/oracle-fixture-acceptance.json"
printf '%s\n' "$root/../evidence/oracle-fixture-acceptance.json"
