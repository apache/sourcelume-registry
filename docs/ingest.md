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

# Record Ingest

Submitting a provenance record to the registry validates it synchronously:
`POST /records` runs the validator chain (the same plugins behind the
`/validation` endpoint, see [validation.md](validation.md)), stores the
record in Apache Atlas — byte-identical, keyed by its record IRI — and
answers with the verdict.

## The flow

```
POST /records ──► validate ──► store with verdict ──► answer
                    │                                   │
                    ├─ conforms ──► VALIDATED       201 / 200
                    └─ issues     ─► INCOMPLETE     + issues in the body
```

There is no intermediate state: the record reaches the store only with its
verdict. That is a stronger guarantee than "nothing is published without
validation" — no verdict-less record ever sits in Atlas awaiting judgment.

Curating provenance data usually takes several rounds (a missing license,
an incomplete custody chain, ...), so an invalid record is not rejected
and thrown away. It is stored as `INCOMPLETE` with its issues attached,
the owner fixes the document and resubmits it under the same id, and the
new submission gets a new verdict.

## The record state model

The verdict lives on the entity as the `recordStatus` attribute, an enum
deliberately independent of the backend's own lifecycle (Atlas'
ACTIVE/DELETED/PURGED say whether an entity *exists*; this says what the
validation *concluded*):

- **VALIDATED** — the record conforms to the spec. Consumers should
  filter on this status. The mapped provenance attributes (name,
  licenseId, sourceUri) are written from the record.
- **INCOMPLETE** — the record carries its validation issues and stays
  curatable. A corrected resubmission is validated in place.

Every verdict carries its context: `sha256` over the received request
bytes (the endpoint hashes the body before decoding — "byte for byte"
for real), `validatedBy` (the validator chain, comma-separated ids),
and `validatedAt` (ISO-8601 UTC). Without them a stored verdict loses
its meaning as the spec and the chain evolve.

## REST semantics

| Request | Result |
|---|---|
| `POST /records` (JSON-LD body) | `201` new record, `200` resubmission — body: `qualifiedName`, `recordStatus`, `sha256`, `validatedBy`, `validatedAt`, and for `INCOMPLETE` the `validationIssues` |
| `POST /records` for an id that is already VALIDATED | `409` |
| `POST /records` without a usable `@id`, or unparseable | `400` |
| `GET /records/{id}` | `200` record view (issues for INCOMPLETE) / `404` |

The `id` of the JSON-LD document doubles as the record's qualified name;
the `Location` header of the answers points at the (URL-encoded)
status view.

## Design notes

- **Synchronous on purpose.** Validating a provenance record is fast
  (JSON Schema and SHACL over a KB-sized document), so the simplest
  design wins: no second application, no polling, no race window, no
  poison records — and the issues are in the response instead of a poll
  away. This follows the dev-list discussion on the ingest proposal.
- **The seam for a queue.** `IngestService.process()` is the pipeline;
  the REST resource calls it inline. If a validation step ever actually
  gets slow (attestation over large payloads, say), a queue and a worker
  slot in behind the same SPI without touching the pipeline or the
  status model. Until then, a queue would pay async's price without
  earning it: records are independent (no ordering to preserve),
  volumes are small, the raw document on the entity is the retention,
  and the write hits Atlas either way.
- **409 instead of update.** A published (VALIDATED) record never
  changes — legitimate updates (a new custody event, a corrected
  license) mean a new id linked to the previous record. The linking
  is deferred until the spec covers it; the rejection is deliberate.
- **Known limit:** the 409 check is check-then-write, and Atlas has no
  compare-and-set. Two concurrent submissions for the same id race;
  serialization comes with ownership and auth.

## Testing

Unit and resource tests run against stubs by design; the end-to-end
round runs against a real Apache Atlas via Testcontainers
(`mvn verify -Pintegration-tests`, see dev-support/README.md): submit,
verdict, resubmission cycle, duplicate rejection, and the bootstrap's
idempotency on an existing type.
