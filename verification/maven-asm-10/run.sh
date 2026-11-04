#!/bin/bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
evidence="$root/verification/maven-asm-10"
work=${ASM10_WORK:?Set ASM10_WORK to the disposable experiment directory}
mode=${1:?baseline, asm, jacoco, or coexist}
case "$mode" in baseline|asm|jacoco|coexist) ;; *) exit 2;; esac
subject="$work/$mode"
test ! -e "$subject"
git clone --quiet --no-hardlinks "$work/subject" "$subject"
git -C "$subject" checkout --quiet --detach 88e37c15cf6fc8490b01bc3e8e2c800cec1ac272
raw="$evidence/raw/$mode"
mkdir -p "$raw" "$work/harness-classes"
classpath="$root/smart-test-picker-common/build/libs/smart-test-picker-common-0.3.0-SNAPSHOT.jar:$root/smart-test-picker-maven/build/libs/smart-test-picker-maven-0.3.0-SNAPSHOT.jar:${ASM10_GSON:?Set ASM10_GSON to Gson 2.10.1 JAR}"
javac -cp "$classpath" -d "$work/harness-classes" "$evidence/Measure.java"
measure() { java -cp "$work/harness-classes:$classpath" com.sap.oss.smarttestpicker.maven.Measure "$@"; }
measure configure "$subject"
export STP_ASM10_REPO="file://$root/build/functional-test-maven-repository"
export MAVEN_OPTS='-Xmx768m'
unset STP_MAPPING_COLLECTOR
cd "$subject"
common=(-B -ntp -s "$evidence/settings.xml" -Dmaven.repo.local="$work/m2" -f measurement-pom.xml
  -Dcheckstyle.skip=true -Dspring-javaformat.skip=true)
profiles=measurement
if [ "$mode" != baseline ]; then profiles=measurement,stp-measurement; fi
common+=(-P"$profiles")
plugin=com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:
revision=$(git rev-parse HEAD)
printf '%s\n' "$revision" > "$raw/revision.txt"
mvn "${common[@]}" test-compile -DskipTests -Djacoco.skip=true > "$raw/preparation.log" 2>&1
mvn "${common[@]}" help:effective-pom -Doutput="$raw/effective-pom.xml" > "$raw/effective-pom.log" 2>&1
mvn "${common[@]}" dependency:tree -DoutputFile="$raw/dependencies.txt" > "$raw/dependencies.log" 2>&1
args=()
if [ "$mode" != baseline ]; then
  mvn "${common[@]}" "${plugin}generate-reactor-head-test-inventory" \
    -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.prHeadRevision="$revision" \
    -DsmartTestPicker.outputFile="$subject/.stp/inventory.json" > "$raw/inventory.log" 2>&1
  measure assign "$subject" > "$raw/assignment.log"
  collector=ASM
  if [ "$mode" = jacoco ]; then collector=JACOCO; fi
  args+=("${plugin}prepare-reactor-executable-mapping" -DsmartTestPicker.coverageCollector="$collector"
    -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.revision="$revision" -DsmartTestPicker.shardId=local
    -DsmartTestPicker.testsFile="$subject/.stp/assignment.json" -DsmartTestPicker.completeInventoryFile="$subject/.stp/inventory.json"
    -DsmartTestPicker.fragmentOutput="$subject/.stp/aggregate.json" -DsmartTestPicker.evidenceOutput="$subject/.stp/evidence.json")
fi
args+=(verify)
if [ "$mode" != baseline ]; then args+=("${plugin}aggregate-reactor-coverage-fragment"); fi
if [ "$mode" = asm ]; then args+=(-Djacoco.skip=true); fi
printf '%q ' mvn "${common[@]}" "${args[@]}" > "$raw/command.txt"
printf '\n' >> "$raw/command.txt"
# macOS time records wall time and OS maximum RSS (bytes), not a heap estimate.
set +e
/usr/bin/time -l mvn "${common[@]}" "${args[@]}" > "$raw/mapping.log" 2> "$raw/time.txt"
code=$?
set -e
printf '%s\n' "$code" > "$raw/exit-code.txt"
git diff --exit-code > "$raw/upstream-source-diff.txt"
cp measurement-pom.xml "$raw/measurement-pom.xml"
if [ -d .stp ]; then cp -R .stp "$raw/handoff"; fi
if [ -d target/stp ]; then cp -R target/stp "$raw/module"; fi
if [ -f target/site/jacoco/jacoco.xml ]; then cp target/site/jacoco/jacoco.xml "$raw/jacoco.xml"; fi
if [ -d target/surefire-reports ]; then
  mkdir -p "$raw/surefire"
  cp target/surefire-reports/TEST-*.xml "$raw/surefire/"
fi
if [ "$code" -ne 0 ]; then tail -35 "$raw/mapping.log"; exit "$code"; fi
if [ "$mode" != baseline ]; then measure verify "$subject" "$raw" | tee "$raw/accounting.log"; fi
echo "$mode COMPLETE"
