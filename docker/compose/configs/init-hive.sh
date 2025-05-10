#!/bin/bash
set -e

# Espera a PostgreSQL
while ! nc -z hive-postgres 5432; do sleep 5; done

# Inicializa esquema si no existe
/opt/hive/bin/schematool -dbType postgres -initSchema || true

# Inicia metastore
exec /opt/hive/bin/hive --service metastore
