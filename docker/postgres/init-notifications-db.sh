#!/bin/sh
# Se ejecuta UNA sola vez, la primera vez que PostgreSQL inicializa su volumen de datos.
# Cada servicio tiene su propia base y su propio usuario: notifications-service no puede ver las tablas de payments.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<EOSQL
CREATE USER notifications WITH PASSWORD '${NOTIFICATIONS_DB_PASSWORD:-notifications}';
CREATE DATABASE notifications OWNER notifications;
EOSQL
