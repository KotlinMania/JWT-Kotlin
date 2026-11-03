#!/bin/bash
# Run the standalone Android publication planner against this template.
set -euo pipefail
project="$(cd "$(dirname "$0")/.." && pwd)"
exec "$project/../tools/java-build-tool.sh" AndroidPublicationSync --source "$project" "$@"
