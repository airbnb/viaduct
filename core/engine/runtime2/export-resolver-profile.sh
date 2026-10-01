#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf 'Usage: %s RECORDING.jfr OUTPUT_DIRECTORY\n' "$0" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage

recording=$1
output_directory=$2

[[ -f "$recording" ]] || {
  printf 'Recording does not exist: %s\n' "$recording" >&2
  exit 1
}

for command in jfr jq sha256sum; do
  command -v "$command" >/dev/null || {
    printf 'Required command is unavailable: %s\n' "$command" >&2
    exit 1
  }
done

mkdir -p "$output_directory"

normalize_output() {
  sed -e 's/[[:space:]]*$//' -e '${/^$/d;}'
}

jfr summary "$recording" |
  normalize_output >"$output_directory/summary.txt"
jfr view --width 240 hot-methods "$recording" >"$output_directory/hot-methods.txt"
jfr view --width 240 allocation-by-site "$recording" >"$output_directory/allocation-by-site.txt"
jfr view --width 240 gc-pauses "$recording" >"$output_directory/gc-pauses.txt"
jfr print \
  --events qplan.PropertyTestPhase \
  --stack-depth 0 \
  "$recording" |
  normalize_output \
  >"$output_directory/property-test-phases.txt"

if [[ ! -s "$output_directory/property-test-phases.txt" ]]; then
  rm "$output_directory/property-test-phases.txt"
fi

jfr print \
  --json \
  --events jdk.ExecutionSample,jdk.NativeMethodSample \
  --stack-depth 64 \
  "$recording" |
  jq -r '
    def frame:
      .method.type.name + "." + .method.name +
      (if (.lineNumber // 0) > 0 then ":" + (.lineNumber | tostring) else "" end);
    [
      .recording.events[]
      | select(((.values.stackTrace.frames? // []) | length) > 0)
      | {
          stack: ([.values.stackTrace.frames[] | frame] | join(";"))
        }
    ]
    | group_by(.stack)
    | map({ samples: length, stack: .[0].stack })
    | sort_by(.samples)
    | reverse
    | .[0:100]
    | (["samples", "stack"] | @tsv),
      (.[] | [.samples, .stack] | @tsv)
  ' >"$output_directory/execution-stacks.txt"

jfr print \
  --json \
  --events jdk.ObjectAllocationSample \
  --stack-depth 64 \
  "$recording" |
  jq -r '
    def frame:
      .method.type.name + "." + .method.name +
      (if (.lineNumber // 0) > 0 then ":" + (.lineNumber | tostring) else "" end);
    [
      .recording.events[]
      | select(((.values.stackTrace.frames? // []) | length) > 0)
      | {
          objectClass: (.values.objectClass.name // "<unknown>"),
          weight: .values.weight,
          stack: ([.values.stackTrace.frames[] | frame] | join(";"))
        }
    ]
    | group_by([.objectClass, .stack])
    | map({
        objectClass: .[0].objectClass,
        weight: (map(.weight) | add),
        stack: .[0].stack
      })
    | sort_by(.weight)
    | reverse
    | .[0:100]
    | (["weight_bytes", "object_class", "stack"] | @tsv),
      (.[] | [.weight, .objectClass, .stack] | @tsv)
  ' >"$output_directory/allocation-stacks.txt"

checksum=$(sha256sum "$recording" | awk '{ print $1 }')
printf '%s  %s\n' "$checksum" "$(basename -- "$recording")" \
  >"$output_directory/recording.sha256"
