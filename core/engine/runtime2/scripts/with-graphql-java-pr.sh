#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 GRAPHQL_JAVA_PR_JAR [GRADLE_TASK_OR_OPTION ...]" >&2
    exit 2
fi

gj_jar=$(realpath "$1")
shift
if [[ $# -eq 0 ]]; then
    set -- :core:engine:runtime2:check
fi
repo_root=$(git rev-parse --show-toplevel)
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
module_dir="$scratch/maven/com/graphql-java/graphql-java/26.0-pr4562-69c7bfb"
mkdir -p "$module_dir"
cp "$gj_jar" "$module_dir/graphql-java-26.0-pr4562-69c7bfb.jar"
cat > "$module_dir/graphql-java-26.0-pr4562-69c7bfb.pom" <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.graphql-java</groupId><artifactId>graphql-java</artifactId><version>26.0-pr4562-69c7bfb</version>
  <dependencies>
    <dependency><groupId>com.graphql-java</groupId><artifactId>java-dataloader</artifactId><version>6.0.0</version><scope>compile</scope></dependency>
    <dependency><groupId>org.reactivestreams</groupId><artifactId>reactive-streams</artifactId><version>1.0.3</version><scope>compile</scope></dependency>
    <dependency><groupId>org.jspecify</groupId><artifactId>jspecify</artifactId><version>1.0.0</version><scope>compile</scope></dependency>
  </dependencies>
</project>
POM
cat > "$scratch/override.init.gradle" <<'GRADLE'
gradle.beforeSettings { settings ->
    settings.pluginManagement.repositories.gradlePluginPortal()
    settings.pluginManagement.repositories.maven {
        url = settings.uri(System.getenv('GJ_PR_MAVEN_REPO'))
    }
}
gradle.settingsEvaluated { settings ->
    settings.dependencyResolutionManagement.repositories.maven {
        url = settings.uri(System.getenv('GJ_PR_MAVEN_REPO'))
    }
}

GRADLE

echo "GraphQL Java PR jar SHA-256: $(shasum -a 256 "$gj_jar" | cut -d ' ' -f 1)"
GJ_PR_MAVEN_REPO="$scratch/maven" "$repo_root/gradlew" \
    -I "$scratch/override.init.gradle" \
    "$@" --console=plain
