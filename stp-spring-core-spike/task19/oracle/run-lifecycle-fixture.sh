#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
root=$(cd "$(dirname "$0")" && pwd)
jdk=${TASK19_JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
jar_for() { find "/Users/d061177/.gradle/caches/modules-2/files-2.1/$1/$2/$3" -name '*.jar' | head -1; }
launcher=$(jar_for org.junit.platform junit-platform-launcher 1.10.5)
engine_api=$(jar_for org.junit.platform junit-platform-engine 1.10.5)
commons=$(jar_for org.junit.platform junit-platform-commons 1.10.5)
jupiter_api=$(jar_for org.junit.jupiter junit-jupiter-api 5.10.5)
jupiter_engine=$(jar_for org.junit.jupiter junit-jupiter-engine 5.10.5)
api_guardian=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.apiguardian/apiguardian-api -name '*.jar' | tail -1)
opentest=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.opentest4j/opentest4j -name '*.jar' | tail -1)
cp="$launcher:$engine_api:$commons:$jupiter_api:$jupiter_engine:$api_guardian:$opentest:$root/build/task19-lifecycle.jar"
"$root/build-native.sh" >/dev/null; "$root/build-lifecycle.sh" >/dev/null
mkdir -p "$root/build/lifecycle-fixture-classes" "$root/../evidence"
"$jdk/bin/javac" --release 21 -cp "$cp" -d "$root/build/lifecycle-fixture-classes" \
  "$root/fixtures/java/task19/fixtures/LifecycleFixtureTest.java" \
  "$root/fixtures/java/task19/fixtures/LifecycleFixtureLauncher.java"
methods="$root/build/lifecycle-fixture-method-events.jsonl"
lifecycle="$root/build/lifecycle-fixture-events.jsonl"
"$jdk/bin/java" -agentpath:"$root/build/libtask19_oracle.dylib=output=$methods,include=Ltask19/fixtures/" \
  -Dtask19.lifecycle.output="$lifecycle" -Djunit.jupiter.extensions.autodetection.enabled=true \
  -cp "$root/build/lifecycle-fixture-classes:$cp" task19.fixtures.LifecycleFixtureLauncher
python3 "$root/fixtures/verify_lifecycle.py" "$methods" "$lifecycle" > "$root/../evidence/lifecycle-fixture-acceptance.json"
printf '%s\n' "$root/../evidence/lifecycle-fixture-acceptance.json"
