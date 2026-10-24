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

# Contributing to Apache Sourcelume Registry

Thank you for helping improve Apache Sourcelume Registry. Contributions of all
kinds are welcome, including code, tests, documentation, bug reports, design
ideas, and help for other community members.

Apache projects are built by their communities. You do not need to be a
committer to participate, propose a change, review a pull request, or help
shape the project.

## Code of Conduct

All participants must follow the
[Apache Software Foundation Code of Conduct](https://www.apache.org/foundation/policies/conduct.html).
Be respectful, assume good intent, and keep technical discussions constructive.

## Communicating with the community

The public developer mailing list is the primary place for project discussions
and decisions:

- Email: `dev@sourcelume.apache.org`
- Subscribe: send an empty email to
  [dev-subscribe@sourcelume.apache.org](mailto:dev-subscribe@sourcelume.apache.org)
- Archives: [lists.apache.org](https://lists.apache.org/list.html?dev@sourcelume.apache.org)

Use the mailing list before investing substantial effort in a large feature,
an architectural change, a new module or dependency, or a change to a public
API. This gives the community an opportunity to agree on the direction and
helps avoid duplicated work. Small fixes and documentation improvements can
usually go directly to a pull request.

The `#sourcelume` channel on ASF Slack is useful for informal conversation, but
decisions should be summarized on the developer mailing list so that they are
publicly archived and accessible to everyone.

## Reporting bugs

Describe bugs on the developer mailing list with enough information for
someone else to reproduce the problem. Include, where relevant:

- The Sourcelume Registry revision or version.
- Your operating system, JDK, Maven, Docker, and Apache Atlas versions.
- A minimal input or sequence of steps that demonstrates the problem.
- What you expected and what happened instead.
- Relevant logs, stack traces, or failing test output, with secrets removed.

Search the mailing-list archives and existing pull requests first to avoid
duplicate reports.

## Reporting security vulnerabilities

Do not disclose a suspected vulnerability in a public mailing list, pull
request, or other public forum. Report it privately to
[`security@apache.org`](mailto:security@apache.org) and follow the
[ASF security guidance](https://www.apache.org/security/).

## Development setup

### Prerequisites

- Git.
- JDK 21.
- Maven 3.9 or newer.
- Docker and Docker Compose only for container-based development and
  integration tests.

The registry currently depends on the `sourcelume-spec` snapshot artifact,
which is not published to a Maven repository. Clone and install the spec before
building the registry:

```bash
git clone https://github.com/apache/sourcelume-spec.git
(cd sourcelume-spec && mvn clean install)
```

Fork the registry on GitHub, clone your fork, and retain the ASF repository as
`upstream`:

```bash
git clone https://github.com/<your-account>/sourcelume-registry.git
cd sourcelume-registry
git remote add upstream https://github.com/apache/sourcelume-registry.git
git fetch upstream
```

Place `sourcelume-spec` wherever is convenient; installing it to your local
Maven repository is what makes it available to the registry build.

CI builds the spec revision pinned in `.github/workflows/ci.yml`, not the
spec's `main`. If `OpenApiDocumentTest` fails locally but not in CI, your
installed spec differs from the pinned one: check that revision out and
`mvn install` it again. To move to a newer spec, change the pin in a pull
request and regenerate `docs/openapi.yaml`
(`-Dopenapi.snapshot.update=true`).

The repository layout and runtime commands are described in the
[README](README.md). Instructions for running Apache Atlas locally and for
exercising the Quarkus runtime are in the
[developer-support guide](dev-support/README.md).

## Making a change

Start each contribution from an up-to-date `main` and use a short-lived topic
branch:

```bash
git fetch upstream
git switch main
git rebase upstream/main
git switch -c <type>/<short-description>
```

While working:

- Keep the change focused on one concern. Submit unrelated changes separately.
- Follow the existing module boundaries and code style.
- Add or update tests for behavioral changes.
- Update documentation when behavior, configuration, or public APIs change.
- Avoid adding dependencies unless they are necessary. Discuss significant or
  cross-cutting dependencies on the developer mailing list first.
- Do not include generated files, build output, credentials, personal IDE
  settings, or unrelated formatting changes.

### Formatting and license headers

Spotless owns Java and POM formatting. Apply its fixes with:

```bash
mvn spotless:apply
```

New source files must carry the standard ASF license header when their format
supports comments. Copy the appropriate header style from a nearby file or use
`dev/license-header.txt` for Java sources. Do not add comments to data formats,
such as JSON-LD fixtures, when doing so would make the data invalid.

The build also runs Apache RAT and Checkstyle. Do not add a RAT exclusion to
work around a file that should have a license header.

## Building and testing

Run the same verification command used by continuous integration from the
repository root:

```bash
mvn clean verify
```

This compiles all modules, runs the unit tests, checks formatting and license
headers, and runs Checkstyle. During development, a module and everything it
depends on can be tested with:

```bash
mvn -pl <module-name> -am test
```

Changes to the Atlas integration or the end-to-end ingest path should also run
the opt-in integration tests. These require the vendored Atlas tooling, a
locally built `atlas:latest` image, and a reachable Docker daemon:

```bash
mvn verify -Pintegration-tests -pl sourcelume-registry-runtime-quarkus
```

See the
[integration-test instructions](dev-support/README.md#running-the-integration-tests--pintegration-tests)
for setup and troubleshooting. If a relevant test cannot be run locally,
explain why in the pull request.

## Submitting a pull request

Push your topic branch to your fork and open a pull request against
[`apache/sourcelume-registry`](https://github.com/apache/sourcelume-registry)'s
`main` branch.

A useful pull request:

- Explains the problem and why the proposed change is appropriate.
- Summarizes the implementation and any user-visible effects.
- Links the relevant mailing-list discussion or prior pull request, when one
  exists.
- Lists the commands or manual checks used to verify the change.
- Calls out compatibility concerns, new dependencies, and follow-up work.
- Includes focused commits with clear, imperative commit messages.

Continuous integration runs `mvn -B verify` for every pull request. Resolve
failures before asking for review. Keep the same pull request open when
addressing feedback so that the review history remains available.

All reviews and approvals are welcome. A committer will merge a contribution
after the community has reached consensus and the required checks pass. Please
be patient and respond to review feedback; reviewers and committers are often
contributing in their own time.

## Contribution licensing

This project is licensed under the
[Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).
By submitting a contribution, you agree that it will be licensed under those
terms and confirm that you have the right to submit it. Clearly identify any
material derived from a third party and its license; do not submit material
with licensing terms that are incompatible with the Apache License.

Larger contributions may require an Apache Individual Contributor License
Agreement. Project maintainers will let you know if one is needed. See the
[ASF contributor agreements](https://www.apache.org/licenses/contributor-agreements.html)
for details.

Thank you for contributing and for helping build an open, welcoming Apache
Sourcelume community.
