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
        echo "Using production environment..."
        shift
      ;;
    i)
        ENVIRONMENT_NAME=integration
        GDS_AWS_ACCOUNT=digital-identity-dev
        echo "Using integration environment..."
        shift
      ;;
    *)
        usage
        exit 1
      ;;
  esac
done

if [ $# -eq 0 ]
  then
    echo "Usage: check-user-account.sh [environment flag] email"
    exit 1
fi

printf "\nChecking account for user: %s\n" "$1"

gds-cli aws ${GDS_AWS_ACCOUNT} aws dynamodb get-item \
  --table-name "${ENVIRONMENT_NAME}-user-profile" \
  --key "{\"Email\": {\"S\": \"$1\"}}" \
  --projection-expression "#E, #PH, #AV, #EV, #S, #PS, #LS" \
  --expression-attribute-names "{\"#E\": \"Email\", \"#PH\": \"PhoneNumber\", \"#AV\": \"accountVerified\", \"#EV\": \"EmailVerified\", \"#S\": \"SubjectID\", \"#PS\": \"PublicSubjectID\", \"#LS\": \"LegacySubjectId\"}" \
  --region "${AWS_REGION}"


