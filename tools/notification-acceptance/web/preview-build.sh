#!/bin/sh
set -eu
if [ "${VERCEL_ENV:-}" != preview ]; then
  echo "Development acceptance deployment requires Vercel preview; production is forbidden." >&2
  exit 1
fi
# Use the same reviewed runtime as CI, even before its npm package is published.
runtime_dir="$(mktemp -d)"
curl --fail --location --retry 2 "https://github.com/denoland/deno/releases/download/v2.9.7/deno-x86_64-unknown-linux-gnu.zip" --output "$runtime_dir/deno.zip"
printf '%s  %s\n' 'c6527f24f4b16031d3ae4fa9f658d5f11534c8d84ce7dc8502420280919c3490' "$runtime_dir/deno.zip" | sha256sum --check
unzip -q "$runtime_dir/deno.zip" -d "$runtime_dir"
"$runtime_dir/deno" run --frozen --allow-env=VERCEL_ENV --allow-read --allow-write --allow-run tools/notification-acceptance/web/preview-build.ts
