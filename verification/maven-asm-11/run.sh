#!/bin/bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
evidence="$root/verification/maven-asm-11"
work=${ASM11_WORK:?Set disposable ASM11_WORK}
seed=${ASM11_SEED:?Set pinned ASM-10 subject checkout}
repository=${ASM11_M2:?Set task-local Maven repository}
asm=${ASM11_ASM:?Set ASM 9.8 JAR for disposable observer}
gson=${ASM11_GSON:?Set Gson 2.10.1 JAR for measurement helper}
test ! -e "$work/subject"
git clone --quiet --no-hardlinks "$seed" "$work/subject"
git -C "$work/subject" checkout --quiet --detach 88e37c15cf6fc8490b01bc3e8e2c800cec1ac272
mkdir -p "$work/classes" "$work/observer" "$evidence/raw/observed"
cp_arg="$root/smart-test-picker-common/build/libs/smart-test-picker-common-0.3.0-SNAPSHOT.jar:$root/smart-test-picker-maven/build/libs/smart-test-picker-maven-0.3.0-SNAPSHOT.jar:$gson"
javac -cp "$cp_arg" -d "$work/classes" "$root/verification/maven-asm-10/Measure.java" "$evidence/Prepare.java"
javac -cp "$asm" -d "$work/observer" "$evidence/ContextObserver.java"
(cd "$work/observer" && jar xf "$asm")
jar cfm "$work/observer.jar" "$evidence/observer-manifest.mf" -C "$work/observer" .
measure() { java -cp "$work/classes:$cp_arg" com.sap.oss.smarttestpicker.maven.Measure "$@"; }
measure configure "$work/subject"
raw="$evidence/raw/observed"
java -cp "$work/classes" Prepare "$work/subject/measurement-pom.xml" "$work/observer.jar" "$raw/context.tsv"
export STP_ASM10_REPO="file://$root/build/functional-test-maven-repository"
export MAVEN_OPTS='-Xmx768m'
unset STP_MAPPING_COLLECTOR
cd "$work/subject"
common=(-B -ntp -s "$root/verification/maven-asm-10/settings.xml" -Dmaven.repo.local="$repository"
  -f measurement-pom.xml -Pmeasurement,stp-measurement -Dcheckstyle.skip=true -Dspring-javaformat.skip=true -Djacoco.skip=true)
plugin=com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:
revision=$(git rev-parse HEAD)
mvn "${common[@]}" test-compile -DskipTests > "$raw/preparation.log" 2>&1
mvn "${common[@]}" "${plugin}generate-reactor-head-test-inventory" -DsmartTestPicker.schemaVersion=3 \
  -DsmartTestPicker.prHeadRevision="$revision" -DsmartTestPicker.outputFile="$work/subject/.stp/inventory.json" > "$raw/inventory.log" 2>&1
measure assign "$work/subject" > "$raw/assignment.log"
args=("${plugin}prepare-reactor-executable-mapping" -DsmartTestPicker.coverageCollector=ASM
  -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.revision="$revision" -DsmartTestPicker.shardId=local
  -DsmartTestPicker.testsFile="$work/subject/.stp/assignment.json" -DsmartTestPicker.completeInventoryFile="$work/subject/.stp/inventory.json"
  -DsmartTestPicker.fragmentOutput="$work/subject/.stp/aggregate.json" -DsmartTestPicker.evidenceOutput="$work/subject/.stp/evidence.json"
  verify "${plugin}aggregate-reactor-coverage-fragment")
printf '%q ' mvn "${common[@]}" "${args[@]}" > "$raw/command.txt"
printf '\n' >> "$raw/command.txt"
mvn "${common[@]}" "${args[@]}" > "$raw/mapping.log" 2>&1
cp -R .stp "$raw/handoff"
cp -R target/stp "$raw/module"
cp -R target/surefire-reports "$raw/surefire"
cp measurement-pom.xml "$raw/measurement-pom.xml"
git diff --exit-code > "$raw/source-diff.txt"
measure verify "$work/subject" "$raw" | tee "$raw/accounting.log"
measure results "$root/verification/maven-asm-10/raw/asm/surefire" "$raw/surefire" "$raw/results-comparison.json"
measure files "$work/subject" "$raw/collection-files.json"
cmp "$root/verification/maven-asm-10/raw/asm/map.json" "$raw/map.json"
echo 'Observed run passed; candidate map byte-identical to ASM-10'
