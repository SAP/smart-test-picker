#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
jdk=${TASK19_JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
root=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$root/build"
cc -std=c11 -O2 -Wall -Wextra -Werror -dynamiclib \
  -I"$jdk/include" -I"$jdk/include/darwin" \
  "$root/native/task19_oracle.c" -o "$root/build/libtask19_oracle.dylib"
printf '%s\n' "$root/build/libtask19_oracle.dylib"
