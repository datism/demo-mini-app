#!/bin/bash
# Patroni runs post_init script on cluster bootstrap
# It passes the connection URI as argument $1

CONN_URI="$1"
echo "Running post_init with connection URI: $CONN_URI"

psql "$CONN_URI" -c "CREATE ROLE replicator WITH REPLICATION PASSWORD 'replpassword' LOGIN;"
psql "$CONN_URI" -c "CREATE ROLE homer_user WITH LOGIN PASSWORD 'homerSeven';"
psql "$CONN_URI" -c "CREATE ROLE homer_data WITH LOGIN PASSWORD 'homerSeven';"
psql "$CONN_URI" -c "ALTER ROLE homer_user WITH SUPERUSER;"
psql "$CONN_URI" -c "ALTER ROLE homer_data WITH SUPERUSER;"
