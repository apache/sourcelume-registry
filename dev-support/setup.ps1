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

# PowerShell port of setup.sh for native Windows development. Vendors Apache
# Atlas's own dev-support/atlas-docker tooling at a pinned git ref — see
# setup.sh for the rationale. ATLAS_REF pins the Atlas version the runtime is
# tested against (2.5.0).

$ErrorActionPreference = "Stop"

$AtlasRef = if ($env:ATLAS_REF) { $env:ATLAS_REF } else { "release-2.5.0" }
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$VendorDir = Join-Path $ScriptDir "vendor\atlas-docker"

Write-Host "=== Sourcelume Dev Support Setup ==="

# 1. Ensure shared Docker network exists
docker network inspect sourcelume-network *> $null
if ($LASTEXITCODE -ne 0) {
  Write-Host "Creating shared docker network 'sourcelume-network'..."
  docker network create sourcelume-network | Out-Null
} else {
  Write-Host "Docker network 'sourcelume-network' already exists."
}

# 2. Vendor Atlas docker tooling
if (Test-Path $VendorDir) {
  Write-Host "Atlas docker tooling already vendored at $VendorDir."
  Write-Host "Remove that directory first if you want to re-vendor a different ref."
} else {
  Write-Host "Vendoring apache/atlas@${AtlasRef}:dev-support/atlas-docker ..."
  $TmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ([System.IO.Path]::GetRandomFileName())
  New-Item -ItemType Directory -Path $TmpDir | Out-Null
  try {
    # -c core.autocrlf=false keeps LF endings in the vendored checkout; Git for
    # Windows' default (autocrlf=true) would produce CRLF, breaking the
    # vendored bash scripts under Git Bash/WSL.
    git clone -c core.autocrlf=false --depth 1 --branch $AtlasRef https://github.com/apache/atlas.git $TmpDir
    if ($LASTEXITCODE -ne 0) { throw "git clone apache/atlas failed" }
    New-Item -ItemType Directory -Path (Split-Path -Parent $VendorDir) -Force | Out-Null
    Copy-Item -Recurse (Join-Path $TmpDir "dev-support\atlas-docker") $VendorDir
    Write-Host "Vendored to $VendorDir."
  } finally {
    Remove-Item -Recurse -Force $TmpDir -ErrorAction SilentlyContinue
  }
}

# 3. Configure vendored .env defaults for Sourcelume local dev
$EnvFile = Join-Path $VendorDir ".env"
if (Test-Path $EnvFile) {
  Write-Host "Configuring $EnvFile defaults (BUILD_HOST_SRC=false, BRANCH=$AtlasRef, ATLAS_BACKEND=postgres)..."
  $content = Get-Content $EnvFile -Raw
  $content = $content -replace '(?m)^BUILD_HOST_SRC=.*', 'BUILD_HOST_SRC=false'
  $content = $content -replace "(?m)^BRANCH=.*", "BRANCH=$AtlasRef"
  $content = $content -replace '(?m)^ATLAS_BACKEND=.*', 'ATLAS_BACKEND=postgres'
  Set-Content -Path $EnvFile -Value $content -NoNewline
}

Write-Host ""
Write-Host "Setup complete! See dev-support/README.md for build, start, and testing instructions."
