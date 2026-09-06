#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
if (( $# < 3 )); then printf '%s\n' 'usage: decode.sh EXEC OUTPUT CLASS_DIR...' >&2; exit 2; fi
root=$(cd "$(dirname "$0")" && pwd)
jdk=${TASK19_JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
core=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.jacoco/org.jacoco.core/0.8.13 -name '*.jar' | head -1)
asm=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.8 -name '*.jar' | head -1)
asm_tree=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm-tree/9.8 -name '*.jar' | head -1)
asm_commons=$(find /Users/d061177/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm-commons/9.8 -name '*.jar' | head -1)
"$root/build-decoder.sh" >/dev/null
"$jdk/bin/java" -cp "$root/build/task19-jacoco-decoder.jar:$core:$asm:$asm_tree:$asm_commons" task19.jacoco.Task19JacocoDecoder "$@"
