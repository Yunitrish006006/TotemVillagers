#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
settlement_dir=$(mktemp -d)
trap 'rm -rf -- "$settlement_dir"' EXIT
printf '%s\n' 'eula=true' > "$settlement_dir/eula.txt"
printf '%s\n' 'server-ip=127.0.0.1' 'server-port=0' 'view-distance=3' 'simulation-distance=3' 'pause-when-empty-seconds=0' 'level-type=minecraft:flat' > "$settlement_dir/server.properties"
for phase in prepare resume verify; do
    printf '%s\n' "$phase" > "$settlement_dir/household-test-phase"
    ./gradlew "$@" "-PhouseholdTestDir=$settlement_dir" runHouseholdRestart
    case "$phase" in
        prepare|resume)
            expected_lines=2
            if [[ "$phase" == resume ]]; then expected_lines=3; fi
            if ! awk -v expected="$expected_lines" 'END { exit NR != expected }' "$settlement_dir/household-test-saved"; then
                echo "Household restart phase $phase did not save its expected plots" >&2
                exit 1
            fi
            ;;
    esac
done
cat "$settlement_dir/household-test-passed"
printf '\n'
