set -euo pipefail
sudo apt-get update -y && sudo apt-get install -y jq

VCS="github"

PIPE_NUM="$(curl -sS -H "Circle-Token: ${CIRCLE_TOKEN}" "https://circleci.com/api/v2/pipeline/${CIRCLE_PIPELINE_ID}" | jq -r '.number')"
PIPELINE_NAME="$(curl -sS -H "Circle-Token: ${CIRCLE_TOKEN}" "https://circleci.com/api/v2/pipeline/${CIRCLE_PIPELINE_ID}/workflow" | jq -r '.items[0].name')"
PIPELINE_URL="https://app.circleci.com/pipelines/${VCS}/${CIRCLE_PROJECT_USERNAME}/${CIRCLE_PROJECT_REPONAME}/${PIPE_NUM}"
WORKFLOW_URL="${PIPELINE_URL}/workflows/${CIRCLE_WORKFLOW_ID}"
JOB_URL="https://app.circleci.com/pipelines/${VCS}/${CIRCLE_PROJECT_USERNAME}/${CIRCLE_PROJECT_REPONAME}/${PIPE_NUM}/workflows/${CIRCLE_WORKFLOW_ID}/jobs/${CIRCLE_BUILD_NUM}"

MSG="$(cat <<EOF
----------- Circle-Ci Error: -------------

project_name=${CIRCLE_PROJECT_REPONAME}
org=${CIRCLE_PROJECT_USERNAME}
pipeline_name=${PIPELINE_NAME}
pipeline_number=${PIPE_NUM}
job_name=${CIRCLE_JOB}
job_number=${CIRCLE_BUILD_NUM}
branch=${CIRCLE_BRANCH}
commit_sha=${CIRCLE_SHA1}
repo_url=${CIRCLE_REPOSITORY_URL}
pipeline_url=${PIPELINE_URL}
workflow_url=${WORKFLOW_URL}
job_url=${JOB_URL}

------------------------------------------
EOF
)"
# Sustituye los placeholders por sus valores
MSG="${MSG//%PIPELINE_URL%/${PIPELINE_URL}}"
MSG="${MSG//%WORKFLOW_URL%/${WORKFLOW_URL}}"


# Construye JSON con jq; preserva saltos de línea como \n
PAYLOAD="$(jq -n --arg t "$MSG" '{text:$t}')"

# Exporta para el orb de Slack
printf 'export SLACK_PARAM_CUSTOM=%q\n' "$PAYLOAD" >> "$BASH_ENV"
