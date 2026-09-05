#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
petclinic_dir="${PETCLINIC_DIR:-/private/tmp/stp-round2-petclinic}"
cd "$repo_root"
./gradlew :stp-agent:agentJar :smart-test-picker-core:publishToMavenLocal \
  :smart-test-picker-common:publishToMavenLocal :smart-test-picker-maven:publishToMavenLocal
(cd "$petclinic_dir" && ./mvnw -q dependency:get -Dartifact=org.apache.maven.shared:maven-invoker:3.3.0)
ruby stp-petclinic-spike/round5/run_round5.rb
ruby stp-petclinic-spike/round5/normalize_round5.rb
