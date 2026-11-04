#!/bin/bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
work=${PARITY_WORK:?Set measured work directory}
gson=${PARITY_GSON:?Set Gson 2.10.1 JAR}
classpath="$root/smart-test-picker-common/build/libs/smart-test-picker-common-0.3.0-SNAPSHOT.jar:$gson"
mkdir -p "$work/classes"
javac -cp "$classpath" -d "$work/classes" "$root/verification/maven-asm-14/Differential.java" \
  "$root/verification/stp-petclinic-parity-01/Compare.java"
java -cp "$work/classes:$classpath" Compare "$root/verification/stp-petclinic-parity-01/raw" \
  "$root/verification/maven-asm-10/raw/asm" "$work/maven/target/classes" "$work/gradle/build/classes/java/main"
