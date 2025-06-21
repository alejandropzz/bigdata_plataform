#!/bin/bash

echo "Waiting to PostgreSQL is already"
until pg_isready -h postgres -p 5432 -U postgres; do
  sleep 2
done

echo "PostgreSQL is already"
exec "$@"


tables=()
declare -A table_columns

current_table=""
columns=()

while IFS= read -r line; do
    # Elimina espacios al inicio y al final
    trimmed=$(echo "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')

    # Detecta el nombre de la tabla
    if [[ $trimmed =~ ^([a-zA-Z0-9_]+)[[:space:]]*\( ]]; then
        current_table="${BASH_REMATCH[1]}"
        tables+=("$current_table")
        columns=()
        continue
    fi

    # Detecta cierre del bloque
    if [[ $trimmed =~ ^\)\; ]] || [[ $trimmed =~ ^\) ]]; then
        if [[ -n "$current_table" ]]; then
            table_columns["$current_table"]="${columns[*]}"
        fi
        current_table=""
        continue
    fi

    # Procesa columnas si hay tabla activa
    if [[ -n "$current_table" && "$trimmed" != "" ]]; then
        col_name=$(echo "$trimmed" | cut -d' ' -f1 | sed 's/,//')
        columns+=("$col_name")
    fi
done < schema-ddl.conf

for table in "${tables[@]}"; do
    echo "Tabla: $table"
    echo "Columnas: ${table_columns[$table]}"
    echo
done

set -a
source .env.postgres
set +a

set -a
source .env.tableconf
set +a

for table in "${tables[@]}"; do
    AUDIT_TABLE="audit_log_${table}_stream"
    PGPASSWORD="$PGPASSWORD" psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -c "
    CREATE TABLE IF NOT EXISTS ${AUDIT_TABLE} (
        ${PK_TABLE_COLUMN} UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        ${DB_NAME_COLUMN} TEXT,        
        ${TABLE_NAME_COLUMN} TEXT,
        ${RECORD_ID_COLUMN} TEXT,
        ${OPERATION_COLUMN} CHAR(1),
        ${OLD_DATA_COLUMN} JSONB,
        ${NEW_DATA_COLUMN} JSONB,
        ${TIMESTAMP_COLUMN} TIMESTAMPTZ DEFAULT now(),
        ${IS_IN_KAFKA_COLUMN} BOOLEAN DEFAULT FALSE
    );
    "

    PK=$(PGPASSWORD=$PGPASSWORD psql -U "$PGUSER" -h "$PGHOST" -p "$PGPORT" -d "$PGDATABASE" -t -c \
    "SELECT a.attname AS column_name
    FROM pg_index i
    JOIN pg_class c ON c.oid = i.indrelid
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(i.indkey)
    WHERE c.relname = '$table' AND i.indisprimary;")

    PK=$(echo "$PK" | xargs)

    if [[ -z "$PK" ]]; then
        echo "Table '$table' does not have a primary key"
        continue
    fi

    # Obtener columnas para esta tabla (si no hay, usar todas con to_jsonb)
    cols="${table_columns[$table]}"
    if [[ -z "$cols" ]]; then
        echo "Table '$table' does not have a specific columns"
    fi

    # Construir fragmentos para jsonb_build_object
    jsonb_new=""
    jsonb_old=""
    if [[ -n "$cols" ]]; then
        for col in $cols; do
            jsonb_new+="'$col', NEW.$col, "
            jsonb_old+="'$col', OLD.$col, "
        done        
        jsonb_new=${jsonb_new%, }
        jsonb_old=${jsonb_old%, }
    fi

    TABLE_LOWER=$(echo "$table" | tr '[:upper:]' '[:lower:]')
    FUNC_NAME="audit_log_${TABLE_LOWER}_trigger"

    PGPASSWORD=$PGPASSWORD psql -U "$PGUSER" -h "$PGHOST" -p "$PGPORT" -d "$PGDATABASE" <<EOF

DROP FUNCTION IF EXISTS $PGSCHEMA.${FUNC_NAME}() CASCADE;

CREATE OR REPLACE FUNCTION $PGSCHEMA.${FUNC_NAME}() RETURNS TRIGGER AS \$\$
DECLARE
    record_id_val TEXT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        record_id_val := NEW.$PK::TEXT;
        INSERT INTO $AUDIT_TABLE(${DB_NAME_COLUMN}, ${TABLE_NAME_COLUMN}, ${RECORD_ID_COLUMN}, ${OPERATION_COLUMN}, ${NEW_DATA_COLUMN})
        VALUES (
            current_database(),
            '$table',
            record_id_val,
            'I',
            $(
              if [[ -n "$cols" ]]; then
                echo "jsonb_build_object($jsonb_new)"
              else
                echo "to_jsonb(NEW)"
              fi
            )
        );
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        record_id_val := NEW.$PK::TEXT;
        INSERT INTO $AUDIT_TABLE(${DB_NAME_COLUMN}, ${TABLE_NAME_COLUMN}, ${RECORD_ID_COLUMN}, ${OPERATION_COLUMN}, ${OLD_DATA_COLUMN}, ${NEW_DATA_COLUMN})
        VALUES (
            current_database(),
            '$table',
            record_id_val,
            'U',
            $(
              if [[ -n "$cols" ]]; then
                echo "jsonb_build_object($jsonb_old)"
              else
                echo "to_jsonb(OLD)"
              fi
            ),
            $(
              if [[ -n "$cols" ]]; then
                echo "jsonb_build_object($jsonb_new)"
              else
                echo "to_jsonb(NEW)"
              fi
            )
        );
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        record_id_val := OLD.$PK::TEXT;
        INSERT INTO $AUDIT_TABLE(${DB_NAME_COLUMN}, ${TABLE_NAME_COLUMN}, ${RECORD_ID_COLUMN}, ${OPERATION_COLUMN}, ${OLD_DATA_COLUMN})
        VALUES (
            current_database(),
            '$table',
            record_id_val,
            'D',
            $(
              if [[ -n "$cols" ]]; then
                echo "jsonb_build_object($jsonb_old)"
              else
                echo "to_jsonb(OLD)"
              fi
            )
        );
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
\$\$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS ${TABLE_LOWER}_insert_trigger ON $PGSCHEMA."$table";
CREATE TRIGGER ${TABLE_LOWER}_insert_trigger
AFTER INSERT ON $PGSCHEMA."$table"
FOR EACH ROW EXECUTE FUNCTION $PGSCHEMA.${FUNC_NAME}();

DROP TRIGGER IF EXISTS ${TABLE_LOWER}_update_trigger ON $PGSCHEMA."$table";
CREATE TRIGGER ${TABLE_LOWER}_update_trigger
AFTER UPDATE ON $PGSCHEMA."$table"
FOR EACH ROW EXECUTE FUNCTION $PGSCHEMA.${FUNC_NAME}();

DROP TRIGGER IF EXISTS ${TABLE_LOWER}_delete_trigger ON $PGSCHEMA."$table";
CREATE TRIGGER ${TABLE_LOWER}_delete_trigger
AFTER DELETE ON $PGSCHEMA."$table"
FOR EACH ROW EXECUTE FUNCTION $PGSCHEMA.${FUNC_NAME}();

EOF

done