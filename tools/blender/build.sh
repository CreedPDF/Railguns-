#!/usr/bin/env bash
# Rebuilds every model, texture and animation from Blender (headless).
# Usage: tools/blender/build.sh [path/to/blender]
# Headless Blender occasionally segfaults (exit 139) on CI runners; such runs are retried.
set -uo pipefail
BLENDER="${1:-${BLENDER:-blender}}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
EXTRA=""
# ONLY=krl skips the railgun models (their committed assets are kept) - used by dev-krl* branches to iterate faster
for attempt in 1 2 3 4; do
  if [ "${ONLY:-}" = "krl" ]; then code=0; break; fi
  "$BLENDER" -b --factory-startup --python-exit-code 1 \
    -P "$ROOT/tools/blender/build_models.py" -- \
    --assets "$ROOT/src/main/resources/assets/orbital_railgun" \
    --out "$ROOT/build/blender" \
    --collision "$ROOT/src/main/resources/orbital_railgun_collision.json" $EXTRA
  code=$?
  # The crashes happen in Cycles' preview renders; retries skip them (committed icons/renders are kept).
  EXTRA="--no-render"
  if [ $code -eq 0 ]; then break; fi
  if [ $code -ne 139 ] && [ $code -ne 134 ]; then exit $code; fi
  echo "Blender crashed (exit $code), retrying ($attempt/4)..."
done
[ $code -eq 0 ] || exit 1

# KRL (Kinetic Rail Launcher) models (own retry loop, so its preview renders are not skipped by the railgun's retries)
EXTRA=""
for attempt in 1 2 3 4; do
  "$BLENDER" -b --factory-startup --python-exit-code 1 \
    -P "$ROOT/tools/blender/build_krl.py" -- \
    --assets "$ROOT/src/main/resources/assets/orbital_railgun" \
    --out "$ROOT/build/blender" $EXTRA
  code=$?
  if [ $code -eq 0 ]; then exit 0; fi
  if [ $code -ne 139 ] && [ $code -ne 134 ]; then exit $code; fi
  EXTRA="--no-render"
  echo "Blender crashed (exit $code), retrying KRL ($attempt/4)..."
done
exit 1
