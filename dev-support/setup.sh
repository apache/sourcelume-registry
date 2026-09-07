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
# TODO: ATLAS_REF is a placeholder. Confirm the real tag name against
# https://github.com/apache/atlas/tags once the target Atlas version is decided
# (see docs/architecture.md open questions - this should match the parent pom's
# `atlas.version` property).
ATLAS_REF="${ATLAS_REF:-release-2.5.0}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENDOR_DIR="${SCRIPT_DIR}/vendor/atlas-docker"

if [ -d "${VENDOR_DIR}" ]; then
  echo "Already vendored at ${VENDOR_DIR}."
  echo "Remove that directory first if you want to re-vendor a different ref."
  exit 0
fi

echo "Vendoring apache/atlas@${ATLAS_REF}:dev-support/atlas-docker ..."
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

git clone --depth 1 --branch "${ATLAS_REF}" https://github.com/apache/atlas.git "${TMP_DIR}"
mkdir -p "$(dirname "${VENDOR_DIR}")"
cp -r "${TMP_DIR}/dev-support/atlas-docker" "${VENDOR_DIR}"

echo "Vendored to ${VENDOR_DIR}."
echo "See dev-support/README.md for how to build and run it, and how it joins sourcelume-network."
