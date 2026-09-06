#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
root=$(cd "$(dirname "$0")" && pwd)
jdk=${TASK19_JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
launcher=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.junit.platform/junit-platform-launcher/1.10.5 -name '*.jar' | head -1)
engine=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.junit.platform/junit-platform-engine/1.10.5 -name '*.jar' | head -1)
jupiter=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.junit.jupiter/junit-jupiter-api/5.10.5 -name '*.jar' | head -1)
commons=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.junit.platform/junit-platform-commons/1.10.5 -name '*.jar' | head -1)
classes="$root/build/lifecycle-classes"
mkdir -p "$classes"
"$jdk/bin/javac" --release 21 -cp "$launcher:$engine:$jupiter:$commons" -d "$classes" \
  "$root"/lifecycle/java/task19/oracle/*.java
cp -R "$root/lifecycle/resources/." "$classes/"
"$jdk/bin/jar" --create --file "$root/build/task19-lifecycle.jar" -C "$classes" .
printf '%s\n' "$root/build/task19-lifecycle.jar"
