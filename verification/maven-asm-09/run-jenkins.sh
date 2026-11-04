#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p raw
url=http://localhost:8082
# Public, disposable POC credentials from the existing Compose configuration.
auth=admin:stp-plugin-poc-admin
cookies=$(mktemp)
trap 'rm -f "$cookies"' EXIT
crumb=$(curl -fsS -c "$cookies" -u "$auth" "$url/crumbIssuer/api/json")
header=$(printf '%s' "$crumb" | jq -r '.crumbRequestField + ":" + .crumb')
curl -fsS -b "$cookies" -u "$auth" -H "$header" --data-urlencode script@configure.groovy "$url/scriptText" > raw/configuration.log
if grep -q 'Exception' raw/configuration.log; then
    head -15 raw/configuration.log
    exit 1
fi
for scenario in "$@"; do
    job="maven-asm-09-$scenario"
    number=$(curl -fsS -u "$auth" "$url/job/$job/api/json" | jq -r .nextBuildNumber)
    curl -fsS -b "$cookies" -u "$auth" -H "$header" -X POST "$url/job/$job/buildWithParameters?CASE=$scenario" > /dev/null
    echo "START $job #$number"
    while true; do
        result=$(curl -fsS -u "$auth" "$url/job/$job/$number/api/json" 2>/dev/null | jq -r '.result // "RUNNING"') || result=QUEUED
        if [ "$result" != RUNNING ] && [ "$result" != QUEUED ]; then break; fi
        sleep 5
    done
    destination="raw/$scenario-$number"
    mkdir -p "$destination"
    curl -fsS -u "$auth" "$url/job/$job/$number/consoleText" > "$destination/console.log"
    curl -fsS -u "$auth" "$url/job/$job/$number/api/json" > "$destination/build.json"
    if curl -fsS -u "$auth" "$url/job/$job/$number/artifact/*zip*/archive.zip" > "$destination/artifacts.zip"; then
        unzip -qo "$destination/artifacts.zip" -d "$destination"
    fi
    rm "$destination/artifacts.zip"
    echo "END $job #$number $result"
done
