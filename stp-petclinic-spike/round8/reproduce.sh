#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"
./gradlew :stp-agent:agentJar
ruby stp-petclinic-spike/round8/run_round8.rb
ruby stp-petclinic-spike/round8/compare_round8.rb
