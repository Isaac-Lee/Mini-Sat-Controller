#!/bin/sh
# Create local-only credentials once; never print secrets or overwrite an existing environment.
set -eu
cd "$(dirname "$0")/.."
umask 077
mkdir -p .local
if [ -f .local/msa.env ]; then
  echo 'Existing .local/msa.env preserved.'
  exit 0
fi
for name in MSC_POSTGRES_PASSWORD MSC_RABBIT_PASSWORD MSC_S3_SECRET_KEY MSC_LOCAL_ADMIN_PASSWORD MSC_LOCAL_OPERATOR_PASSWORD MSC_LOCAL_REQUESTER_PASSWORD MSC_SERVICE_PASSWORD; do
  printf '%s=%s\n' "$name" "$(openssl rand -hex 24)" >> .local/msa.env
done
for service in tasking planning flight_dynamics mission_definition reference_data ground_operations spacecraft_control space_link monitoring anomaly acquisition product mission_projection simulator; do
  variable=$(printf '%s' "$service" | tr '[:lower:]' '[:upper:]')
  printf 'MSC_DB_%s_PASSWORD=%s\n' "$variable" "$(openssl rand -hex 24)" >> .local/msa.env
done
printf 'MSC_RABBIT_USER=msc\nMSC_S3_ACCESS_KEY=msc-local\n' >> .local/msa.env
# This explicitly simulated time reference is not IERS data and never claims real leap-second validity.
printf 'MSC_TIME_SOURCE=simulation-only-offset-v1\nMSC_TIME_OFFSET_SECONDS=37\nMSC_TIME_VALID_FROM=2020-01-01T00:00:00Z\nMSC_TIME_VALID_UNTIL=2100-01-01T00:00:00Z\n' >> .local/msa.env
echo 'Created local credentials in .local/msa.env (mode 0600).'
