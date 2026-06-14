#!/bin/bash
# Load Weaver-Girl Grafana dashboards
# Usage: ./load-dashboards.sh <grafana-url> <api-key>
#
# Example:
#   ./load-dashboards.sh http://localhost:3000 admin:admin

GRAFANA_URL="${1:-http://localhost:3000}"
GRAFANA_AUTH="${2:-admin:admin}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Loading Weaver-Girl dashboards to ${GRAFANA_URL}..."

for dashboard in "${SCRIPT_DIR}"/*.json; do
    filename=$(basename "$dashboard")
    uid=$(grep -o '"uid"[[:space:]]*:[[:space:]]*"[^"]*"' "$dashboard" | head -1 | cut -d'"' -f4)
    title=$(grep -o '"title"[[:space:]]*:[[:space:]]*"[^"]*"' "$dashboard" | head -1 | cut -d'"' -f4)

    echo -n "  Loading ${title} (${filename})... "

    # Wrap dashboard JSON in Grafana's import format
    payload=$(cat <<EOF
{
  "dashboard": $(cat "$dashboard"),
  "overwrite": true,
  "inputs": []
}
EOF
)

    response=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST \
        -H "Content-Type: application/json" \
        -u "${GRAFANA_AUTH}" \
        -d "${payload}" \
        "${GRAFANA_URL}/api/dashboards/db")

    if [ "$response" = "200" ]; then
        echo "OK"
    else
        echo "FAILED (HTTP ${response})"
    fi
done

echo "Done!"
