#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 1 ]]; then
    echo "Usage: $0 GRAPHQL_JAVA_PR_JAR [GRADLE_TASK_OR_OPTION ...]" >&2
    exit 2
fi
gj_jar=$1
shift
if [[ $# -eq 0 ]]; then
    set -- :core:engine:runtime2:jmhJar --no-configuration-cache
fi
exec "$(dirname "$0")/with-graphql-java-pr.sh" "$gj_jar" "$@"
