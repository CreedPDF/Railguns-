#!/usr/bin/env bash
# Rebuilds every model, texture and animation from Blender (headless).
# Usage: tools/blender/build.sh [path/to/blender]
# Headless Blender occasionally segfaults (exit 139) on CI runners; such runs are retried.
set -uo pipefail
BLENDER="${1:-${BLENDER:-blender}}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
for attempt in 1 2 3 4; do
  "$BLENDER" -b --factory-startup --python-exit-code 1 \
    -P "$ROOT/tools/blender/build_models.py" -- \
    --assets "$ROOT/src/main/resources/assets/orbital_railgun" \
    --out "$ROOT/build/blender" \
    --collision "$ROOT/src/main/resources/orbital_railgun_collision.json"
  code=$?
  if [ $code -eq 0 ]; then exit 0; fi
  if [ $code -ne 139 ] && [ $code -ne 134 ]; then exit $code; fi
  echo "Blender crashed (exit $code), retrying ($attempt/4)..."
done
exit 1
