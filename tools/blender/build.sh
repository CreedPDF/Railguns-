#!/usr/bin/env bash
# Rebuilds every model, texture and animation from Blender (headless).
# Usage: tools/blender/build.sh [path/to/blender]
# Headless Blender occasionally segfaults (exit 139) on CI runners; such runs are retried (the retries skip the
# preview renders, where the crashes happen; committed icons / renders are kept).
# ONLY=krl, ONLY=ship or ONLY=station builds just that set (dev branches use it to iterate faster; other assets stay as committed).
set -uo pipefail
BLENDER="${1:-${BLENDER:-blender}}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ASSETS="$ROOT/src/main/resources/assets/orbital_railgun"
OUT="$ROOT/build/blender"

run() {  # run <script> <label> [extra args...]
  local script="$1" label="$2"; shift 2
  local extra="" code=1
  for attempt in 1 2 3 4; do
    "$BLENDER" -b --factory-startup --python-exit-code 1 -P "$ROOT/tools/blender/$script" -- \
      --assets "$ASSETS" --out "$OUT" "$@" $extra
    code=$?
    if [ $code -eq 0 ]; then return 0; fi
    if [ $code -ne 139 ] && [ $code -ne 134 ]; then return $code; fi
    extra="--no-render"
    echo "Blender crashed (exit $code), retrying $label ($attempt/4)..."
  done
  return 1
}

ONLY="${ONLY:-}"
if [ -z "$ONLY" ]; then
  run build_models.py railgun --collision "$ROOT/src/main/resources/orbital_railgun_collision.json" || exit 1
fi
if [ -z "$ONLY" ] || [ "$ONLY" = "krl" ]; then
  run build_krl.py KRL || exit 1
fi
if [ -z "$ONLY" ] || [ "$ONLY" = "ship" ]; then
  run build_ship.py starship || exit 1
fi
if [ -z "$ONLY" ] || [ "$ONLY" = "station" ]; then
  run build_station.py station || exit 1
fi
exit 0
