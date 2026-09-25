#!/usr/bin/env bash
set -uo pipefail

fall_back() {
  echo "::warning title=Dependency mirror disabled::$1 Resolving from Maven Central and the Gradle Plugin Portal."
  exit 0
}

role_arn="${VIADUCT_DEPENDENCY_MIRROR_ROLE_ARN:-}"
domain="${VIADUCT_DEPENDENCY_MIRROR_DOMAIN:-viaduct}"
repository="${VIADUCT_DEPENDENCY_MIRROR_REPOSITORY:-viaduct-ci}"
region="${VIADUCT_DEPENDENCY_MIRROR_REGION:-us-east-1}"

if [ -n "${VIADUCT_ARTIFACTORY_MIRROR:-}" ]; then
  echo "VIADUCT_ARTIFACTORY_MIRROR is already set; leaving it unchanged."
  exit 0
fi
if [ -z "$role_arn" ]; then
  echo "VIADUCT_DEPENDENCY_MIRROR_ROLE_ARN is not set; using Maven Central and the Gradle Plugin Portal."
  exit 0
fi
if [ -z "${ACTIONS_ID_TOKEN_REQUEST_URL:-}" ] || [ -z "${ACTIONS_ID_TOKEN_REQUEST_TOKEN:-}" ]; then
  fall_back "No GitHub OIDC token is available to this job (fork pull request, or missing 'id-token: write')."
fi
if ! command -v aws >/dev/null 2>&1; then
  fall_back "The AWS CLI is not installed on this runner."
fi

domain_owner="$(echo "$role_arn" | cut -d: -f5)"

oidc_response="$(curl -sSf --retry 3 \
  -H "Authorization: bearer ${ACTIONS_ID_TOKEN_REQUEST_TOKEN}" \
  "${ACTIONS_ID_TOKEN_REQUEST_URL}&audience=sts.amazonaws.com")" \
  || fall_back "Could not obtain a GitHub OIDC token."
oidc_token="$(printf '%s' "$oidc_response" | sed -n 's/.*"value":"\([^"]*\)".*/\1/p')"
[ -n "$oidc_token" ] || fall_back "The GitHub OIDC response did not contain a token."
echo "::add-mask::${oidc_token}"

token_file="$(mktemp "${RUNNER_TEMP:-/tmp}/dependency-mirror-oidc.XXXXXX")"
trap 'rm -f "$token_file"' EXIT
printf '%s' "$oidc_token" > "$token_file"
export AWS_ROLE_ARN="$role_arn"
export AWS_WEB_IDENTITY_TOKEN_FILE="$token_file"
export AWS_ROLE_SESSION_NAME="viaduct-ci-${GITHUB_RUN_ID:-local}"
export AWS_REGION="$region"
unset AWS_PROFILE AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN

auth_token="$(aws codeartifact get-authorization-token \
  --domain "$domain" --domain-owner "$domain_owner" \
  --duration-seconds 21600 \
  --query authorizationToken --output text)" \
  || fall_back "Could not obtain a mirror authorization token for role ${role_arn}."
[ -n "$auth_token" ] || fall_back "The mirror returned an empty authorization token."
echo "::add-mask::${auth_token}"

endpoint="$(aws codeartifact get-repository-endpoint \
  --domain "$domain" --domain-owner "$domain_owner" \
  --repository "$repository" --format maven \
  --query repositoryEndpoint --output text)" \
  || fall_back "Could not look up the mirror endpoint for ${domain}/${repository}."

echo "VIADUCT_ARTIFACTORY_MIRROR=https://aws:${auth_token}@${endpoint#https://}" >> "$GITHUB_ENV"
echo "Resolving dependencies through the mirror: ${endpoint}"
