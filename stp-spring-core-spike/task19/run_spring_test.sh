#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
if (( $# != 3 )); then printf '%s\n' 'usage: run_spring_test.sh SELECTOR RUN_DIR AGENT_ORDER' >&2; exit 2; fi
root=$(cd "$(dirname "$0")/../.." && pwd)
spring=${TASK19_SPRING_CHECKOUT:-/private/tmp/stp-round9-spring}
jdk=${TASK19_JAVA_HOME:-$(/usr/libexec/java_home -v 21)}
export TASK19_TEST_SELECTOR=$1
export TASK19_RUN_DIR=$2
export TASK19_AGENT_ORDER=$3
export TASK19_ASM_AGENT="$root/stp-agent/build/libs/stp-agent-experimental.jar"
export TASK19_JACOCO_AGENT=${TASK19_JACOCO_AGENT:-/private/tmp/task19-jacoco/jacocoagent.jar}
export TASK19_ORACLE_AGENT="$root/stp-spring-core-spike/task19/oracle/build/libtask19_oracle.dylib"
export TASK19_LIFECYCLE_JAR="$root/stp-spring-core-spike/task19/oracle/build/task19-lifecycle.jar"
export TASK19_JACOCO_LISTENER_JAR="$root/smart-test-picker-core/build/libs/smart-test-picker-core-0.1.0.jar"
export JAVA_HOME=$jdk
"$spring/gradlew" --init-script "$root/stp-spring-core-spike/task19/spring-task19.init.gradle" \
  -p "$spring" :spring-core:test --no-daemon
