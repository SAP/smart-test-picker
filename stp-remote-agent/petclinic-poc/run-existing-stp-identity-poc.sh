#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STP_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MODE="${1:-}"
if [[ "$MODE" != "sequential" && "$MODE" != "parallel" ]]; then
	echo "Usage: PETCLINIC_DIR=/path/to/spring-petclinic $0 <sequential|parallel>" >&2
	exit 2
fi
if [[ -z "${PETCLINIC_DIR:-}" ]]; then
	echo "Usage: PETCLINIC_DIR=/path/to/spring-petclinic $0 <sequential|parallel>" >&2
	exit 2
fi
if [[ ! -x "$PETCLINIC_DIR/mvnw" ]]; then
	echo "PETCLINIC_DIR must point to the Spring PetClinic checkout: $PETCLINIC_DIR" >&2
	exit 2
fi

EXPECTED_PETCLINIC_HEAD="88e37c15cf6fc8490b01bc3e8e2c800cec1ac272"
PETCLINIC_HEAD="$(git -C "$PETCLINIC_DIR" rev-parse HEAD)"
PETCLINIC_REMOTE="$(git -C "$PETCLINIC_DIR" remote get-url origin)"
if [[ "$PETCLINIC_HEAD" != "$EXPECTED_PETCLINIC_HEAD" ]]; then
	echo "PetClinic must be at $EXPECTED_PETCLINIC_HEAD; found $PETCLINIC_HEAD" >&2
	exit 2
fi
if [[ "$PETCLINIC_REMOTE" != *"spring-projects/spring-petclinic"* ]]; then
	echo "Unexpected PetClinic origin: $PETCLINIC_REMOTE" >&2
	exit 2
fi
if [[ -n "$(git -C "$PETCLINIC_DIR" status --porcelain)" ]]; then
	echo "PetClinic worktree must be clean" >&2
	exit 2
fi

PORT="${PORT:-18080}"
BASE_URL="http://127.0.0.1:$PORT"
PROBE_PATH="/actuator/health"
OUTPUT_DIR="${OUTPUT_DIR:-$STP_ROOT/stp-remote-agent/build/petclinic-existing-stp-poc/$MODE}"
CLIENT_OUTPUT="$OUTPUT_DIR/client-stp-$MODE.json"
REMOTE_OUTPUT="$OUTPUT_DIR/remote-observations-$MODE.json"
SERVER_LOG="$OUTPUT_DIR/petclinic-server-$MODE.log"
TEST_OUTPUT="$OUTPUT_DIR/jvm-b-test-output.log"
CONTROL_STATUS="$OUTPUT_DIR/headerless-control-status.txt"
CONTROL_BODY="$OUTPUT_DIR/headerless-control-body.html"
PROVENANCE="$OUTPUT_DIR/provenance.txt"
SERVER_PID=""

sanitize_server_log() {
	if [[ -f "$SERVER_LOG" ]]; then
		sed -E \
			-e '/Starting PetClinicApplication/s#.*#PetClinicApplication startup path and user metadata redacted#' \
			-e 's#started by [^[:space:]]+#started by [redacted]#g' \
			"$SERVER_LOG" >"$SERVER_LOG.redacted"
		mv "$SERVER_LOG.redacted" "$SERVER_LOG"
	fi
}

stop_server() {
	if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
		kill -TERM "$SERVER_PID" 2>/dev/null || true
		if wait "$SERVER_PID"; then
			:
		else
			SERVER_EXIT=$?
			if [[ "$SERVER_EXIT" -ne 143 ]]; then
				echo "PetClinic JVM exited with status $SERVER_EXIT" >&2
				return "$SERVER_EXIT"
			fi
		fi
	fi
	SERVER_PID=""
}

