#!/bin/bash
# =============================================================================
# reset-database.sh - Hard reset the tempo database
# =============================================================================
# This script:
#   0. Creates the tempo role if it doesn't exist
#   1. Drops and recreates the tempo database
#   2. Installs pgvector extension (requires admin; needed before JPA runs)
#   3. Starts the Spring Boot app to create schema via JPA ddl-auto
#   4. Applies PostgreSQL-specific features (FTS, indexes, session tables, etc.)
#   5. Optionally seeds initial data
#
# Usage:
#   ./docs/sql/reset-database.sh [--seed] [--no-app]
#
# Options:
#   --seed    Include seed data (crawl configs, etc.)
#   --no-app  Skip starting the app (use if app is already running)
#
# Environment variables (all optional):
#   DB_HOST          PostgreSQL host (default: localhost)
#   DB_PORT          PostgreSQL port (default: 5432)
#   DB_NAME          Database name (default: tempo)
#   DB_USER          Application user (default: tempo)
#   DB_ADMIN_USER    Admin user for DDL operations (default: postgres)
#   PGPASSWORD       Password for all users (default: password)
#
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Default config (override with env vars)
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-tempo}"
DB_USER="${DB_USER:-tempo}"
DB_ADMIN_USER="${DB_ADMIN_USER:-postgres}"
PGPASSWORD="${PGPASSWORD:-password}"

export PGPASSWORD

# Parse arguments
SEED_DATA=false
START_APP=true

for arg in "$@"; do
    case $arg in
        --seed)
            SEED_DATA=true
            shift
            ;;
        --no-app)
            START_APP=false
            shift
            ;;
    esac
done

echo "=== Database Reset Script ==="
echo "Host: $DB_HOST:$DB_PORT"
echo "Database: $DB_NAME"
echo "User: $DB_USER (admin: $DB_ADMIN_USER)"
echo "Seed data: $SEED_DATA"
echo ""

# Step 0: Create the application role if it doesn't exist
echo ">>> Step 0: Ensuring '$DB_USER' role exists..."
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_ADMIN_USER" -tc \
    "SELECT 1 FROM pg_roles WHERE rolname = '$DB_USER'" | grep -q 1 \
    || psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_ADMIN_USER" -c \
    "CREATE ROLE $DB_USER WITH LOGIN PASSWORD '$PGPASSWORD';"
echo "    Role '$DB_USER' is ready."

# Step 1: Drop and recreate database
echo ""
echo ">>> Step 1: Dropping and recreating database..."
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_ADMIN_USER" -c "DROP DATABASE IF EXISTS $DB_NAME;"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_ADMIN_USER" -c "CREATE DATABASE $DB_NAME OWNER $DB_USER;"
echo "    Database recreated."

# Step 2: Install pgvector extension (must happen BEFORE JPA runs,
# because ContentChunk entity uses columnDefinition = "vector(1024)")
echo ""
echo ">>> Step 2: Installing pgvector extension..."
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_ADMIN_USER" -d "$DB_NAME" -c "CREATE EXTENSION IF NOT EXISTS vector;"
echo "    pgvector extension installed."

# Step 3: Start app to create schema via JPA ddl-auto: update
if [ "$START_APP" = true ]; then
    echo ""
    echo ">>> Step 3: Starting application to create schema..."
    echo "    (This will take ~30 seconds for Spring Boot startup)"
    echo ""

    cd "$PROJECT_ROOT"

    # Pass dev profile (ddl-auto: update) and point at the correct DB host
    SPRING_PROFILES_ACTIVE=dev \
    JDBC_DATABASE_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
    ./gradlew bootRun &
    APP_PID=$!

    # Wait for app to be ready (check health endpoint)
    echo "    Waiting for application to start..."
    for i in {1..90}; do
        if curl -s http://localhost:8082/actuator/health > /dev/null 2>&1; then
            echo "    Application is ready."
            break
        fi
        if ! kill -0 $APP_PID 2>/dev/null; then
            echo "    ERROR: Application exited unexpectedly."
            exit 1
        fi
        sleep 1
    done

    # Give it a moment to finish any startup tasks
    sleep 3

    # Stop the app
    echo "    Stopping application..."
    kill $APP_PID 2>/dev/null || true
    wait $APP_PID 2>/dev/null || true
    echo "    Application stopped."
else
    echo ""
    echo ">>> Step 3: Skipping app start (--no-app specified)"
    echo "    Make sure the app has already run to create the schema!"
fi

# Step 4: Apply PostgreSQL-specific features (FTS, indexes, functions, session tables, etc.)
echo ""
echo ">>> Step 4: Applying PostgreSQL-specific features..."
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f "$SCRIPT_DIR/essential-postgres-features.sql"

# Step 5: Seed data (optional)
if [ "$SEED_DATA" = true ]; then
    echo ""
    echo ">>> Step 5: Applying seed data..."
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f "$SCRIPT_DIR/archive/012-seed-crawl-configs.sql"

    if [ -f "$SCRIPT_DIR/archive/034-seed-user-ownership.sql" ]; then
        psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f "$SCRIPT_DIR/archive/034-seed-user-ownership.sql"
    fi
    echo "    Seed data applied."
fi

echo ""
echo "=== Database reset complete ==="
echo ""
echo "You can now start the application:"
echo "  ./gradlew bootRun"
echo ""
