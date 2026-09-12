#!/bin/sh
set -eu
for service in tasking planning flight_dynamics mission_definition reference_data ground_operations spacecraft_control space_link monitoring anomaly acquisition product mission_projection simulator; do
  variable=$(printf '%s' "$service" | tr '[:lower:]' '[:upper:]')
  password=$(printenv "MSC_DB_${variable}_PASSWORD")
  role="msc_${service}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v role="$role" -v password="$password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'role', :'password') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'role', :'role') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'role') \gexec
SQL
done
