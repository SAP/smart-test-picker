#!/bin/bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
work=${PARITY_WORK:?Set disposable work directory}
source_repo=${PARITY_SUBJECT:?Set pinned subject checkout}
repository=${PARITY_M2:?Set isolated Maven repository}
gson=${PARITY_GSON:?Set Gson 2.10.1 JAR}
subject="$work/maven"
raw="$root/verification/stp-petclinic-parity-01/raw/maven"
test ! -e "$subject"
git clone --quiet --no-hardlinks "$source_repo" "$subject"
git -C "$subject" checkout --quiet --detach 88e37c15cf6fc8490b01bc3e8e2c800cec1ac272
mkdir -p "$raw" "$work/classes"
classpath="$root/smart-test-picker-common/build/libs/smart-test-picker-common-0.3.0-SNAPSHOT.jar:$root/smart-test-picker-maven/build/libs/smart-test-picker-maven-0.3.0-SNAPSHOT.jar:$gson"
javac -cp "$classpath" -d "$work/classes" "$root/verification/maven-asm-10/Measure.java"
measure() { java -cp "$work/classes:$classpath" com.sap.oss.smarttestpicker.maven.Measure "$@"; }
measure configure "$subject"
export STP_ASM10_REPO="file://$root/build/functional-test-maven-repository"
export MAVEN_OPTS='-Xmx768m'
unset STP_MAPPING_COLLECTOR
cd "$subject"
common=(-B -ntp -s "$root/verification/maven-asm-10/settings.xml" -Dmaven.repo.local="$repository"
  -f measurement-pom.xml -Pmeasurement,stp-measurement -Dcheckstyle.skip=true -Dspring-javaformat.skip=true
  -Djacoco.skip=true -Djunit.jupiter.execution.parallel.enabled=false)
plugin=com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:
revision=$(git rev-parse HEAD)
printf '%s\n' "$revision" > "$raw/revision.txt"
mvn -version > "$raw/toolchain.txt"
mvn "${common[@]}" test-compile -DskipTests > "$raw/preparation.log" 2>&1
mvn "${common[@]}" "${plugin}generate-reactor-head-test-inventory" \
  -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.prHeadRevision="$revision" \
  -DsmartTestPicker.outputFile="$subject/.stp/inventory.json" > "$raw/inventory.log" 2>&1
measure assign "$subject" > "$raw/assignment.log"
args=("${plugin}prepare-reactor-executable-mapping" -DsmartTestPicker.coverageCollector=ASM
  -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.revision="$revision" -DsmartTestPicker.shardId=local
  -DsmartTestPicker.testsFile="$subject/.stp/assignment.json" -DsmartTestPicker.completeInventoryFile="$subject/.stp/inventory.json"
  -DsmartTestPicker.fragmentOutput="$subject/.stp/aggregate.json" -DsmartTestPicker.evidenceOutput="$subject/.stp/evidence.json"
  verify "${plugin}aggregate-reactor-coverage-fragment")
printf '%q ' mvn "${common[@]}" "${args[@]}" > "$raw/command.txt"
printf '\n' >> "$raw/command.txt"
set +e
mvn "${common[@]}" "${args[@]}" > "$raw/mapping.log" 2>&1
code=$?
set -e
printf '%s\n' "$code" > "$raw/exit-code.txt"
git diff --exit-code > "$raw/upstream-source-diff.txt"
cp measurement-pom.xml "$raw/measurement-pom.xml"
cp -R .stp "$raw/handoff"
for directory in stp surefire-reports; do cp -R "target/$directory" "$raw/$directory"; done
if [ "$code" -ne 0 ]; then tail -25 "$raw/mapping.log"; exit "$code"; fi
measure verify "$subject" "$raw" | tee "$raw/accounting.log"
