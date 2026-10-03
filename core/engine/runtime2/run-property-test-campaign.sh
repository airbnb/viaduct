#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

if (( $# < 1 )); then
  printf 'Usage: %s <campaign.json|classpath:/resource.json> [round ...]\n' \
    "${BASH_SOURCE[0]##*/}" >&2
  exit 2
fi

campaign=$1
shift
campaign_name=${campaign##*/}
campaign_name=${campaign_name%.json}
report_dir=${PROPERTY_TEST_CAMPAIGN_REPORT_DIR:-"$PWD/build/reports/$campaign_name"}
launcher="$PWD/build/install/property-test-round/bin/property-test-round"
mkdir -p -- "$report_dir"

printf 'Building the direct property-test round launcher\n'
../../../gradlew -p ../.. :engine:runtime2:installPropertyTestRoundLauncher --console=plain

if (( $# > 0 )); then
  rounds=("$@")
else
  rounds=()
  while IFS= read -r round; do
    rounds+=("$round")
  done < <("$launcher" --campaign "$campaign" --list-rounds)
fi

campaign_started_at=$(date +%s)
for round in "${rounds[@]}"; do
  if [[ ! $round =~ ^[0-9]+$ ]]; then
    printf 'Campaign round must be a positive integer: %s\n' "$round" >&2
    exit 2
  fi
  round_number=$((10#$round))
  if (( round_number < 1 )); then
    printf 'Campaign round must be a positive integer: %s\n' "$round" >&2
    exit 2
  fi
  log="$report_dir/round-$(printf '%03d' "$round_number").log"
  round_started_at=$(date +%s)
  printf 'Starting %s round %s (log: %s)\n' \
    "$campaign_name" "$round_number" "$log"

  if ! "$launcher" --campaign "$campaign" --round "$round_number" 2>&1 | tee "$log"; then
    printf 'Round %s failed. Replay the whole round with:\n' "$round_number" >&2
    printf '  ./%s %q %q\n' \
      "${BASH_SOURCE[0]##*/}" "$campaign" "$round_number" >&2
    exit 1
  fi
  printf 'Completed round %s in %ss wall-clock\n' \
    "$round_number" "$(( $(date +%s) - round_started_at ))"
done

printf 'Completed %s round(s) in %ss wall-clock. Logs: %s\n' \
  "${#rounds[@]}" "$(( $(date +%s) - campaign_started_at ))" "$report_dir"
