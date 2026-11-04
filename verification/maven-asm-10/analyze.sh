#!/bin/bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
evidence="$root/verification/maven-asm-10"
work=${ASM10_WORK:?Set ASM10_WORK to the experiment directory}
classpath="$root/smart-test-picker-common/build/libs/smart-test-picker-common-0.3.0-SNAPSHOT.jar:$root/smart-test-picker-maven/build/libs/smart-test-picker-maven-0.3.0-SNAPSHOT.jar:${ASM10_GSON:?Set ASM10_GSON to Gson 2.10.1 JAR}"
mkdir -p "$work/harness-classes"
javac -cp "$classpath" -d "$work/harness-classes" "$evidence/Measure.java"
measure() { java -cp "$work/harness-classes:$classpath" com.sap.oss.smarttestpicker.maven.Measure "$@"; }
raw="$evidence/raw"
for mode in baseline asm jacoco coexist; do
  measure files "$work/$mode" "$raw/$mode/collection-files.json"
  if [ "$mode" != baseline ]; then
    measure verify "$work/$mode" "$raw/$mode" | tee "$raw/$mode/accounting.log"
    measure results "$raw/baseline/surefire" "$raw/$mode/surefire" "$raw/$mode-results-comparison.json"
  fi
done
measure counters "$raw/baseline/jacoco.xml" "$raw/coexist/jacoco.xml" "$raw/project-counter-comparison.json" | tee "$raw/project-counter-comparison.log"
measure divergence "$raw/asm/map.json" "$raw/jacoco/map.json" "$raw/divergence.json"
measure summary "$root" "$raw"
cmp "$raw/asm/map.json" "$raw/coexist/map.json"
