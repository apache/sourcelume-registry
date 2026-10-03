# Record Ingest

This document describes the ingest architecture of the Apache Sourcelume
Registry: how a provenance record enters the registry, what happens to it,
and the state model behind it. Record *validation* itself is described in
[validation.md](validation.md); ingest is the pipeline that puts
validation to work.

The core guarantee:

> **No record is ACTIVE without validation.**

Consumers of the registry can therefore treat `ACTIVE` records as
"validated by the operator's configured validator chain" without
re-validating anything themselves.

## The flow

Submitting a record and validating it are two separate, asynchronous
steps:

1. A producer submits a record to the registry REST API
   (`POST /records`). The registry stores the record **byte-identical**
   as a `PENDING` entity — keyed by the record IRI (`id` in the JSON-LD)
   — and answers immediately with `202 Accepted`. No validation happens
   on the write path.
2. The **ingest worker** (`sourcelume-registry-ingest-worker`) polls
   the store for `PENDING` records, runs the configured `RecordValidator`
   chain on the stored raw JSON-LD, and promotes each record:
   conforms → `ACTIVE` (with the mapped provenance attributes), does not
   conform → `INCOMPLETE` (with the validation issues stored as JSON).
3. Consumers read the record's state via `GET /records/{id}`. Records that
   came back `INCOMPLETE` can be corrected and **resubmitted** — they
   return to `PENDING` and go through the pipeline again.

There is no queue and no extra database: **Apache Atlas is the single
persistent state source**. A worker that picks up a record but fails
mid-pipeline leaves the record `PENDING`; the next poll simply finds it
again. This is deliberate: one state store, no dual-write consistency
problems, and the poll interval is the only operational knob.

## The record state model

Every dataset entity carries a `recordStatus` attribute with exactly one
of three values:

```
                 validate: conforms
    PENDING ─────────────────────────────► ACTIVE
       ▲  │
       │  │ validate: does not conform
       │  ▼
       │  INCOMPLETE ──(operator corrects,
       │                 resubmits)──► back to POST /records
       │
       └── resubmission of an INCOMPLETE record
```

| Status | Meaning | Who sets it |
|---|---|---|
| `PENDING` | stored, not yet validated | `POST /records` |
| `ACTIVE` | validated by the configured chain; mapped attributes written | worker |
| `INCOMPLETE` | validation failed; issues stored on the entity | worker |

`INCOMPLETE` is deliberately **non-terminal and curatable**: records are
data, and data gets fixed. Nothing is ever silently dropped or silently
accepted. The guarantee is about `ACTIVE` alone — consumers filter for
`ACTIVE` and know every such record passed the chain.

The status lives on the Sourcelume entity type, not on Atlas's own
`ACTIVE`/`DELETED` flags: those describe *entity existence* in Atlas
(soft-delete), while `recordStatus` describes the *ingest lifecycle*.
Keeping them separate keeps the record status model portable across
backends — the `AtlasAdapter` SPI stays vendor-neutral.

## REST semantics

| Request | Result |
|---|---|
| `POST /records` (new record id) | `202 Accepted` + `Location: /records/{id}`; stored as `PENDING` |
| `POST /records` (record already `PENDING` or `ACTIVE`) | `409 Conflict` (the record is in the way) |
| `POST /records` (record is `INCOMPLETE`) | `202 Accepted` — corrected resubmission, back to `PENDING`, stored issues cleared |
| `POST /records` (no usable `id` in the document) | `400 Bad Request` |
| `POST /records` (content type neither JSON nor `application/ld+json`) | `415 Unsupported Media Type` |
| `GET /records/{id}` | `200` with status, issues, mapped attributes — or `404` |

The id used for keying (and the `qualifiedName` of the Atlas entity) is
the record's own `id` IRI, byte-identical from the submitted document.

## Why asynchronous?

Validation of a single record is fast, but the registry's contract is
not "validate this document" (it already offers exactly that as a
pre-flight check via `POST /records/validate`, see validation.md) — it
is "**keep** a validated record, verifiably". Curation takes rounds:
records fail validation, get corrected, get resubmitted. Making the write
path synchronous would turn every submit into a validation wait, and
every re-validation into a new API. With a stored status, "validate this
record again" is simply "resubmit it" — the same code path as the first
submission.

## Operating the worker

The worker is a headless Quarkus application; see `dev-support/README.md`
for the compose setup. The loop is configured via:

| Property | Default | Meaning |
|---|---|---|
| `sourcelume.ingest.poll-interval` | `5s` | how often to poll for `PENDING` records |
| `sourcelume.ingest.batch-size` | `20` | how many records to pick up per tick |

A tick never runs concurrently with itself (`concurrentExecution = SKIP`).
A failing record is logged and retried on the next tick; it never blocks
the rest of the batch. The worker refuses to start without validator
plugins on the classpath (fail-on-start, same semantics as the REST
runtime): a worker that silently promoted unvalidated records would
defeat the registry's core guarantee.

## Design notes (deferred)

- **Composition** (dataset A was curated from dataset B) is out of scope
  here; it is a spec-level question (0.2+), expected to be modeled as
  relationships plus query-time joins — never as stored aggregate state.
- **Claiming/locking** for multiple workers against the same store is
  deferred: with a single worker per registry the poll loop is
  race-free; concurrent workers may pick up the same `PENDING` record,
  which is idempotent (same validation, same promotion).
- A **re-validation endpoint** (validate the stored `rawJsonLd` against
  a newer spec version) is a straightforward extension once the spec
  grows versions; the byte-identical storage makes it possible at all.
