<!--
    Licensed to the Apache Software Foundation (ASF) under one or more
    contributor license agreements.  See the NOTICE file distributed with
    this work for additional information regarding copyright ownership.
    The ASF licenses this file to You under the Apache License, Version 2.0
    (the "License"); you may not use this file except in compliance with
    the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
-->

# Performance benchmark: `GET /records/{id}` reference resolution

Reproduces the workload measured in
[apache/sourcelume-registry#24](https://github.com/apache/sourcelume-registry/issues/24):
model records whose `trainingData` lists reference a growing number of
dataset records, and what resolving those references costs on the read
path.

The scripts talk only to the public record API (`POST /records`,
`GET /records/{id}`), so they measure whichever storage plugin the
registry runs — the default PostgreSQL distribution or the Atlas one —
and the numbers stay comparable across backends.

## Prerequisites

- a running registry — the
  [PostgreSQL distribution](../README.md#postgresql-default-distribution)
  or the
  [Atlas stack](../README.md#quick-start-standing-up-the-atlas-backend)
- `curl` and `jq`
- a POSIX shell — on Windows run from Git Bash or WSL2, like the rest of
  dev-support
- a disposable registry: seeded records accumulate, see *Notes*

## Usage

```bash
./dev-support/perf/seed.sh     # seeds 161 datasets + models sized 0/12/40/161 refs
./dev-support/perf/bench.sh    # n=30 timed reads per model record
```

`bench.sh` picks the newest manifest in `out/`; pass an explicit path to
measure an older seed. Example output (Atlas distribution, PostgreSQL
backend):

```
record                                                        n       min    median       max  refs
perf-20261113T103000Z-model-refs0                            30     301ms     333ms     402ms     0
perf-20261113T103000Z-model-refs12                           30     355ms     391ms     460ms    12
perf-20261113T103000Z-model-refs40                           30     412ms     448ms     511ms    40
```

## Knobs

| variable | default | meaning |
|---|---|---|
| `REGISTRY_URL` | `http://localhost:8082` | registry base URL — same API port on both distributions |
| `PERF_PREFIX` | `perf-<utc timestamp>` | record id prefix |
| `PERF_DATASETS` | `161` | dataset records seeded |
| `PERF_REFS` | `0 12 40 161` | `trainingData` sizes, one model record each |
| `PERF_ITERS` | `30` | timed reads per record |
| `PERF_WARMUP` | `2` | untimed reads before timing |
| `PERF_OUT` | `./out` (git-ignored) | manifest directory |

## Notes

- Every record id carries `PERF_PREFIX`, so each run writes new records
  rather than colliding with earlier seeds (`POST /records` returns `409`
  for a VALIDATED id). Point the scripts at a disposable registry if you
  run them repeatedly.
- `seed.sh` posts one record at a time on purpose: ingest is synchronous
  and the dataset writes are not what this benchmark measures.
- To compare backends, seed and time each distribution with the same
  `PERF_*` values; medians land in the same units.
