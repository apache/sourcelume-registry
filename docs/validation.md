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

# Record Validation

This document describes the record validation architecture of the Apache
Sourcelume Registry: the `RecordValidator` SPI in the core, the shipped
validation plugins, and the design decisions behind them.

The goal is Java-side parity with the **Python reference validator**
(`sourcelume-spec` `tools/validate.py`, jsonschema[format] + pyshacl), so
records can be validated at ingest time inside the JVM — while the spec
artifacts (JSON Schema, SHACL shapes, JSON-LD context) remain the single
source of truth, packaged in the `sourcelume-spec` artifact.

## Plugin architecture

Validation is a **chain of plugins**, not a built-in feature:

- The **core** (`sourcelume-registry-core`) defines only the contract:
  `RecordValidator` (stable `id()`, execution `order()`, and
  `validate(String jsonLd)` that reports failures as results, never as
  exceptions), the immutable `ValidationResult`/`ValidationIssue` types, and
  the `ValidatorChain`.
- Concrete validators live in **separate plugin modules**, each owning its
  validation machinery. They register themselves through the JDK
  `ServiceLoader` (`META-INF/services`); the chain discovers whatever is on
  the classpath.

Chain semantics (mirroring `validate.py`, which runs the JSON Schema stage
before SHACL and only invokes the latter when the former passes):

- Validators run by ascending `order()`.
- The chain is **fail-fast**: the first non-conforming validator ends the
  chain; its issues (plus everything collected so far) are returned.
- An **empty chain is rejected** (`IllegalStateException`): a chain that
  validates nothing would silently accept every document.
- **Duplicate ids are rejected** (`IllegalStateException`): two plugins
  claiming the same stage is a deployment error. Swapping a plugin means
  swapping the jar, not running both.

Validators take the **raw JSON-LD string**, not a DTO: the JSON-LD semantics
(term-to-IRI expansion) must be preserved, and validation must be possible
before any DTO mapping happens.

### Shipped plugins

| Module | Stage id | Order | Machinery | Weight |
|---|---|---|---|---|
| `sourcelume-registry-validation-jsonschema` | `json-schema` | 100 | networknt json-schema-validator 2.x (draft 2020-12) | light |
| `sourcelume-registry-validation-shacl` | `shacl` | 200 | Apache Jena SHACL 6.x (JSON-LD via Titanium) | ~18 MB |

Deployments pick the stages they want by adding or removing plugin jars;
the discovery mechanism does the rest. The core stays free of both
networknt and Jena dependencies.

### Usage

```java
ValidatorChain chain = ValidatorChain.discover();
ValidationResult result = chain.validate(jsonLd);
if (!result.conforms()) {
    result.issues().forEach(issue ->
        System.out.println(issue.severity() + " " + issue.path() + ": " + issue.message()));
}
```

The "no ingest without validation" policy is deliberately not part of this
change: it belongs to the future `IngestService` in the core, which will
enforce it on top of the chain.

## Why two stages? The alias gap

The two stages overlap by roughly 90%: required properties, string types,
cardinality. One class of errors, however, is invisible to JSON Schema:

> The sourcelume schema allows additional properties (a deliberate 0.0.1
> decision). Term aliases therefore cannot be constrained at the JSON level.

A record can claim an Apache-2.0 license under `"license"` *and* a GPL-3.0
license under the full IRI `"http://purl.org/dc/terms/license"`. The JSON
Schema passes (`additionalProperties` is unconstrained). After JSON-LD
expansion both claims map to `dct:license` on the same node — and the SHACL
shapes reject it (`maxCount 1` and `nodeKind sh:IRI`).

Both stages together see what the registry actually stores and queries: the
JSON as submitted, and the RDF graph after expansion. The shared fixtures
were run against the Python oracle (`tools/validate.py`) during development
with matching verdicts; keeping that parity honest is exactly why the
fixtures live next to the plugin tests. Each plugin module deliberately
carries its own copies of the shared fixtures (module-local test resources,
no cross-module test coupling) — when a fixture changes, update both
copies and re-run the oracle.

## SHACL plugin: design decisions and findings

### Offline context policy (SSRF)

Documents reference the JSON-LD context by IRI; the spec currently defines
no canonical absolute context IRI, and the spec examples use a relative path
(`../context/0.0.1/sourcelume.jsonld`). Fetching whatever IRI an *ingested*
document names is not acceptable:

- validation would depend on network access, and
- ingest payloads would direct the validator at arbitrary hosts (SSRF).

The `JsonLdContextInliner` therefore replaces every reference ending in the
bundled context resource path with the context shipped in the `sourcelume-spec`
artifact and **refuses documents with any other string reference**. Inline
context objects are passed through, but scanned recursively for every
construct that would make the RDF parser retrieve a remote document —
`@import` and term-scoped context references are refused, so the offline
guarantee is structural (a JSON-LD processor only ever retrieves documents
for exactly those constructs). The number of replacements is capped: a
document made of repeated context references cannot amplify into the
serialized form and exhaust heap. Documents without any `@context` at all,
or with a `@context` that is neither a string, an array, nor an object, are
refused as well: their terms would not expand, the graph would be empty of
sourcelume types, and an empty graph trivially *conforms* to the shapes —
silently accepting garbage would be worse than rejecting the document.

The same trivial-conformance concern applies to context *games*: an inline
context can redefine the `type` term mapping (or be simply empty), in which
case the document expands to no `sl:ProvenanceRecord` node and the shapes
would conform vacuously. The plugin therefore refuses any graph that,
after expansion, contains no node typed `sl:ProvenanceRecord` — validation
must never succeed by dropping the focus node.

### Format assertions (parity with jsonschema[format])

Since JSON Schema draft 2019-09, `format` only annotates by default. The
Python reference validator enforces `date-time`/`uri` via `jsonschema[format]`
(rfc3339-validator, rfc3987), so the `json-schema` plugin explicitly enables
networknt's format assertions and pins the message locale to `Locale.ROOT`
for deterministic output. Bad timestamps and malformed URIs are violations,
not annotations.

### Apache Jena as the SHACL engine

SHACL validates RDF graphs, not JSON trees: the document must be expanded to
RDF first (JSON-LD → RDF via Titanium in Jena 6.x), which is why a SHACL stage
cannot be built from a JSON library alone — the same reason the Python side
pairs pyshacl with rdflib. Jena executes the spec's `sourcelume.shacl.ttl`
directly (SHACL Core only: `targetClass`, `datatype`, `min/maxCount`,
`nodeKind`, `sh:in`), keeping the shapes file the single source of truth for
both implementations.

### ASF licensing note (category B)

`jena-shacl` transitively depends on `org.glassfish:jakarta.json`
(EPL-2.0 / GPL-2.0 with Classpath Exception), an ASF **category B**
dependency. That is acceptable as a binary dependency, but it must be
reflected in LICENSE/NOTICE when this plugin is bundled in a distribution.
All other Jena transitives are category A (Apache/MIT/BSD).

### Known spec follow-ups

These are open questions for `dev@sourcelume.apache.org`, not blockers:

- **Canonical absolute context IRI**: the spec should define one, so
  documents (and the inliner's suffix matching) can reference it explicitly.
- **SHACL normativity**: the spec prose describes RDF-level validation but
  does not yet state normatively that SHACL shapes must pass. The registry
  treats them as normative for ingest.

## License

Apache License, Version 2.0 — see the repository root.
