#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
jdk=${TASK19_JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
cache=/Users/d061177/.gradle/caches/modules-2/files-2.1
core=$(find "$cache/org.jacoco/org.jacoco.core/0.8.13" -name '*.jar' | head -1)
asm=$(find "$cache/org.ow2.asm/asm/9.8" -name '*.jar' | head -1)
tree=$(find "$cache/org.ow2.asm/asm-tree/9.8" -name '*.jar' | head -1)
commons=$(find "$cache/org.ow2.asm/asm-commons/9.8" -name '*.jar' | head -1)
util=$(find "$cache/org.ow2.asm/asm-util/9.8" -name '*.jar' | head -1)
mkdir -p "$here/build/classes"
"$jdk/bin/javac" --release 17 -cp "$core:$asm:$tree:$commons:$util" -d "$here/build/classes" "$here/Task20JacocoInspector.java"
"$jdk/bin/jar" --create --file "$here/build/task20-jacoco-inspector.jar" -C "$here/build/classes" .
printf '%s\n' "$here/build/task20-jacoco-inspector.jar:$core:$asm:$tree:$commons:$util"
