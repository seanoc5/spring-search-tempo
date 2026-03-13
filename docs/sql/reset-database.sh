#!/bin/bash
# =============================================================================
# reset-database.sh - Hard reset the tempo database
# =============================================================================
# This script:
#   1. Drops and recreates the tempo database
#   2. Starts the Spring Boot app to create schema via JPA ddl-auto
#   3. Applies PostgreSQL-specific features
#   4. Optionally seeds initial data
#
# Usage:
#   ./docs/sql/reset-database.sh [--seed] [--no-app]
#
# Options:
#   --seed    Include seed data (crawl configs, etc.)
#   --no-app  Skip starting the app (use if app is already running)
#
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Default config (override with env vars)
DB_HOST="${DB_HOST:-minti9}"
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
echo "Seed data: $SEED_DATA"
echo ""

# Step 1: Drop and recreate database
echo ">>> Step 1: Dropping and recreating database..."
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_ADMIN_USER" -c "DROP DATABASE IF EXISTS $DB_NAME;"
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_ADMIN_USER" -c "CREATE DATABASE $DB_NAME OWNER $DB_USER;"
echo "    Database recreated."

# Step 2: Start app to create schema
if [ "$START_APP" = true ]; then
    echo ""
    echo ">>> Step 2: Starting application to create schema..."
    echo "    (This will take ~30 seconds for Spring Boot startup)"
    echo ""

    cd "$PROJECT_ROOT"

    # Start app in background, wait for it to be ready, then stop it
    ./gradlew bootRun &
    APP_PID=$!

    # Wait for app to be ready (check health endpoint)
    echo "    Waiting for application to start..."
    for i in {1..60}; do
        if curl -s http://localhost:8082/actuator/health > /dev/null 2>&1; then
            echo "    Application is ready."
            break
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
    echo ">>> Step 2: Skipping app start (--no-app specified)"
    echo "    Make sure the app has already run to create the schema!"
fi

# Step 3: Apply PostgreSQL-specific features
echo ""
echo ">>> Step 3: Applying PostgreSQL-specific features..."
psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f "$SCRIPT_DIR/essential-postgres-features.sql"

# Step 4: Seed data (optional)
if [ "$SEED_DATA" = true ]; then
    echo ""
    echo ">>> Step 4: Applying seed data..."
    psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f "$SCRIPT_DIR/012-seed-crawl-configs.sql"

    if [ -f "$SCRIPT_DIR/034-seed-user-ownership.sql" ]; then
        psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f "$SCRIPT_DIR/034-seed-user-ownership.sql"
    fi
    echo "    Seed data applied."
fi

echo ""
echo "=== Database reset complete ==="
echo ""
echo "You can now start the application:"
echo "  ./gradlew bootRun"
echo ""
