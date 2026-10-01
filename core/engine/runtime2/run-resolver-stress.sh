#!/usr/bin/env bash
set -uo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")" || exit 1

seed=20260809
resolvers=(resolver03 resolver08 resolver23 resolution)
pids=()

for resolver in "${resolvers[@]}"; do
  log="${resolver}-stress.log"
  ../../../gradlew -p ../.. \
    ":engine:runtime2:${resolver}Stress" \
    "-P${resolver}StressSeed=$seed" \
    --console=plain \
    >"$log" 2>&1 &
  pids+=("$!")
  printf 'Started %s stress (PID %s, log: %s)\n' \
    "$resolver" "${pids[-1]}" "$log"
done

status=0
for index in "${!pids[@]}"; do
  resolver=${resolvers[$index]}
  pid=${pids[$index]}
  if wait "$pid"; then
    printf '%s stress completed successfully\n' "$resolver"
  else
    exit_code=$?
    printf '%s stress failed with exit code %s\n' \
      "$resolver" "$exit_code" >&2
    status=1
  fi
done

exit "$status"
