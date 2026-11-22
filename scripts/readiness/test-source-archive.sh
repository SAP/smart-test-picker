#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
# Run the source-copy consumers from committed sources without Git metadata.
set -euo pipefail

source_root=$(cd "$(dirname "$0")/../.." && pwd)
mkdir -p "$source_root/build"
# Keep the nested bundle fixture's GnuPG socket path below the platform limit.
archive_root=$(mktemp -d "$source_root/build/XXX")
git -C "$source_root" archive HEAD | tar -x -C "$archive_root"
test ! -e "$archive_root/.git"
export GIT_CEILING_DIRECTORIES="$source_root/build"
if git -C "$archive_root" rev-parse --show-toplevel >/dev/null 2>&1; then
    echo 'Source archive must not belong to a Git work tree' >&2
    exit 1
fi
echo "Testing source archive: $archive_root"
cd "$archive_root"
./gradlew --no-daemon --console=plain verifyStpVersionConsistency
./gradlew --no-daemon --console=plain :smart-test-picker:test \
    --tests '*StpVersionConsistencyFunctionalTest' \
    --tests '*CentralBundleFunctionalTest' --rerun-tasks
