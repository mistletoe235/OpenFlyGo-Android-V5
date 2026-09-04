#!/bin/zsh
set -euo pipefail

ROOT="${0:A:h:h}"
HZ="${1:-50}"
SDK_DIR=$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" | tail -1)
BUILD_TOOLS=$(find "$SDK_DIR/build-tools" -mindepth 1 -maxdepth 1 -type d | sort | tail -1)
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
KEYSTORE="$HOME/.android/debug.keystore"
INPUT="$ROOT/app/build/outputs/apk/full/debug/app-full-debug.apk"
OUTPUT_DIR="$ROOT/app/build/outputs/apk/hil-rate-experiment"
UNALIGNED="$OUTPUT_DIR/OpenFlyGo-v5-hil-rate-${HZ}-unaligned.apk"
ALIGNED="$OUTPUT_DIR/OpenFlyGo-v5-hil-rate-${HZ}-aligned.apk"
OUTPUT="$OUTPUT_DIR/OpenFlyGo-v5-hil-rate-${HZ}-debug.apk"

cd "$ROOT"
./gradlew :app:assembleFullDebug -PHIL_NATIVE_RATE_EXPERIMENT_HZ="$HZ"
mkdir -p "$OUTPUT_DIR"
python3 "$ROOT/tools/patch_dji_simulator_rate.py" --input "$INPUT" --output "$UNALIGNED" --hz "$HZ"
"$ZIPALIGN" -P 16 -f 4 "$UNALIGNED" "$ALIGNED"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$OUTPUT" \
  "$ALIGNED"
"$ZIPALIGN" -P 16 -c 4 "$OUTPUT"
"$APKSIGNER" verify --verbose --print-certs "$OUTPUT"
shasum -a 256 "$OUTPUT"
echo "$OUTPUT"
