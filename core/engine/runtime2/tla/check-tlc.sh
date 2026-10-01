#!/usr/bin/env bash
set -euo pipefail

if (($# == 0)); then
  echo "usage: check-tlc.sh SPEC [TLC options]" >&2
  exit 2
fi

caller_dir=$PWD
spec_arg=$1
shift

if [[ $spec_arg = /* ]]; then
  spec_path=$spec_arg
else
  spec_path=$caller_dir/$spec_arg
fi

spec_dir=$(cd -- "$(dirname -- "$spec_path")" && pwd -P)
spec_file=$(basename -- "$spec_path")

args=()
while (($# > 0)); do
  case $1 in
    -config|-metadir|-userFile)
      option=$1
      shift
      if (($# == 0)); then
        echo "$option requires a path" >&2
        exit 2
      fi
      value=$1
      if [[ $value != /* ]]; then
        value=$caller_dir/$value
      fi
      args+=("$option" "$value")
      ;;
    *)
      args+=("$1")
      ;;
  esac
  shift
done

jar="$(mise where github:tlaplus/tlaplus)/tla2tools.jar"
library="$(mise where github:tlaplus/tlapm)/lib/tlaps"

cd -- "$spec_dir"
exec java -XX:+UseParallelGC \
  "-DTLA-Library=$spec_dir:$library" \
  -jar "$jar" "$spec_file" "${args[@]}"
