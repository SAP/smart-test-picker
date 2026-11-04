#!/bin/bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
evidence="$root/verification/maven-asm-14"
work=${ASM14_WORK:?Set disposable work directory}
repository=${ASM14_M2:?Set task-local Maven repository}
gson=${ASM14_GSON:?Set Gson 2.10.1 JAR}
source_repo=${ASM14_SUBJECT:?Set existing pinned upstream checkout}
collector=${1:?asm or jacoco}
order=${2:?name or random1 or random2}
case "$collector" in asm|jacoco) ;; *) exit 2;; esac
case "$order" in
  name) orderer='org.junit.jupiter.api.MethodOrderer$MethodName'; seed=8675309;;
  random1) orderer='org.junit.jupiter.api.MethodOrderer$Random'; seed=8675309;;
  random2) orderer='org.junit.jupiter.api.MethodOrderer$Random'; seed=20261104;;
  *) exit 2;;
esac
label="$collector-$order"
subject="$work/$label"
test ! -e "$subject"
git clone --quiet --no-hardlinks "$source_repo" "$subject"
git -C "$subject" checkout --quiet --detach 88e37c15cf6fc8490b01bc3e8e2c800cec1ac272
raw="$evidence/raw/$label"
mkdir -p "$raw" "$work/classes"
classpath="$root/smart-test-picker-common/build/libs/smart-test-picker-common-0.3.0-SNAPSHOT.jar:$root/smart-test-picker-maven/build/libs/smart-test-picker-maven-0.3.0-SNAPSHOT.jar:$gson"
javac -cp "$classpath" -d "$work/classes" "$root/verification/maven-asm-10/Measure.java"
measure() { java -cp "$work/classes:$classpath" com.sap.oss.smarttestpicker.maven.Measure "$@"; }
measure configure "$subject"
export STP_ASM10_REPO="file://$root/build/functional-test-maven-repository"
export MAVEN_OPTS='-Xmx768m'
unset STP_MAPPING_COLLECTOR
cd "$subject"
common=(-B -ntp -s "$root/verification/maven-asm-10/settings.xml" -Dmaven.repo.local="$repository" -f measurement-pom.xml
  -Dcheckstyle.skip=true -Dspring-javaformat.skip=true -Pmeasurement,stp-measurement
  "-Djunit.jupiter.testmethod.order.default=$orderer" "-Djunit.jupiter.execution.order.random.seed=$seed"
  '-Djunit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$ClassName'
  -Djunit.jupiter.execution.parallel.enabled=false)
plugin=com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:
revision=$(git rev-parse HEAD)
printf '%s\n' "$revision" > "$raw/revision.txt"
mvn -version > "$raw/toolchain.txt"
mvn "${common[@]}" test-compile -DskipTests -Djacoco.skip=true > "$raw/preparation.log" 2>&1
mvn "${common[@]}" "${plugin}generate-reactor-head-test-inventory" \
  -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.prHeadRevision="$revision" \
  -DsmartTestPicker.outputFile="$subject/.stp/inventory.json" > "$raw/inventory.log" 2>&1
measure assign "$subject" > "$raw/assignment.log"
selected=JACOCO
if [ "$collector" = asm ]; then selected=ASM; fi
args=("${plugin}prepare-reactor-executable-mapping" "-DsmartTestPicker.coverageCollector=$selected"
  -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.revision="$revision" -DsmartTestPicker.shardId=local
  -DsmartTestPicker.testsFile="$subject/.stp/assignment.json" -DsmartTestPicker.completeInventoryFile="$subject/.stp/inventory.json"
  -DsmartTestPicker.fragmentOutput="$subject/.stp/aggregate.json" -DsmartTestPicker.evidenceOutput="$subject/.stp/evidence.json"
  verify "${plugin}aggregate-reactor-coverage-fragment")
if [ "$collector" = asm ]; then args+=(-Djacoco.skip=true); fi
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
for directory in stp surefire-reports jacoco classes; do
  if [ -d "target/$directory" ]; then cp -R "target/$directory" "$raw/$directory"; fi
done
if [ "$code" -ne 0 ]; then tail -25 "$raw/mapping.log"; exit "$code"; fi
measure verify "$subject" "$raw" | tee "$raw/accounting.log"
if [ "$collector" = jacoco ]; then
  oracle_cp="$gson:$repository/org/jacoco/org.jacoco.core/0.8.15/org.jacoco.core-0.8.15.jar:$repository/org/ow2/asm/asm/9.8/asm-9.8.jar:$repository/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar:$repository/org/ow2/asm/asm-commons/9.8/asm-commons-9.8.jar"
  javac -cp "$oracle_cp" -d "$work/classes" "$root/verification/maven-asm-13/SessionOracle.java"
  java -cp "$work/classes:$oracle_cp" SessionOracle "$raw/jacoco" "$raw/classes" "$raw/session-oracle.json" | tee "$raw/session-oracle.log"
fi
echo "$label COMPLETE"
