#!/usr/bin/env sh
set -eu

runs="${1:-20}"
i=1
while [ "$i" -le "$runs" ]; do
  echo "RUN $i"
  ../../gradlew --no-daemon --quiet -p . run
  i=$((i + 1))
done
