#!/usr/bin/env bash
set -euo pipefail

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


# Times GET /records/{id} for the model records seeded by seed.sh and
# reports min/median/max latency — the read-path cost of resolving each
# record's trainingData references (issue #24).
#
# Like seed.sh this uses only the public record API, so the numbers cover
# whichever storage plugin the registry runs and stay comparable across
# backends.
#
# Usage: ./bench.sh [manifest]
#   manifest       seed manifest path (default: newest in PERF_OUT)
#   REGISTRY_URL   registry base URL   (default http://localhost:8082)
#   PERF_ITERS     timed reads         (default 30)
#   PERF_WARMUP    untimed warm reads  (default 2)
#   PERF_OUT       manifest directory  (default <this dir>/out)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REGISTRY_URL="${REGISTRY_URL:-http://localhost:8082}"
PERF_ITERS="${PERF_ITERS:-30}"
PERF_WARMUP="${PERF_WARMUP:-2}"
PERF_OUT="${PERF_OUT:-${SCRIPT_DIR}/out}"

for tool in curl jq sort awk; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "error: ${tool} is required" >&2; exit 1; }
done

MANIFEST="${1:-}"
if [ -z "${MANIFEST}" ]; then
  MANIFEST="$(ls -t "${PERF_OUT}"/*.manifest 2>/dev/null | head -1 || true)"
fi
if [ -z "${MANIFEST}" ] || [ ! -f "${MANIFEST}" ]; then
  echo "error: no manifest found — run ./seed.sh first, or pass a manifest path" >&2
  exit 1
fi

echo "Timing GET /records/{id} on ${REGISTRY_URL} (n=${PERF_ITERS}, warmup=${PERF_WARMUP})"
printf '%-52s %5s %9s %9s %9s %5s\n' "record" "n" "min" "median" "max" "refs"

while IFS= read -r id; do
  [ -z "${id}" ] && continue
  url="${REGISTRY_URL}/records/$(jq -rn --arg v "${id}" '$v|@uri')"

  if ! probe="$(curl -sf "${url}")"; then
    echo "error: GET ${url} failed — is the registry running and seeded?" >&2
    exit 1
  fi
  refs="$(jq -r '(.trainingData // []) | length' <<< "${probe}")"

  for _ in $(seq 1 "${PERF_WARMUP}"); do
    curl -sf -o /dev/null "${url}"
  done

  times="$(for _ in $(seq 1 "${PERF_ITERS}"); do
    curl -sf -o /dev/null -w '%{time_total}\n' "${url}"
  done)"

  printf '%s\n' "${times}" | sort -n | awk \
    -v label="$(basename "${id}")" -v refs="${refs}" '
    { a[NR] = $1 }
    END {
      n = NR
      m = (n % 2) ? a[(n + 1) / 2] : (a[n / 2] + a[n / 2 + 1]) / 2
      printf "%-52s %5d %8.0fms %8.0fms %8.0fms %5s\n", label, n, a[1] * 1000, m * 1000, a[n] * 1000, refs
    }'
done < "${MANIFEST}"
