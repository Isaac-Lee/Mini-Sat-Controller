#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
service=${1:?service name required}
port=${2:?port required}
: "${JAVA_HOME:?Configure a JDK 21 JAVA_HOME}"
. ./.local/msa.env
case "$service" in
  tasking) db_user=msc_tasking; db_password=${MSC_DB_TASKING_PASSWORD:?} ;;
  planning) db_user=msc_planning; db_password=${MSC_DB_PLANNING_PASSWORD:?} ;;
  flight-dynamics) db_user=msc_flight_dynamics; db_password=${MSC_DB_FLIGHT_DYNAMICS_PASSWORD:?} ;;
  mission-definition) db_user=msc_mission_definition; db_password=${MSC_DB_MISSION_DEFINITION_PASSWORD:?} ;;
  reference-data) db_user=msc_reference_data; db_password=${MSC_DB_REFERENCE_DATA_PASSWORD:?} ;;
  ground-operations) db_user=msc_ground_operations; db_password=${MSC_DB_GROUND_OPERATIONS_PASSWORD:?} ;;
  spacecraft-control) db_user=msc_spacecraft_control; db_password=${MSC_DB_SPACECRAFT_CONTROL_PASSWORD:?} ;;
  space-link) db_user=msc_space_link; db_password=${MSC_DB_SPACE_LINK_PASSWORD:?} ;;
  monitoring) db_user=msc_monitoring; db_password=${MSC_DB_MONITORING_PASSWORD:?} ;;
  anomaly) db_user=msc_anomaly; db_password=${MSC_DB_ANOMALY_PASSWORD:?} ;;
  acquisition) db_user=msc_acquisition; db_password=${MSC_DB_ACQUISITION_PASSWORD:?} ;;
  product) db_user=msc_product; db_password=${MSC_DB_PRODUCT_PASSWORD:?} ;;
  mission-projection) db_user=msc_mission_projection; db_password=${MSC_DB_MISSION_PROJECTION_PASSWORD:?} ;;
  simulator) db_user=msc_simulator; db_password=${MSC_DB_SIMULATOR_PASSWORD:?} ;;
  *) echo 'Unknown service' >&2; exit 2 ;;
esac
case "$service" in
  flight-dynamics|reference-data|acquisition|product|simulator)
    object_access=$MSC_S3_ACCESS_KEY; object_secret=$MSC_S3_SECRET_KEY ;;
  *) object_access=; object_secret= ;;
esac
# Spring Boot loads nested classes lazily; never run the mutable Maven target artifact.
artifact="services/msc-$service-service/target/msc-$service-service-0.1.0-SNAPSHOT.jar"
artifact_digest=$(shasum -a 256 "$artifact" | cut -d ' ' -f 1)
runtime_dir=".local/runtime/$service"
mkdir -p "$runtime_dir"
runtime_jar="$runtime_dir/$artifact_digest.jar"
if [ ! -f "$runtime_jar" ]; then
  partial_jar="$runtime_jar.$$.part"
  trap 'rm -f "$partial_jar"' EXIT HUP INT TERM
  cp "$artifact" "$partial_jar"
  copied_digest=$(shasum -a 256 "$partial_jar" | cut -d ' ' -f 1)
  [ "$artifact_digest" = "$copied_digest" ] || { echo 'Build artifact changed while copying; retry after build completes' >&2; exit 1; }
  mv "$partial_jar" "$runtime_jar"
  trap - EXIT HUP INT TERM
fi
# Supply only this service's database credential, not the complete development env file.
exec env -i PATH="$PATH" JAVA_HOME="$JAVA_HOME" \
  SERVER_ADDRESS=127.0.0.1 SERVER_PORT="$port" \
  MSC_S3_ENDPOINT=http://127.0.0.1:59000 MSC_S3_ACCESS_KEY="$object_access" MSC_S3_SECRET_KEY="$object_secret" \
  MSC_OREKIT_ARCHIVE="${MSC_OREKIT_ARCHIVE:-}" MSC_OREKIT_SHA256="${MSC_OREKIT_SHA256:-}" \
  MSC_DATABASE_URL="jdbc:postgresql://127.0.0.1:55432/$db_user" \
  MSC_DATABASE_USER="$db_user" MSC_DATABASE_PASSWORD="$db_password" \
  MSC_RABBIT_HOST=127.0.0.1 MSC_RABBIT_PORT=55672 MSC_RABBIT_USER="$MSC_RABBIT_USER" MSC_RABBIT_PASSWORD="$MSC_RABBIT_PASSWORD" \
  MSC_SECURITY_MODE=local MSC_LOCAL_ADMIN_PASSWORD="$MSC_LOCAL_ADMIN_PASSWORD" \
  MSC_LOCAL_OPERATOR_PASSWORD="$MSC_LOCAL_OPERATOR_PASSWORD" MSC_LOCAL_REQUESTER_PASSWORD="$MSC_LOCAL_REQUESTER_PASSWORD" MSC_SERVICE_PASSWORD="$MSC_SERVICE_PASSWORD" \
  MSC_TIME_SOURCE="$MSC_TIME_SOURCE" MSC_TIME_OFFSET_SECONDS="$MSC_TIME_OFFSET_SECONDS" MSC_TIME_VALID_FROM="$MSC_TIME_VALID_FROM" MSC_TIME_VALID_UNTIL="$MSC_TIME_VALID_UNTIL" \
  MSC_SERVICES_TASKING_URL=http://127.0.0.1:8101 \
  MSC_SERVICES_PLANNING_URL=http://127.0.0.1:8102 \
  MSC_SERVICES_FLIGHT_DYNAMICS_URL=http://127.0.0.1:8103 \
  MSC_SERVICES_MISSION_DEFINITION_URL=http://127.0.0.1:8104 \
  MSC_SERVICES_REFERENCE_DATA_URL=http://127.0.0.1:8105 \
  MSC_SERVICES_GROUND_OPERATIONS_URL=http://127.0.0.1:8106 \
  MSC_SERVICES_SPACECRAFT_CONTROL_URL=http://127.0.0.1:8107 \
  MSC_SERVICES_SPACE_LINK_URL=http://127.0.0.1:8108 \
  MSC_SERVICES_MONITORING_URL=http://127.0.0.1:8109 \
  MSC_SERVICES_ANOMALY_URL=http://127.0.0.1:8110 \
  MSC_SERVICES_ACQUISITION_URL=http://127.0.0.1:8111 \
  MSC_SERVICES_PRODUCT_URL=http://127.0.0.1:8112 \
  MSC_SERVICES_MISSION_PROJECTION_URL=http://127.0.0.1:8113 \
  MSC_SERVICES_SIMULATOR_URL=http://127.0.0.1:8114 \
  "$JAVA_HOME/bin/java" -jar "$runtime_jar"
