#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
root=$(cd "$(dirname "$0")" && pwd)
jdk=${TASK19_JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
core=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.jacoco/org.jacoco.core/0.8.13 -name '*.jar' | head -1)
asm=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.8 -name '*.jar' | head -1)
asm_tree=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm-tree/9.8 -name '*.jar' | head -1)
asm_commons=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm-commons/9.8 -name '*.jar' | head -1)
mkdir -p "$root/build/classes"
"$jdk/bin/javac" --release 17 -cp "$core:$asm:$asm_tree:$asm_commons" -d "$root/build/classes" "$root/Task19JacocoDecoder.java"
"$jdk/bin/jar" --create --file "$root/build/task19-jacoco-decoder.jar" -C "$root/build/classes" .
printf '%s\n' "$root/build/task19-jacoco-decoder.jar"
