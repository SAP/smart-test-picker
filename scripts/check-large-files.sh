#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

limit_bytes=5242880
failed=0

# One repository-relative path per case arm, with a policy justification beside it.
is_allowlisted() {
	case "$1" in
		# path/to/indispensable-artifact) return 0 ;;
		*) return 1 ;;
	esac
}

while IFS= read -r -d '' path; do
	[[ -f "$path" ]] || continue
	size=$(wc -c < "$path" | tr -d ' ')
	if (( size > limit_bytes )) && ! is_allowlisted "$path"; then
		printf '%s\n' "tracked file exceeds 5 MiB: $path ($size bytes)" >&2
		failed=1
	fi
done < <(git ls-files -z)

if (( failed )); then
	exit 1

fi

printf '%s\n' 'large-file check passed (tracked-file limit: 5 MiB)'
