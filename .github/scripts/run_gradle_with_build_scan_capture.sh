#!/usr/bin/env bash

set -u -o pipefail

label=""
start_message=""
success_message=""
failure_message=""
artifact_path="build-scan-artifact.json"
exit_mode="propagate"
declare -a failure_notes=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --label)
      label="$2"
      shift 2
      ;;
    --start-message)
      start_message="$2"
      shift 2
      ;;
    --success-message)
      success_message="$2"
      shift 2
      ;;
    --failure-message)
      failure_message="$2"
      shift 2
      ;;
    --failure-note)
      failure_notes+=("$2")
      shift 2
      ;;
    --artifact-path)
      artifact_path="$2"
      shift 2
      ;;
    --always-succeed)
      exit_mode="always-zero"
      shift
      ;;
    --)
      shift
      break
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ -z "$label" ]]; then
  echo "Missing required argument: --label" >&2
  exit 2
fi

if [[ $# -eq 0 ]]; then
  echo "Missing command to run" >&2
  exit 2
fi

if [[ -n "$start_message" ]]; then
  echo "$start_message"
fi

log_file="$(mktemp)"
if "$@" 2>&1 | tee "$log_file"; then
  status=0
  if [[ -n "$success_message" ]]; then
    echo "$success_message"
  fi
else
  status=$?
  if [[ -n "$failure_message" ]]; then
    echo "$failure_message"
  fi
  for note in "${failure_notes[@]}"; do
    echo "$note"
  done
fi

# Only upload scan artifacts created by the trusted capture script in this step.
rm -f "$artifact_path"
python3 .github/scripts/maybe_capture_build_scan_artifact.py \
  "$log_file" \
  --status "$status" \
  --artifact-path "$artifact_path" \
  --label "$label"

if [[ "$exit_mode" == "always-zero" ]]; then
  exit 0
fi

exit "$status"
