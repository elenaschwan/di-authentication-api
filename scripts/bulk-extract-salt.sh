#!/usr/bin/env bash

set -eu

export AWS_REGION=eu-west-2
export ENVIRONMENT_NAME=build
export GDS_AWS_ACCOUNT=digital-identity-dev

while getopts "pi" opt; do
  case ${opt} in
  p)
    ENVIRONMENT_NAME=production
    GDS_AWS_ACCOUNT=digital-identity-prod
    echo -n "Using production environment..."
    shift
    ;;
  i)
    ENVIRONMENT_NAME=integration
    GDS_AWS_ACCOUNT=digital-identity-dev
    echo -n "Using integration environment..."
    shift
    ;;
  *)
    usage
    exit 1
    ;;
  esac
done

if [ $# -eq 0 ]; then
  echo "Usage: bulk-extract-salt.sh [environment flag] inputfile outputfile"
  exit 1
fi

function exportSubjectSaltLine() {
  echo -n "exporting salt for: ${1}"
  salt="$(
    gds-cli aws ${GDS_AWS_ACCOUNT} aws dynamodb query \
      --table-name "${ENVIRONMENT_NAME}-user-profile" \
      --index-name "SubjectIDIndex" \
      --key-condition-expression "SubjectID = :v1" \
      --expression-attribute-values "{\":v1\": {\"S\": \"$1\"}}" \
      --projection-expression "#E, #ST, #S, #PS, #LS" \
      --expression-attribute-names "{\"#E\": \"Email\", \"#ST\": \"salt\", \"#S\": \"SubjectID\", \"#PS\": \"PublicSubjectID\", \"#LS\": \"LegacySubjectId\"}" \
      --region "${AWS_REGION}" \
      --no-paginate |
      jq -r '.Items[0].salt.B'
  )"
  echo ${1},$salt >>"${2}"
}

while IFS= read -r intsub; do
  exportSubjectSaltLine "$intsub" "${2}"
done <"${1}"
