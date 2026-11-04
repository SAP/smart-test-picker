#!/bin/bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
evidence="$root/verification/maven-asm-12"
work=${ASM12_WORK:?Set disposable ASM12_WORK}
repository=${ASM12_M2:?Set task-local Maven repository}
asm=${ASM12_ASM:?Set ASM 9.8 JAR}
gson=${ASM12_GSON:?Set Gson 2.10.1 JAR}
mode=${1:?asm-observed, asm-plain, asm-reversed, or jacoco}
case "$mode" in asm-observed|asm-plain|asm-reversed|jacoco) ;; *) exit 2;; esac
if [ ! -d "$work/seed" ]; then
  cp -R "$root/smart-test-picker-maven/src/test/fixtures/shared-spring-web-context" "$work/seed"
  git -C "$work/seed" init -q
  git -C "$work/seed" add .
  git -C "$work/seed" -c user.name='STP verification' -c user.email='stp-verification@example.invalid' commit -qm 'Freeze shared Spring context fixture'
fi
subject="$work/$mode"
test ! -e "$subject"
git clone --quiet --no-hardlinks "$work/seed" "$subject"
observer="$work/observer-$mode"
mkdir -p "$work/classes" "$observer" "$evidence/raw/$mode"
cp_arg="$root/smart-test-picker-common/build/libs/smart-test-picker-common-0.3.0-SNAPSHOT.jar:$root/smart-test-picker-maven/build/libs/smart-test-picker-maven-0.3.0-SNAPSHOT.jar:$gson"
javac -cp "$cp_arg" -d "$work/classes" "$root/verification/maven-asm-10/Measure.java" "$evidence/Check.java"
javac -cp "$asm" -d "$observer" "$evidence/ContextObserver.java"
(cd "$observer" && jar xf "$asm")
jar cfm "$observer.jar" "$root/verification/maven-asm-11/observer-manifest.mf" -C "$observer" .
measure() { java -cp "$work/classes:$cp_arg" com.sap.oss.smarttestpicker.maven.Measure "$@"; }
raw="$evidence/raw/$mode"
export STP_ASM10_REPO="file://$root/build/functional-test-maven-repository"
export MAVEN_OPTS='-Xmx512m'
unset STP_MAPPING_COLLECTOR
cd "$subject"
common=(-B -ntp -s "$root/verification/maven-asm-10/settings.xml" -Dmaven.repo.local="$repository")
collector=ASM
if [ "$mode" = jacoco ]; then collector=JACOCO; else common+=(-Djacoco.skip=true); fi
if [ "$mode" = asm-observed ] || [ "$mode" = asm-reversed ]; then
  common+=("-DobserverArgLine=-javaagent:$observer.jar=$raw/context.tsv")
fi
if [ "$mode" = asm-reversed ]; then common+=(-DtestOrder=reversealphabetical); fi
plugin=com.sap.oss.smart-test-picker:smart-test-picker-maven:0.3.0-SNAPSHOT:
revision=$(git rev-parse HEAD)
printf '%s\n' "$revision" > "$raw/fixture-revision.txt"
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
# Invoke the existing aggregation goal separately when verify stops before reaching it.
if [ "$mapping_code" -ne 0 ]; then
  mvn "${common[@]}" "${mapping[@]:1}" "${plugin}aggregate-reactor-coverage-fragment" > "$raw/aggregation.log" 2>&1
  aggregation_code=$?
else aggregation_code=0; fi
set -e
printf 'mapping=%s\naggregation=%s\n' "$mapping_code" "$aggregation_code" > "$raw/exit-codes.txt"
cp -R .stp "$raw/handoff"
if [ -d target/stp ]; then cp -R target/stp "$raw/module"; fi
cp -R target/surefire-reports "$raw/surefire"
cp target/context-reuse.tsv "$raw/context-reuse.tsv"
git diff --exit-code > "$raw/source-diff.txt"
java -cp "$work/classes:$cp_arg" com.sap.oss.smarttestpicker.maven.Check "$subject" "$raw" > "$raw/validation.log"
if [ "$mapping_code" -eq 0 ]; then measure verify "$subject" "$raw" | tee "$raw/accounting.log"; fi
printf '%s mapping=%s aggregation=%s\n' "$mode" "$mapping_code" "$aggregation_code"
