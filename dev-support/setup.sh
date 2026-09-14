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


# Vendors Apache Atlas's OWN dev-support/atlas-docker tooling at a pinned git ref,
# rather than Sourcelume hand-maintaining a duplicate copy of Atlas's multi-file
# build/run setup. Atlas's own tooling already handles building Atlas from source,
# choosing a backend (postgres/hbase), and version pinning - reproducing that here
# by hand would just be a second copy to keep in sync forever.
#
# ATLAS_REF should match the parent pom's `atlas.version` property.
ATLAS_REF="${ATLAS_REF:-release-2.5.0}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENDOR_DIR="${SCRIPT_DIR}/vendor/atlas-docker"

echo "=== Sourcelume Dev Support Setup ==="

# 1. Ensure shared Docker network exists
if ! docker network inspect sourcelume-network >/dev/null 2>&1; then
  echo "Creating shared docker network 'sourcelume-network'..."
  docker network create sourcelume-network || true
else
  echo "Docker network 'sourcelume-network' already exists."
fi

# 2. Vendor Atlas docker tooling
if [ -d "${VENDOR_DIR}" ]; then
  echo "Atlas docker tooling already vendored at ${VENDOR_DIR}."
  echo "Remove that directory first if you want to re-vendor a different ref."
else
  echo "Vendoring apache/atlas@${ATLAS_REF}:dev-support/atlas-docker ..."
  TMP_DIR="$(mktemp -d)"
  trap 'rm -rf "${TMP_DIR}"' EXIT

  git clone --depth 1 --branch "${ATLAS_REF}" https://github.com/apache/atlas.git "${TMP_DIR}"
  mkdir -p "$(dirname "${VENDOR_DIR}")"
  cp -r "${TMP_DIR}/dev-support/atlas-docker" "${VENDOR_DIR}"

  echo "Vendored to ${VENDOR_DIR}."
fi

# 3. Configure vendored .env defaults for Sourcelume local dev
if [ -f "${VENDOR_DIR}/.env" ]; then
  echo "Configuring ${VENDOR_DIR}/.env defaults (BUILD_HOST_SRC=false, BRANCH=${ATLAS_REF}, ATLAS_BACKEND=postgres)..."
  if [[ "$OSTYPE" == "darwin"* ]]; then
    sed -i '' 's/^BUILD_HOST_SRC=.*/BUILD_HOST_SRC=false/' "${VENDOR_DIR}/.env" 2>/dev/null || true
    sed -i '' "s/^BRANCH=.*/BRANCH=${ATLAS_REF}/" "${VENDOR_DIR}/.env" 2>/dev/null || true
    sed -i '' 's/^ATLAS_BACKEND=.*/ATLAS_BACKEND=postgres/' "${VENDOR_DIR}/.env" 2>/dev/null || true
  else
    sed -i 's/^BUILD_HOST_SRC=.*/BUILD_HOST_SRC=false/' "${VENDOR_DIR}/.env" 2>/dev/null || true
    sed -i "s/^BRANCH=.*/BRANCH=${ATLAS_REF}/" "${VENDOR_DIR}/.env" 2>/dev/null || true
    sed -i 's/^ATLAS_BACKEND=.*/ATLAS_BACKEND=postgres/' "${VENDOR_DIR}/.env" 2>/dev/null || true
  fi
fi

echo ""
echo "Setup complete! See dev-support/README.md for build, start, and testing instructions."
