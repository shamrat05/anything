#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
args=()
if [[ "$(uname -o)" == Android ]]; then
  aapt_path="${FASTSEARCH_AAPT2:-$(command -v aapt2 || true)}"
  if [[ -n "$aapt_path" ]]; then args+=("-Pandroid.aapt2FromMavenOverride=$aapt_path"); fi
fi
./gradlew "${args[@]}" "$@"
