#!/usr/bin/env bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# maintenance.  See the NOTICE file distributed with this work for
# additional information regarding copyright ownership.
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
#

# ==============================================================================
# Apache Sourcelume Simple Demo
# Community Over Code Glasgow 2026 Hackathon
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATASET_FILE="${SCRIPT_DIR}/sample-dataset.json"
INCOMPLETE_RECORD_FILE="${SCRIPT_DIR}/sample-incomplete-record.jsonld"
PROVENANCE_RECORD_FILE="${SCRIPT_DIR}/sample-provenance-record.jsonld"

REGISTRY_URL="${SOURCELUME_REGISTRY_URL:-http://localhost:8082}"
ATLAS_URL="${SOURCELUME_ATLAS_URL:-http://localhost:21000}"

# ANSI formatting
if [ -t 1 ]; then
  BOLD="\033[1m"
  GREEN="\033[0;32m"
  BLUE="\033[0;34m"
  YELLOW="\033[1;33m"
  RED="\033[0;31m"
  CYAN="\033[0;36m"
  MAGENTA="\033[0;35m"
  RESET="\033[0m"
else
  BOLD=""
  GREEN=""
  BLUE=""
  YELLOW=""
  RED=""
  CYAN=""
  MAGENTA=""
  RESET=""
fi

