#!/usr/bin/env bash
set -uo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")" || exit 1

seed=20260809
resolvers=(03 08 23 26)
pids=()

for resolver in "${resolvers[@]}"; do
  log="resolver${resolver}-stress.log"
  ../gradlew -p . \
    ":semantics:resolver${resolver}Stress" \
    "-Presolver${resolver}StressSeed=$seed" \
    --console=plain \
    >"$log" 2>&1 &
  pids+=("$!")
  printf 'Started resolver%s stress (PID %s, log: %s)\n' \
    "$resolver" "${pids[-1]}" "$log"
done

status=0
for index in "${!pids[@]}"; do
  resolver=${resolvers[$index]}
  pid=${pids[$index]}
  if wait "$pid"; then
    printf 'resolver%s stress completed successfully\n' "$resolver"
  else
    exit_code=$?
    printf 'resolver%s stress failed with exit code %s\n' \
      "$resolver" "$exit_code" >&2
    status=1
  fi
done

exit "$status"
