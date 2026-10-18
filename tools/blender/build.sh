#!/usr/bin/env bash
# Rebuilds every model, texture and animation from Blender (headless).
# Usage: tools/blender/build.sh [path/to/blender]
set -euo pipefail
BLENDER="${1:-${BLENDER:-blender}}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
"$BLENDER" -b --factory-startup --python-exit-code 1 \
  -P "$ROOT/tools/blender/build_models.py" -- \
  --assets "$ROOT/src/main/resources/assets/orbital_railgun" \
  --out "$ROOT/build/blender"
