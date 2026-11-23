#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
world=$(mktemp -d)
trap 'rm -rf -- "$world"' EXIT
printf '%s\n' 'eula=true' > "$world/eula.txt"
printf '%s\n' 'server-ip=127.0.0.1' 'server-port=0' 'view-distance=3' 'simulation-distance=3' 'pause-when-empty-seconds=0' 'level-type=minecraft:single_biome_surface' 'generator-settings={"biome":"minecraft:stony_peaks"}' 'level-seed=73019' > "$world/server.properties"
for phase in prepare resume verify; do
    printf '%s\n' "$phase" > "$world/resource-phase"
    ./gradlew "$@" "-PhouseholdTestDir=$world" -PresourceParcelProbe runHouseholdRestart
    test -s "$world/resource-$phase-passed"
done
echo "Three server processes verified the personal quarry and forest, both homes, harvested goods and replacement provenance."
