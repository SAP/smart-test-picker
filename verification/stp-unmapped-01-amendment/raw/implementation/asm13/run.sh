#!/bin/bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
evidence="$root/verification/maven-asm-13"
work=${ASM13_WORK:?Set new disposable experiment directory}
repository=${ASM13_M2:?Set task-local Maven repository}
asm=${ASM13_ASM:?Set ASM 9.8 JAR}
gson=${ASM13_GSON:?Set Gson 2.10.1 JAR}
mode=${1:?jacoco-sequential, jacoco-parallel, asm-sequential, asm-parallel}
case "$mode" in jacoco-sequential|jacoco-parallel|asm-sequential|asm-parallel) ;; *) exit 2;; esac
label=${2:-$mode}
case "$label" in "$mode"|"$mode-repeat") ;; *) exit 2;; esac
if [ ! -d "$work/seed" ]; then
  cp -R "$root/smart-test-picker-maven/src/test/fixtures/http-reset-boundary" "$work/seed"
  git -C "$work/seed" init -q
  git -C "$work/seed" add .
  git -C "$work/seed" -c user.name='STP verification' -c user.email='stp-verification@example.invalid' commit -qm 'Freeze HTTP reset boundary fixture'
fi
subject="$work/$label"
test ! -e "$subject"
git clone --quiet --no-hardlinks "$work/seed" "$subject"
observer="$work/observer-$label"
raw="$PERF_RAW"
mkdir -p "$work/classes" "$observer" "$raw"
cp_arg="$root/smart-test-picker-common/build/libs/smart-test-picker-common-0.3.0-SNAPSHOT.jar:$root/smart-test-picker-maven/build/libs/smart-test-picker-maven-0.3.0-SNAPSHOT.jar:$gson"
javac -cp "$cp_arg" -d "$work/classes" "$root/verification/maven-asm-10/Measure.java" "$root/verification/maven-asm-12/Check.java"
javac -cp "$asm" -d "$observer" "$evidence/Gates.java" "/Users/D061177/work/moje/smart-test-picker/verification/stp-unmapped-01-amendment/.work/asm13/BoundaryObserver.java"
(cd "$observer" && jar xf "$asm")
jar cfm "$observer.jar" "$evidence/observer-manifest.mf" -C "$observer" .
measure() { java -cp "$work/classes:$cp_arg" com.sap.oss.smarttestpicker.maven.Measure "$@"; }
export STP_ASM10_REPO="$PERF_REPOSITORY"
export MAVEN_OPTS='-Xmx512m'
unset STP_MAPPING_COLLECTOR
cd "$subject"
common=(-B -ntp -s "$root/verification/maven-asm-10/settings.xml" -Dmaven.repo.local="$repository" "-Dmeasurement.observer.jar=$observer.jar")
collector=JACOCO
if [[ "$mode" = asm-* ]]; then collector=ASM; common+=(-Djacoco.skip=true); fi
if [[ "$mode" = *-parallel ]]; then common+=(-Dmeasurement.parallel=true); fi
plugin=com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:
revision=$(git rev-parse HEAD)
printf '%s\n' "$revision" > "$raw/fixture-revision.txt"
mvn -version > "$raw/toolchain.txt"
mvn "${common[@]}" test-compile -DskipTests > "$raw/preparation.log" 2>&1
mvn "${common[@]}" "${plugin}generate-reactor-head-test-inventory" -DsmartTestPicker.schemaVersion=3 \
  -DsmartTestPicker.prHeadRevision="$revision" -DsmartTestPicker.outputFile="$subject/.stp/inventory.json" > "$raw/inventory.log" 2>&1
measure assign "$subject" > "$raw/assignment.log"
mapping=("${plugin}prepare-reactor-executable-mapping" "-DsmartTestPicker.coverageCollector=$collector"
  -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.revision="$revision" -DsmartTestPicker.shardId=local
  -DsmartTestPicker.testsFile="$subject/.stp/assignment.json" -DsmartTestPicker.completeInventoryFile="$subject/.stp/inventory.json"
  -DsmartTestPicker.fragmentOutput="$subject/.stp/aggregate.json" -DsmartTestPicker.evidenceOutput="$subject/.stp/evidence.json")
printf '%q ' mvn "${common[@]}" "${mapping[@]}" verify "${plugin}aggregate-reactor-coverage-fragment" > "$raw/command.txt"
printf '\n' >> "$raw/command.txt"
set +e
mvn "${common[@]}" "${mapping[@]}" verify "${plugin}aggregate-reactor-coverage-fragment" > "$raw/mapping.log" 2>&1
mapping_code=$?
if [ "$mapping_code" -ne 0 ]; then
  mvn "${common[@]}" "${mapping[@]:1}" "${plugin}aggregate-reactor-coverage-fragment" > "$raw/aggregation.log" 2>&1
  aggregation_code=$?
else aggregation_code=0; fi
set -e
printf 'mapping=%s\naggregation=%s\n' "$mapping_code" "$aggregation_code" > "$raw/exit-codes.txt"
cp -R .stp "$raw/handoff"
for directory in stp surefire-reports jacoco jacoco-xml; do
  if [ -d "target/$directory" ]; then cp -R "target/$directory" "$raw/$directory"; fi
done
cp -R target/classes "$raw/production-classes"
if [ -f target/boundary-events.tsv ]; then cp target/boundary-events.tsv "$raw/events.tsv"; fi
git diff --exit-code > "$raw/source-diff.txt"
java -cp "$work/classes:$cp_arg" com.sap.oss.smarttestpicker.maven.Check "$subject" "$raw" > "$raw/validation.log"
if [ "$mapping_code" -eq 0 ]; then measure verify "$subject" "$raw" | tee "$raw/accounting.log"; fi
if [ "$collector" = JACOCO ]; then
  oracle_cp="$gson:$repository/org/jacoco/org.jacoco.core/0.8.15/org.jacoco.core-0.8.15.jar:$asm:$repository/org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar:$repository/org/ow2/asm/asm-commons/9.8/asm-commons-9.8.jar"
  javac -cp "$oracle_cp" -d "$work/classes" "$evidence/SessionOracle.java"
  java -cp "$work/classes:$oracle_cp" SessionOracle "$raw/jacoco" "$raw/production-classes" "$raw/session-oracle.json" | tee "$raw/session-oracle.log"
fi
printf '%s mapping=%s aggregation=%s\n' "$mode" "$mapping_code" "$aggregation_code"
