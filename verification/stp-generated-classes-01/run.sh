#!/bin/bash
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
agent=${PROBE_AGENT:?Supply the current shaded agent JAR}
m2=${PROBE_M2:?Supply a dependency repository}
mvn=${PROBE_MVN:-mvn}
output="$here/raw/memory"
test ! -e "$output"
mkdir -p "$output"
work=$(mktemp -d "${TMPDIR:-/tmp}/stp-generated-classes.XXXXXX")
cp -R "$here/fixture" "$work/fixture"
args=(-o -B -ntp -f "$work/fixture/pom.xml" "-Dmaven.repo.local=$m2"
  "-Dprobe.agent=$agent" "-Dprobe.output=$output" "-Dprobe.revision=$(git -C "$here" rev-parse HEAD)" test)
printf '%q ' "$mvn" "${args[@]}" > "$output/command.txt"
printf '\n' >> "$output/command.txt"
shasum -a 256 "$agent" > "$output/agent.sha256"
java -version > "$output/java-version.txt" 2>&1
"$mvn" -version > "$output/maven-version.txt" 2>&1
code=0
"$mvn" "${args[@]}" > "$output/maven.log" 2>&1 || code=$?
printf '%s\n' "$code" > "$output/exit-code.txt"
if test -d "$work/fixture/target/surefire-reports"; then
  cp -R "$work/fixture/target/surefire-reports" "$output/"
fi
test "$code" = 0
java -cp "$agent" "$here/Verify.java" "$output/fragment.json" > "$output/verification.txt" 2>&1
test ! -e "$work/fixture/target/classes/generatedprobe/DynamicValue.class"
test ! -e "$work/fixture/target/test-classes/generatedprobe/DynamicValue.class"
printf 'PASS: no DynamicValue class file in either compiler output directory\n' >> "$output/verification.txt"
cat "$output/verification.txt"
