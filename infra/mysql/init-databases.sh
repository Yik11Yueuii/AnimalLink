#!/bin/sh
set -eu

# This script runs only when the MySQL data volume is first initialized.
for service in identity animal incident adoption intelligence; do
  case "$service" in
    identity) password="$IDENTITY_DB_PASSWORD" ;;
    animal) password="$ANIMAL_DB_PASSWORD" ;;
    incident) password="$INCIDENT_DB_PASSWORD" ;;
    adoption) password="$ADOPTION_DB_PASSWORD" ;;
    intelligence) password="$INTELLIGENCE_DB_PASSWORD" ;;
  esac
  case "$password" in
    *[!A-Za-z0-9_-]*|'') echo "Local database passwords must contain only letters, digits, _ or -" >&2; exit 1 ;;
  esac
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -u root <<SQL
CREATE DATABASE IF NOT EXISTS animallink_${service} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'animallink_${service}'@'%' IDENTIFIED BY '${password}';
ALTER USER 'animallink_${service}'@'%' IDENTIFIED BY '${password}';
GRANT ALL PRIVILEGES ON animallink_${service}.* TO 'animallink_${service}'@'%';
SQL
done
