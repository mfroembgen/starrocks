#!/usr/bin/env bash
set -euo pipefail

fe_image=${1:?frontend image required}
be_image=${2:?backend image required}
network="theta-smoke-${RANDOM}-$$"
fe="${network}-fe"
be="${network}-be"

cleanup() {
    if [[ $? != 0 ]]; then
        docker logs "$fe" 2>&1 || true
        docker logs "$be" 2>&1 || true
        docker exec "$fe" sh -c 'tail -100 /opt/starrocks/fe/log/fe.warn.log' || true
        docker exec "$be" sh -c 'tail -100 /opt/starrocks/be/log/be.WARNING' || true
    fi
    docker rm -f "$fe" "$be" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

sql() {
    docker exec "$fe" mysql --protocol=TCP -h127.0.0.1 -P9030 -uroot --batch --skip-column-names -e "$1"
}

docker network create "$network" >/dev/null
docker run -d --name "$fe" --network "$network" --network-alias fe --memory=4g \
    --entrypoint bash "$fe_image" -c '
        echo '\''JAVA_OPTS="-Xms512m -Xmx2048m -Dlog4j2.formatMsgNoLookups=true"'\'' >> /opt/starrocks/fe/conf/fe.conf
        exec /opt/starrocks/fe/bin/start_fe.sh
    ' >/dev/null
ready=false
for ((attempt=0; attempt<90; attempt++)); do
    if sql 'SELECT 1' >/dev/null 2>&1; then ready=true; break; fi
    sleep 2
done
[[ "$ready" == true ]] || exit 1

docker run -d --name "$be" --network "$network" --network-alias be --memory=4g \
    --entrypoint bash "$be_image" -c '
        echo "mem_limit = 2G" >> /opt/starrocks/be/conf/be.conf
        exec /opt/starrocks/be/bin/start_be.sh
    ' >/dev/null
sql 'ALTER SYSTEM ADD BACKEND "be:9050"; CREATE DATABASE theta_smoke;'
ready=false
for ((attempt=0; attempt<90; attempt++)); do
    if sql 'CREATE TABLE IF NOT EXISTS theta_smoke.input (id INT, value BIGINT) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ("replication_num"="1");' >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 2
done
[[ "$ready" == true ]] || exit 1
sql 'INSERT INTO theta_smoke.input VALUES (1,1),(2,2),(3,NULL),(4,2);'
count=$(sql 'SELECT ds_theta_count_distinct(value,14) FROM theta_smoke.input;')
estimate=$(sql 'SELECT ds_theta_estimate(sketch) FROM (SELECT ds_theta_accumulate(value,14) AS sketch FROM theta_smoke.input) states;')
[[ "$count" == 2 && "$estimate" == 2 ]] || { echo "Unexpected Theta count/estimate: $count/$estimate" >&2; exit 1; }
echo 'Native FE/BE image smoke passed: Theta count and serialized-state estimate, including NULL.'
