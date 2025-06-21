#!/bin/bash

ENV_TABLES_FILE=".env.tables"

if [ ! -f "$ENV_TABLES_FILE" ]; then
  echo "No se encontró el archivo $ENV_TABLES_FILE"
  exit 1
fi

TABLES_LINE=$(grep ^TABLES= "$ENV_TABLES_FILE")
if [ -z "$TABLES_LINE" ]; then
  echo "No se encontró la variable TABLES en $ENV_TABLES_FILE"
  exit 1
fi

TABLES=$(echo "$TABLES_LINE" | cut -d '=' -f2)

SCALA_FILE="/opt/cdc_scd_scripts/postgres/PostgresKafkaProducer.scala"

JARS="/opt/jars/play-functional_2.12-2.9.2.jar,/opt/jars/play-json_2.12-2.9.4.jar,/opt/jars/HikariCP-5.1.0.jar,/opt/jars/config-1.4.2.jar,/opt/jars/dotenv-java-3.0.0.jar,/opt/jars/kafka-clients-3.7.0.jar,/opt/jars/mysql-binlog-connector-java-0.21.0.jar,/opt/jars/postgresql-42.7.3.jar"

SPARK_SHELL_CMD="/opt/bitnami/spark/bin/spark-shell"

IFS=',' read -ra TABLE_ARRAY <<< "$TABLES"
for TABLE_NAME in "${TABLE_ARRAY[@]}"; do  
  echo "Ejecutando spark-shell para tabla: $TABLE_NAME"

  $SPARK_SHELL_CMD \
    --master yarn \
    --jars "$JARS" \
    --driver-class-path "/opt/jars/postgresql-42.7.3.jar" \
    --conf "spark.driver.extraClassPath=/opt/jars/postgresql-42.7.3.jar" \
    <<EOF &
:load $SCALA_FILE
ChangePublisher.run("$TABLE_NAME")
EOF

done

wait  # Espera a que todos los procesos en segundo plano terminen