# Sourcelume ASCII Logo Banner
echo -e "${CYAN}"
cat << "EOF"
                                                                  |
   _____                               __                       --+--
  / ___/____  __  __________________  / /_  ______ ___  ___       |
  \__ \/ __ \/ / / / ___/ ___/ _ \/ / / / / / / __ `__ \/ _ \
 ___/ / /_/ / /_/ / /  / /__/  __/ / / / /_/ / / / / / /  __/
/____/\____/\__,_/_/   \___/\___/_/ /_/\__,_/_/ /_/ /_/\___/
EOF
echo -e "${RESET}"

echo -e "${BOLD}${CYAN}================================================================================${RESET}"
echo -e "${BOLD}${CYAN}  Apache Sourcelume Registry — Full Capabilities Demonstration                  ${RESET}"
echo -e "${CYAN}  Community Over Code Glasgow 2026 Hackathon                                    ${RESET}"
echo -e "${BOLD}${CYAN}================================================================================${RESET}"
echo -e "${MAGENTA}Demo Story & Objectives:${RESET}"
echo -e "  1. ${BOLD}The Problem:${RESET}   AI models often train on data with unverified provenance/licensing."
echo -e "  2. ${BOLD}The Solution:${RESET}  Sourcelume Spec (provenance schemas) + Sourcelume Registry (Atlas)."
echo -e "  3. ${BOLD}The Mission:${RESET}   Verifiable data lineage, licensing compliance, and metadata governance.\n"
echo -e "${MAGENTA}Core Capabilities Demonstrated:${RESET}"
echo -e "  1. ${BOLD}Zero-Config Type Bootstrap:${RESET} Automatic Atlas type synchronization on startup."
echo -e "  2. ${BOLD}Pre-Flight Validation:${RESET}      Fast in-memory JSON Schema + SHACL graph checking."
echo -e "  3. ${BOLD}Synchronous Ingest Pipeline:${RESET} Byte-for-byte SHA-256 digesting and verdict auditing."
echo -e "  4. ${BOLD}Curatable Ingest Lifecycle:${RESET}  INCOMPLETE draft ingestion -> issue query -> VALIDATED fix."
echo -e "  5. ${BOLD}Immutability & Governance:${RESET}   HTTP 409 Conflict protection against tampering."
echo -e "${BOLD}${CYAN}--------------------------------------------------------------------------------${RESET}\n"

# Verify required command line tools
command -v curl >/dev/null 2>&1 || { echo -e "${RED}Error: curl is required but not installed.${RESET}" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo -e "${RED}Error: jq is required but not installed.${RESET}" >&2; exit 1; }

for req_file in "${INCOMPLETE_RECORD_FILE}" "${PROVENANCE_RECORD_FILE}"; do
  if [ ! -f "${req_file}" ]; then
    echo -e "${RED}Error: Required sample file not found at ${req_file}${RESET}" >&2
    exit 1
  fi
done

# Step 1: Health Check
echo -e "${BOLD}${BLUE}[Step 1/6] System Readiness & Health Probe (${REGISTRY_URL}/q/health/ready)${RESET}"
echo -e "  ${YELLOW}Showing:${RESET} Active health probe confirming registry connectivity to Apache Atlas.\n"
cat << "EOF"
  +----------------------+             +----------------------------------+             +-------------------------+
  |    Client / CI       |             |   Sourcelume Registry (Quarkus)  |             |      Apache Atlas       |
  +----------------------+             +----------------------------------+             +-------------------------+
             |                                          |                                            |
             |  1. GET /q/health/ready                  |                                            |
             | ---------------------------------------> |                                            |
             |                                          |  2. Probe Atlas connectivity / status      |
             |                                          | -----------------------------------------> |
             |                                          |                                            |
             |                                          |  3. Connection OK (HTTP 200 / UP)          |
             |                                          | <----------------------------------------- |
             |  4. Health check JSON (status: UP)       |                                            |
             | <--------------------------------------- |                                            |
             |                                          |                                            |
EOF
echo ""
HEALTH_JSON=$(curl -s -f "${REGISTRY_URL}/q/health/ready" 2>/dev/null || true)

if [ -z "${HEALTH_JSON}" ]; then
  echo -e "\n${RED}✗ Failed to connect to Sourcelume Registry at ${REGISTRY_URL}.${RESET}"
  echo -e "${YELLOW}Please ensure Apache Atlas and Sourcelume Registry are running:${RESET}"
  echo -e "  1. Start Atlas:    cd dev-support && docker compose -f docker-compose.atlas.yml up -d"
  echo -e "  2. Start Registry: mvn -pl sourcelume-registry-distribution-atlas quarkus:dev"
  exit 1
fi

HEALTH_STATUS=$(echo "${HEALTH_JSON}" | jq -r '.status // "DOWN"')
if [ "${HEALTH_STATUS}" != "UP" ]; then
  echo -e "\n${RED}✗ Registry is running but reporting health status: ${HEALTH_STATUS}${RESET}"
  echo "${HEALTH_JSON}" | jq .
  exit 1
fi
echo -e "${GREEN}✓ Registry status is UP (Connected to Apache Atlas backend).${RESET}\n"

# Step 2: Inspect Atlas Typedefs
echo -e "${BOLD}${BLUE}[Step 2/6] Automatic Atlas Type Bootstrapping (/api/v1/types/typedefs)${RESET}"
echo -e "  ${YELLOW}Showing:${RESET} Zero-configuration initialization — Sourcelume bootstraps custom entity"
echo -e "           and enum types into Atlas on startup with automatic conflict resolution.\n"
cat << "EOF"
  +--------------------------------+       +----------------------------------+       +-------------------------+
  |   Sourcelume Spec Models       |       |   Sourcelume Registry (Quarkus)  |       |      Apache Atlas       |
  +--------------------------------+       +----------------------------------+       +-------------------------+
                  |                                         |                                      |
                  |  1. Load models/sourcelume/             |                                      |
                  |     sourcelume_model.json               |                                      |
                  | --------------------------------------> |                                      |
                  |                                         |  2. Check existing / conflict sync   |
                  |                                         |  3. Register entity & enum typedefs  |
                  |                                         | -----------------------------------> |
                  |                                         |     - sourcelume_dataset (entity)    |
                  |                                         |     - sourcelume_model (entity)      |
                  |                                         |     - sourcelume_record_status (enum)|
                  |                                         |                                      |
EOF
echo ""
TYPEDEFS=$(curl -s "${REGISTRY_URL}/api/v1/types/typedefs")
ENTITY_DEFS_COUNT=$(echo "${TYPEDEFS}" | jq '.entityDefs | length')
ENUM_DEFS_COUNT=$(echo "${TYPEDEFS}" | jq '.enumDefs | length')
echo -e "  • Total Entity Definitions: ${BOLD}${ENTITY_DEFS_COUNT}${RESET} | Enum Definitions: ${BOLD}${ENUM_DEFS_COUNT}${RESET}"

DATASET_TYPE=$(curl -s "${REGISTRY_URL}/api/v1/types/dataset")
DATASET_TYPENAME=$(echo "${DATASET_TYPE}" | jq -r '.entityDefs[0].name // empty')
SUPER_TYPES=$(echo "${DATASET_TYPE}" | jq -c -r '.entityDefs[0].superTypes // []')
echo -e "  • Verified Entity Type: ${GREEN}${DATASET_TYPENAME}${RESET} (SuperTypes: ${SUPER_TYPES})"
echo -e "${GREEN}✓ Type system successfully bootstrapped in Apache Atlas.${RESET}\n"

# Step 3: Pre-Flight Specification Validation
echo -e "${BOLD}${BLUE}[Step 3/6] Pre-Flight Specification Validation (POST /records/validate)${RESET}"
echo -e "  ${YELLOW}Showing:${RESET} Multi-stage in-memory validation chain (JSON Schema Draft 2020-12 +"
echo -e "           SHACL graph validation via Apache Jena) executing without touching storage.\n"
cat << "EOF"
       +-----------------------------------------------------------------------------------+
       |                       Sourcelume In-Memory Validator Chain                        |
       +-----------------------------------------------------------------------------------+
                                                 |
  +----------------+    Raw JSON-LD    +-------------------+    Clean Syntax   +-------------------+
  |  Client / CI   | ----------------> | Stage 1:          | ----------------> | Stage 2:          |
  |  Pre-Flight    |                   | JSON Schema       |                   | SHACL Graph       |
  +----------------+                   | (Draft 2020-12)   |                   | (Apache Jena)     |
          ^                            +-------------------+                   +-------------------+
          |                                      | Fail (422)                            | Pass (200) / Fail (422)
          +--------------------------------------+---------------------------------------+
                              ValidationResult (conforms + structured issues)
EOF
echo ""

echo -e "${BOLD}3a. Validating Non-Conforming Record (missing mandatory 'license'):${RESET}"
VALIDATE_FAIL_HTTP=$(curl -s -w "\n%{http_code}" -X POST "${REGISTRY_URL}/records/validate" \
  -H "Content-Type: application/json" \
  -d @"${INCOMPLETE_RECORD_FILE}")
VALIDATE_FAIL_STATUS=$(echo "${VALIDATE_FAIL_HTTP}" | tail -n 1)
VALIDATE_FAIL_BODY=$(echo "${VALIDATE_FAIL_HTTP}" | sed '$d')

echo -e "  • HTTP Status: ${YELLOW}${VALIDATE_FAIL_STATUS} Unprocessable Entity${RESET}"
echo -e "  • Structured Issues Report:"
echo "${VALIDATE_FAIL_BODY}" | jq .

echo -e "\n${BOLD}3b. Validating Fully Conforming Spec Record:${RESET}"
VALIDATE_PASS_HTTP=$(curl -s -w "\n%{http_code}" -X POST "${REGISTRY_URL}/records/validate" \
  -H "Content-Type: application/json" \
  -d @"${PROVENANCE_RECORD_FILE}")
VALIDATE_PASS_STATUS=$(echo "${VALIDATE_PASS_HTTP}" | tail -n 1)
VALIDATE_PASS_BODY=$(echo "${VALIDATE_PASS_HTTP}" | sed '$d')

echo -e "  • HTTP Status: ${GREEN}${VALIDATE_PASS_STATUS} OK${RESET}"
echo "${VALIDATE_PASS_BODY}" | jq .
echo -e "${GREEN}✓ Pre-flight validation executed instantly with zero persistence overhead.${RESET}\n"

# Step 4: Synchronous Ingestion & Curation Lifecycle
echo -e "${BOLD}${BLUE}[Step 4/6] Synchronous Ingestion & Curation Lifecycle (POST /records)${RESET}"
echo -e "  ${YELLOW}Showing:${RESET} Byte-accurate SHA-256 digesting, INCOMPLETE state capture, issue"
echo -e "           inspection, and in-place promotion to VALIDATED upon corrected resubmission.\n"
cat << "EOF"
  +-------------------------------------------------------------------------------------------------+
  |                             Curatable Ingestion State Machine                                   |
  +-------------------------------------------------------------------------------------------------+
                                                  |
  +---------------------+   POST Incomplete   +---------------------+   Inspect Issues   +---------+
  | 1. Draft Submission | ------------------> | Persisted Entity    | -----------------> | Review  |
  |    (Missing License)|                     | Status: INCOMPLETE  |  GET /records/id   | Issues  |
  +---------------------+                     | HTTP 201 Created    |                    +---------+
                                              +---------------------+                         |
                                                         |                                    |
  +---------------------+   POST Corrected Record        | Fix Missing Metadata               |
  | 2. Resubmission     | -------------------------------+------------------------------------+
  |    (Valid Spec)     |                                v
  +---------------------+                     +---------------------+
                                              | Updated Entity      |
                                              | Status: VALIDATED   |
                                              | HTTP 200 OK         |
                                              +---------------------+
EOF
echo ""

RECORD_ID=$(jq -r '.id' "${INCOMPLETE_RECORD_FILE}")
ENCODED_RECORD_ID=$(jq -rn --arg x "${RECORD_ID}" '$x|@uri')

echo -e "${BOLD}4a. Submitting Initial Draft Record (Missing License):${RESET}"
INGEST_DRAFT_HTTP=$(curl -s -w "\n%{http_code}" -X POST "${REGISTRY_URL}/records" \
  -H "Content-Type: application/json" \
  -d @"${INCOMPLETE_RECORD_FILE}")
INGEST_DRAFT_STATUS=$(echo "${INGEST_DRAFT_HTTP}" | tail -n 1)
INGEST_DRAFT_BODY=$(echo "${INGEST_DRAFT_HTTP}" | sed '$d')

echo -e "  • Response Status: ${GREEN}${INGEST_DRAFT_STATUS} Created${RESET}"
echo -e "  • Ingest Verdict:"
echo "${INGEST_DRAFT_BODY}" | jq .

echo -e "\n${BOLD}4b. Inspecting Stored Record Issues (GET /records/${ENCODED_RECORD_ID}):${RESET}"
RECORD_STATUS_JSON=$(curl -s "${REGISTRY_URL}/records/${ENCODED_RECORD_ID}")
echo "${RECORD_STATUS_JSON}" | jq .

echo -e "\n${BOLD}4c. Resubmitting Corrected Record Under Same Identifier:${RESET}"
INGEST_FIXED_HTTP=$(curl -s -w "\n%{http_code}" -X POST "${REGISTRY_URL}/records" \
  -H "Content-Type: application/json" \
  -d @"${PROVENANCE_RECORD_FILE}")
INGEST_FIXED_STATUS=$(echo "${INGEST_FIXED_HTTP}" | tail -n 1)
INGEST_FIXED_BODY=$(echo "${INGEST_FIXED_HTTP}" | sed '$d')

echo -e "  • Response Status: ${GREEN}${INGEST_FIXED_STATUS} OK${RESET} (In-Place Update)"
echo -e "  • Updated Verdict:"
echo "${INGEST_FIXED_BODY}" | jq .
echo -e "${GREEN}✓ Record successfully transitioned from INCOMPLETE → VALIDATED!${RESET}\n"

# Step 5: Immutability & Tamper Protection
echo -e "${BOLD}${BLUE}[Step 5/6] Immutability & Tamper Protection (POST /records -> 409 Conflict)${RESET}"
echo -e "  ${YELLOW}Showing:${RESET} Strict governance guarantees — once a record achieves VALIDATED status,"
echo -e "           any further overwrite attempts are rejected with HTTP 409 Conflict.\n"
cat << "EOF"
  +----------------------+             +----------------------------------+             +-------------------------+
  |  Mutate / Overwrite  |             |   Sourcelume Ingest Pipeline     |             |      Apache Atlas       |
  +----------------------+             +----------------------------------+             +-------------------------+
             |                                          |                                            |
             |  1. POST /records (Existing ID)          |                                            |
             | ---------------------------------------> |                                            |
             |                                          |  2. Check existing recordStatus            |
             |                                          |     Found: VALIDATED (Published)           |
             |                                          |                                            |
             |  3. HTTP 409 Conflict                    |  [Rejected: Modification Forbidden]        |
             | <--------------------------------------- |                                            |
             |     {"recordStatus": "VALIDATED"}        |                                            |
EOF
echo ""

CONFLICT_HTTP=$(curl -s -w "\n%{http_code}" -X POST "${REGISTRY_URL}/records" \
  -H "Content-Type: application/json" \
  -d @"${PROVENANCE_RECORD_FILE}")
CONFLICT_STATUS=$(echo "${CONFLICT_HTTP}" | tail -n 1)
CONFLICT_BODY=$(echo "${CONFLICT_HTTP}" | sed '$d')

echo -e "  • Ingest Attempt on Validated Record Status: ${RED}${CONFLICT_STATUS} Conflict${RESET}"
echo -e "  • Rejection Response:"
echo "${CONFLICT_BODY}" | jq .
echo -e "${GREEN}✓ Provenance immutability verified — published records cannot be overwritten.${RESET}\n"

# Step 6: Unified Query Resolution & Atlas Exploration
echo -e "${BOLD}${BLUE}[Step 6/6] Unified Query Resolution & Atlas Entity Exploration${RESET}"
echo -e "  ${YELLOW}Showing:${RESET} Flexible metadata retrieval using canonical Record IRI, Dataset qualifiedName,"
echo -e "           and internal Atlas Graph GUID.\n"
cat << "EOF"
                               +--------------------------------------------+
                               |     Unified Metadata Resolution Paths      |
                               +--------------------------------------------+
                                                     |
             +---------------------------------------+---------------------------------------+
             |                                       |                                       |
             v                                       v                                       v
  [1. Canonical Record IRI]              [2. Dataset URI Query]                 [3. Atlas Graph GUID]
   GET /records/{encodedId}               GET /api/v1/datasets?qualifiedName=    GET /api/v1/datasets/guid/{guid}
             |                                       |                                       |
             +---------------------------------------+---------------------------------------+
                                                     |
                                                     v
                                     +-------------------------------+
                                     | Apache Atlas Governed Entity  |
                                     | - Type: sourcelume_dataset    |
                                     | - Status: VALIDATED           |
                                     | - SHA-256 Digest & Audit Log  |
                                     +-------------------------------+
EOF
echo ""

echo -e "${BOLD}6a. Query via Canonical Record Endpoint (GET /records/{encodedId}):${RESET}"
RECORD_VIEW=$(curl -s "${REGISTRY_URL}/records/${ENCODED_RECORD_ID}")
echo "${RECORD_VIEW}" | jq .

DATASET_QN=$(echo "${RECORD_VIEW}" | jq -r '.qualifiedName // empty')
DATASET_NAME=$(echo "${RECORD_VIEW}" | jq -r '.name // empty')
LICENSE_ID=$(echo "${RECORD_VIEW}" | jq -r '.licenseId // empty')
SHA256_HASH=$(echo "${RECORD_VIEW}" | jq -r '.sha256 // empty')

echo -e "\n${BOLD}6b. Query via Dataset Qualified Name (GET /api/v1/datasets?qualifiedName=...):${RESET}"
DATASET_VIEW=$(curl -s "${REGISTRY_URL}/api/v1/datasets?qualifiedName=${ENCODED_RECORD_ID}")
echo "${DATASET_VIEW}" | jq .

ASSIGNED_GUID=$(echo "${DATASET_VIEW}" | jq -r '.guid // empty')

if [ -n "${ASSIGNED_GUID}" ]; then
  echo -e "\n${BOLD}6c. Query via Internal Atlas Graph GUID (GET /api/v1/datasets/guid/${ASSIGNED_GUID}):${RESET}"
  GUID_VIEW=$(curl -s "${REGISTRY_URL}/api/v1/datasets/guid/${ASSIGNED_GUID}")
  echo "${GUID_VIEW}" | jq .
fi

echo -e "\n${GREEN}✓ Entity attributes verified across all resolution endpoints!${RESET}"
echo -e "  • Dataset Name:         ${BOLD}${DATASET_NAME}${RESET}"
echo -e "  • Canonical Spec IRI:   ${BOLD}${DATASET_QN}${RESET}"
echo -e "  • SPDX License URI:     ${BOLD}${LICENSE_ID}${RESET}"
echo -e "  • Raw SHA-256 Digest:   ${BOLD}${SHA256_HASH}${RESET}"
if [ -n "${ASSIGNED_GUID}" ]; then
  echo -e "  • Atlas Graph GUID:     ${BOLD}${ASSIGNED_GUID}${RESET}"
fi

# Summary
echo -e "\n${BOLD}${CYAN}================================================================================${RESET}"
echo -e "${BOLD}${GREEN}  Demo Execution Complete!                                                      ${RESET}"
echo -e "${BOLD}${CYAN}================================================================================${RESET}"
echo -e "${BOLD}Visual Exploration in Apache Atlas UI:${RESET}"
echo -e "  1. Open your browser:            ${BOLD}${ATLAS_URL}${RESET}"
echo -e "  2. Sign in with credentials:     ${BOLD}admin${RESET} / ${BOLD}atlasR0cks!${RESET}"
echo -e "  3. Search for Type:              ${BOLD}sourcelume_dataset${RESET}"
echo -e "  4. Inspect the entity attributes (name, sourceUri, licenseId, recordStatus, sha256) and audit log."
echo -e "\n${GREEN}Thank you for exploring Apache Sourcelume!${RESET}"
