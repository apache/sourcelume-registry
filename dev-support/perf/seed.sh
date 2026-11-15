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


# Seeds the registry with the workload described in
# https://github.com/apache/sourcelume-registry/issues/24: a set of
# ProvenanceRecord datasets plus TrainingProvenanceRecord models whose
# trainingData lists reference a configurable number of them, so the read
# path of GET /records/{id} can be benchmarked.
#
# The script talks only to the public record API, so it exercises whatever
# storage plugin the registry runs (the default PostgreSQL distribution or
# the Atlas one) and the numbers stay comparable across backends.
#
# Usage: ./seed.sh
#   REGISTRY_URL   registry base URL   (default http://localhost:8082)
#   PERF_PREFIX    record id prefix    (default perf-<utc timestamp>)
#   PERF_DATASETS  dataset count       (default 161)
#   PERF_REFS      model ref counts    (default "0 12 40 161")
#   PERF_OUT       manifest directory  (default <this dir>/out)
#
# Every record id carries PERF_PREFIX, so reruns write new records instead
# of colliding with earlier seeds. The model record ids land in
# <PERF_OUT>/<PERF_PREFIX>.manifest for bench.sh.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REGISTRY_URL="${REGISTRY_URL:-http://localhost:8082}"
PERF_PREFIX="${PERF_PREFIX:-perf-$(date -u +%Y%m%dT%H%M%SZ)}"
PERF_DATASETS="${PERF_DATASETS:-161}"
PERF_REFS="${PERF_REFS:-0 12 40 161}"
PERF_OUT="${PERF_OUT:-${SCRIPT_DIR}/out}"

for tool in curl jq; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "error: ${tool} is required" >&2; exit 1; }
done

mkdir -p "${PERF_OUT}"
MANIFEST="${PERF_OUT}/${PERF_PREFIX}.manifest"
: > "${MANIFEST}"

TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
DATASET_IRI_PREFIX="https://sourcelume.apache.org/records/${PERF_PREFIX}-ds-"
MODEL_IRI_PREFIX="https://sourcelume.apache.org/records/${PERF_PREFIX}-model-refs"

post_record() {
  local status
  status="$(curl -s -o /dev/null -w '%{http_code}' -X POST "${REGISTRY_URL}/records" \
    -H 'Content-Type: application/ld+json' --data-binary @- <<< "$1")"
  if [ "${status}" != "200" ] && [ "${status}" != "201" ]; then
    echo "error: POST /records answered HTTP ${status}" >&2
    exit 1
  fi
}

dataset_doc() {
  jq -n \
    --arg id "${DATASET_IRI_PREFIX}$1" \
    --arg identifier "https://example.org/datasets/${PERF_PREFIX}-ds-$1" \
    --arg name "perf dataset $1" \
    --arg ts "${TS}" '{
      "@context": "https://sourcelume.apache.org/context/0.0.1/sourcelume.jsonld",
      id: $id,
      type: "ProvenanceRecord",
      identifier: $identifier,
      name: $name,
      license: "https://www.apache.org/licenses/LICENSE-2.0",
      creator: [{type: "schema:Organization", name: "dev-support perf harness"}],
      created: $ts,
      added: $ts,
      contentCreated: $ts,
      origin: "Seeded by dev-support/perf for issue #24 read-path benchmarking.",
      custodyChain: [{agent: "https://example.org/", action: "created", startTime: $ts}]
    }'
}

model_doc() {
  local refs="$1"
  local training
  training="$(seq 1 "${refs}" | jq -R --arg prefix "${PERF_PREFIX}" '{
      dataset: ("https://sourcelume.apache.org/records/" + $prefix + "-ds-" + .),
      phase: (["pretraining", "fine-tuning", "alignment", "evaluation"][((. | tonumber) - 1) % 4])
    }' | jq -s .)"
  jq -n \
    --arg id "${MODEL_IRI_PREFIX}${refs}" \
    --arg identifier "https://example.org/models/${PERF_PREFIX}-refs${refs}" \
    --arg name "perf model refs${refs}" \
    --arg ts "${TS}" \
    --argjson trainingData "${training}" '{
      "@context": "https://sourcelume.apache.org/context/0.0.1/sourcelume.jsonld",
      id: $id,
      type: "TrainingProvenanceRecord",
      identifier: $identifier,
      name: $name,
      version: "1",
      creator: [{type: "schema:Organization", name: "dev-support perf harness"}],
      created: $ts,
      disclosure: "complete",
      trainingData: $trainingData
    }'
}

echo "Seeding ${PERF_DATASETS} datasets into ${REGISTRY_URL} (prefix ${PERF_PREFIX})..."
for i in $(seq 1 "${PERF_DATASETS}"); do
  post_record "$(dataset_doc "${i}")"
  if [ "$((i % 20))" -eq 0 ] || [ "${i}" -eq "${PERF_DATASETS}" ]; then
    printf '\r  %s/%s datasets' "${i}" "${PERF_DATASETS}"
  fi
done
printf '\n'

for refs in ${PERF_REFS}; do
  echo "Seeding model record with ${refs} trainingData references..."
  post_record "$(model_doc "${refs}")"
  printf '%s\n' "${MODEL_IRI_PREFIX}${refs}" >> "${MANIFEST}"
done

echo "Manifest: ${MANIFEST}"
echo "Run ./bench.sh ${MANIFEST} to time GET /records/{id}."
