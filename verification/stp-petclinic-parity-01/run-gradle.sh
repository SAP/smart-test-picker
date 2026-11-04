#!/bin/bash
set -euo pipefail
root=$(git rev-parse --show-toplevel)
work=${PARITY_WORK:?Set disposable work directory}
source_repo=${PARITY_SUBJECT:?Set pinned upstream checkout}
gson=${PARITY_GSON:?Set Gson 2.10.1 JAR}
subject="$work/gradle"
raw="$root/verification/stp-petclinic-parity-01/raw"
test ! -e "$subject"
git clone --quiet --no-hardlinks "$source_repo" "$subject"
git -C "$subject" checkout --quiet --detach 88e37c15cf6fc8490b01bc3e8e2c800cec1ac272
mkdir -p "$raw/gradle" "$work/classes"
export GRADLE_USER_HOME="$work/gradle-home"
common=(-p "$subject" --no-daemon -I "$root/verification/stp-petclinic-parity-01/gradle.init.gradle"
  -Dparity.stpRepo="$root/build/functional-test-maven-repository")
"$subject/gradlew" "${common[@]}" -Dstp.executableInventoryDiscovery=true \
  generateGradleExecutableHeadTestInventory --stacktrace > "$raw/gradle-inventory.log" 2>&1
classpath="$root/smart-test-picker-common/build/libs/smart-test-picker-common-0.3.0-SNAPSHOT.jar:$gson"
javac -cp "$classpath" -d "$work/classes" "$root/verification/stp-petclinic-parity-01/Prepare.java"
java -cp "$work/classes:$classpath" Prepare "$subject/build/executable-head-test-inventory.json" \
  "$raw/maven/handoff/inventory.json" "$subject/assignment.json" > "$raw/inventory-equality.txt"
cp "$subject/assignment.json" "$subject/build/executable-head-test-inventory.json" "$raw/gradle/"
set +e
"$subject/gradlew" "${common[@]}" -Dstp.executableAssignment="$subject/assignment.json" \
  generateSmartTestMapping --info --stacktrace > "$raw/gradle-mapping.log" 2>&1
code=$?
set -e
printf '%s\n' "$code" > "$raw/gradle/exit-code.txt"
git -C "$subject" rev-parse HEAD > "$raw/gradle/revision.txt"
git -C "$subject" diff --exit-code > "$raw/gradle/upstream-source-diff.txt"
cp "$subject/build/stp/coverage/_test/local/fragment.json" "$raw/gradle/"
cp "$subject/build/stp/coverage/_test/local/agent-diagnostic.json" "$raw/gradle/"
mkdir -p "$raw/gradle/test-results"
cp "$subject"/build/test-results/test/TEST-*.xml "$raw/gradle/test-results/"
exit "$code"
