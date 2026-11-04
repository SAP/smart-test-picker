#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
for run in "$@"; do
    directory="raw/$run"
    result=$(jq -r .result "$directory/build.json")
    log="$directory/console.log"
    case "$run" in
        asm-*|jacoco-*|qualified-*)
            test "$result" = SUCCESS
            grep -q 'ASM09 PRODUCTION CODECS PASS' "$log"
            grep -q 'EXECUTABLE JOIN PASS tests=2' "$log"
            test -s "$directory/archive/build/stp/coverage-map-distributed.json"
            ;;
        mismatch-*)
            test "$result" = FAILURE
            grep -q 'mapping collector mismatch: manifest=' "$log"
            ! grep -q 'ASM09_MAPPING_BODY_ENTERED' "$log"
            ;;
        cli-mismatch-*)
            test "$result" = FAILURE
            grep -q 'Mapping collector mismatch: manifest=' "$log"
            ! grep -q 'Running b.BTests' "$log"
            ! grep -q 'ASM mapping agent .* resolved to' "$log"
            ;;
        forks-*)
            test "$result" = FAILURE
            grep -q 'unsupported forkCount=2' "$log"
            ! grep -q 'ASM mapping agent .* resolved to' "$log"
            ! grep -q 'Running b.BTests' "$log"
            ;;
        zero-scope-*)
            test "$result" = FAILURE
            grep -q 'classesTransformed=0' "$log"
            test ! -f "$directory/archive/module-b/target/stp/coverage-fragment-v3.json"
            test ! -f "$directory/archive/build/stp/fragment-0.json"
            ;;
        missing-fragment-*|incomplete-fragment-*)
            test "$result" = FAILURE
            grep -q '(Publish on controller)' "$log"
            grep -q 'reasonCode=MAPPING_INCOMPLETE' "$log"
            ! grep -q 'NotSerializableException' "$log"
            if [[ "$run" == missing-fragment-* ]]; then
                grep -q 'outcome=FRAGMENT_MISSING' "$log"
            fi
            test ! -f "$directory/archive/build/stp/coverage-map-distributed.json"
            ;;
        *) echo "Unknown scenario: $run" >&2; exit 1 ;;
    esac
    printf '%s expected-result-check PASS (%s)\n' "$run" "$result"
done