cleanup() {
	stop_server || true
	sanitize_server_log
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir -p "$OUTPUT_DIR"
rm -f "$CLIENT_OUTPUT" "$REMOTE_OUTPUT" "$REMOTE_OUTPUT.tmp" "$SERVER_LOG" \
	"$TEST_OUTPUT" "$CONTROL_STATUS" "$CONTROL_BODY" "$PROVENANCE"

STP_BRANCH="$(git -C "$STP_ROOT" branch --show-current)"
STP_HEAD="$(git -C "$STP_ROOT" rev-parse HEAD)"
STP_DIRTY="false"
[[ -z "$(git -C "$STP_ROOT" status --porcelain)" ]] || STP_DIRTY="true"
PETCLINIC_DIRTY="false"
JAVA_VERSION="$(java -version 2>&1)"
CLIENT_RUN_ID="petclinic-$MODE"
cat >"$PROVENANCE" <<EOF
STP branch: $STP_BRANCH
STP HEAD: $STP_HEAD
STP worktree dirty: $STP_DIRTY
PetClinic origin: $PETCLINIC_REMOTE
PetClinic HEAD: $PETCLINIC_HEAD
PetClinic worktree dirty: $PETCLINIC_DIRTY
Java version:
$JAVA_VERSION
Port: $PORT
Readiness probe: $PROBE_PATH
OpenTelemetry Java agent: 2.32.0
OpenTelemetry API: 1.66.0
JVM B test task: :stp-remote-agent:petclinicRemoteTest
JVM B test runner: Gradle Test worker running JUnit Jupiter
JVM B STP agent arguments: output=$CLIENT_OUTPUT;runId=$CLIENT_RUN_ID;instrumentation=off;debug=false
JUnit parallel enabled: $([[ "$MODE" == "parallel" ]] && echo true || echo false)
JUnit parallel default mode: $([[ "$MODE" == "parallel" ]] && echo concurrent || echo same_thread)
EOF

echo "Building both agents..."
"$STP_ROOT/gradlew" -p "$STP_ROOT" :stp-remote-agent:remoteAgentJar :stp-remote-agent:copyOpenTelemetryJavaAgent :stp-agent:agentJar --no-daemon --console=plain
AGENT_JAR="$STP_ROOT/stp-remote-agent/build/libs/stp-remote-agent.jar"
OTEL_AGENT_JAR="$STP_ROOT/stp-remote-agent/build/otel-agent/opentelemetry-javaagent.jar"
CLIENT_AGENT_JAR="$STP_ROOT/stp-agent/build/libs/stp-agent.jar"

echo "Building PetClinic $PETCLINIC_HEAD..."
(cd "$PETCLINIC_DIR" && ./mvnw -q -Dmaven.test.skip=true package)
PETCLINIC_JAR=""
for candidate in "$PETCLINIC_DIR"/target/spring-petclinic-*.jar; do
	if [[ -f "$candidate" && "$candidate" != *.jar.original ]]; then
		PETCLINIC_JAR="$candidate"
		break
	fi
done
if [[ -z "$PETCLINIC_JAR" ]]; then
	echo "Could not find the PetClinic executable jar under $PETCLINIC_DIR/target" >&2
	exit 1
fi

HARNESS_CLASSES="$OUTPUT_DIR/harness-classes"
mkdir -p "$HARNESS_CLASSES"
javac --release 17 -d "$HARNESS_CLASSES" "$SCRIPT_DIR/PetClinicHttpHarness.java"

PETCLINIC_REVISION="$(git -C "$PETCLINIC_DIR" rev-parse HEAD)"
REMOTE_AGENT_OPTIONS="output=$REMOTE_OUTPUT;includes=org.springframework.samples.petclinic.;serviceId=spring-petclinic;revision=$PETCLINIC_REVISION"
echo "Starting PetClinic JVM A at $BASE_URL with readiness probe $PROBE_PATH..."
java "-javaagent:$OTEL_AGENT_JAR" "-javaagent:$AGENT_JAR=$REMOTE_AGENT_OPTIONS" -jar "$PETCLINIC_JAR" \
	--server.address=127.0.0.1 --server.port="$PORT" >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!

java -cp "$HARNESS_CLASSES" PetClinicHttpHarness wait --probe-path "$PROBE_PATH" "$BASE_URL"

GRADLE_ARGS=(
	"-PpetclinicBaseUrl=$BASE_URL"
	"-PpetclinicClientOutput=$CLIENT_OUTPUT"
	"-PpetclinicRunId=$CLIENT_RUN_ID"
)
if [[ "$MODE" == "parallel" ]]; then
	GRADLE_ARGS+=("-PpetclinicParallel=true")
fi
echo "Starting JVM B Gradle Test worker ($MODE)..."
"$STP_ROOT/gradlew" -p "$STP_ROOT" :stp-remote-agent:petclinicRemoteTest \
	"${GRADLE_ARGS[@]}" --no-daemon --console=plain --info >"$TEST_OUTPUT" 2>&1
if [[ ! -s "$CLIENT_OUTPUT" ]]; then
	echo "STP client agent did not write $CLIENT_OUTPUT when JVM B exited" >&2
	tail -80 "$TEST_OUTPUT" >&2
	exit 1
fi

echo "Sending headerless control request after JVM B has exited..."
CONTROL_HTTP_STATUS="$(curl --silent --show-error --output "$CONTROL_BODY" --write-out '%{http_code}' "$BASE_URL/vets")"
printf '%s\n' "$CONTROL_HTTP_STATUS" >"$CONTROL_STATUS"
if [[ "$CONTROL_HTTP_STATUS" != "200" ]]; then
	echo "Headerless control GET /vets returned HTTP $CONTROL_HTTP_STATUS" >&2
	exit 1
fi

echo "Stopping PetClinic JVM A to flush remote observations..."
stop_server
sanitize_server_log
if [[ ! -s "$REMOTE_OUTPUT" ]]; then
	echo "Remote STP agent did not write $REMOTE_OUTPUT on shutdown" >&2
	cat "$SERVER_LOG" >&2
	exit 1
fi

echo "Joining client STP identities with remote observations..."
python3 "$SCRIPT_DIR/verify-existing-stp-identity.py" \
	"$CLIENT_OUTPUT" "$REMOTE_OUTPUT" "$TEST_OUTPUT" "$CONTROL_STATUS" "$MODE" \
	| tee "$OUTPUT_DIR/join-verification.log"
echo "POC passed. Client STP output: $CLIENT_OUTPUT"
echo "Remote observations: $REMOTE_OUTPUT"
echo "Provenance: $PROVENANCE"
echo "PetClinic server log: $SERVER_LOG"
