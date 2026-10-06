#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STP_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
if [[ -z "${PETCLINIC_DIR:-}" ]]; then
	echo "Usage: PETCLINIC_DIR=/path/to/spring-petclinic ./run-two-jvm-poc.sh" >&2
	exit 2
fi
PETCLINIC_DIR="$PETCLINIC_DIR"
PORT="${PORT:-18080}"
PROBE_PATH="${PROBE_PATH:-/actuator/health}"
OBSERVATIONS_NAME="${OBSERVATIONS_NAME:-remote-observations.json}"
OUTPUT_DIR="${OUTPUT_DIR:-$STP_ROOT/stp-remote-agent/build/petclinic-two-jvm-poc}"
OBSERVATIONS="$OUTPUT_DIR/$OBSERVATIONS_NAME"
PROVENANCE_NAME="${PROVENANCE_NAME:-${OBSERVATIONS_NAME%.json}-provenance.txt}"
PROVENANCE="$OUTPUT_DIR/$PROVENANCE_NAME"
SERVER_LOG_NAME="${SERVER_LOG_NAME:-petclinic-server.log}"
SERVER_LOG="$OUTPUT_DIR/$SERVER_LOG_NAME"
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

cleanup() {
	if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
		kill -TERM "$SERVER_PID" 2>/dev/null || true
		wait "$SERVER_PID" 2>/dev/null || true
	fi
}
trap 'cleanup; sanitize_server_log' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ ! -x "$PETCLINIC_DIR/mvnw" ]]; then
	echo "PETCLINIC_DIR must point to the Spring PetClinic checkout: $PETCLINIC_DIR" >&2
	exit 2
fi

PETCLINIC_REMOTE="$(git -C "$PETCLINIC_DIR" remote get-url origin)"
if [[ "$PETCLINIC_REMOTE" != *"spring-projects/spring-petclinic"* ]]; then
	echo "Unexpected PetClinic origin: $PETCLINIC_REMOTE" >&2
	exit 2
fi
PETCLINIC_REMOTE="$(printf '%s' "$PETCLINIC_REMOTE" | sed -E 's#(https?://)[^/@]+@#\1[redacted]@#')"
STP_BRANCH="$(git -C "$STP_ROOT" branch --show-current)"
STP_HEAD="$(git -C "$STP_ROOT" rev-parse HEAD)"
STP_DIRTY="false"
[[ -z "$(git -C "$STP_ROOT" status --porcelain)" ]] || STP_DIRTY="true"
PETCLINIC_HEAD="$(git -C "$PETCLINIC_DIR" rev-parse HEAD)"
PETCLINIC_DIRTY="false"
[[ -z "$(git -C "$PETCLINIC_DIR" status --porcelain)" ]] || PETCLINIC_DIRTY="true"
JAVA_VERSION="$(java -version 2>&1)"
if [[ "$PROBE_PATH" != /* ]]; then
	echo "PROBE_PATH must start with '/': $PROBE_PATH" >&2
	exit 2
fi

mkdir -p "$OUTPUT_DIR"
rm -f "$OBSERVATIONS" "$OBSERVATIONS.tmp" "$SERVER_LOG" "$PROVENANCE"
cat >"$PROVENANCE" <<EOF
STP branch: $STP_BRANCH
STP HEAD: $STP_HEAD
STP worktree dirty: $STP_DIRTY
PetClinic origin: $PETCLINIC_REMOTE
PetClinic HEAD: $PETCLINIC_HEAD
PetClinic worktree dirty: $PETCLINIC_DIRTY
Java version:
$JAVA_VERSION
Probe path: $PROBE_PATH
PORT: $PORT
EOF

echo "Building standalone stp-remote-agent..."
"$STP_ROOT/gradlew" -p "$STP_ROOT" :stp-remote-agent:remoteAgentJar --no-daemon --console=plain
AGENT_JAR="$STP_ROOT/stp-remote-agent/build/libs/stp-remote-agent.jar"

echo "Building PetClinic executable jar (tests are not run)..."
(cd "$PETCLINIC_DIR" && ./mvnw -q -Dmaven.test.skip=true package)
PETCLINIC_JAR=""
for candidate in "$PETCLINIC_DIR"/target/spring-petclinic-*.jar; do
	if [[ -f "$candidate" && "$candidate" != *.jar.original ]]; then
		PETCLINIC_JAR="$candidate"
		break
	fi
done
if [[ -z "$PETCLINIC_JAR" ]]; then
	echo "Could not find the packaged PetClinic executable jar under $PETCLINIC_DIR/target" >&2
	exit 1
fi

HARNESS_CLASSES="$OUTPUT_DIR/harness-classes"
mkdir -p "$HARNESS_CLASSES"
javac --release 17 -d "$HARNESS_CLASSES" "$SCRIPT_DIR/PetClinicHttpHarness.java"

BASE_URL="http://127.0.0.1:$PORT"
AGENT_OPTIONS="output=$OBSERVATIONS;includes=org.springframework.samples.petclinic.;header=X-STP-Test-Execution-Id"
echo "Starting PetClinic JVM A at $BASE_URL with stp-remote-agent..."
java "-javaagent:$AGENT_JAR=$AGENT_OPTIONS" -jar "$PETCLINIC_JAR" \
	--server.address=127.0.0.1 --server.port="$PORT" >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!

echo "Waiting for PetClinic, then running the independent JVM B HTTP harness..."
if ! java -cp "$HARNESS_CLASSES" PetClinicHttpHarness wait --probe-path "$PROBE_PATH" "$BASE_URL"; then
	cleanup
	SERVER_PID=""
	sanitize_server_log
	echo "PetClinic readiness failed. See sanitized server log: $SERVER_LOG" >&2
	exit 1
fi
java -cp "$HARNESS_CLASSES" PetClinicHttpHarness run "$BASE_URL"

echo "Stopping PetClinic JVM A so the agent can flush observations..."
kill -TERM "$SERVER_PID"
if wait "$SERVER_PID"; then
	:
else
	SERVER_EXIT=$?
	if [[ "$SERVER_EXIT" -ne 143 ]]; then
		cat "$SERVER_LOG" >&2
		exit "$SERVER_EXIT"
	fi
fi
SERVER_PID=""
sanitize_server_log

if [[ ! -f "$OBSERVATIONS" ]]; then
	echo "stp-remote-agent did not write $OBSERVATIONS on shutdown" >&2
	exit 1
fi

echo "Verifying per-request observations in JVM B..."
java -cp "$HARNESS_CLASSES" PetClinicHttpHarness verify "$OBSERVATIONS"
echo "POC passed. Observations: $OBSERVATIONS"
echo "Provenance: $PROVENANCE"
echo "PetClinic server log: $SERVER_LOG"
