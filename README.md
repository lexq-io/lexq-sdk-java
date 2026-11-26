# LexQ Java SDK

The Java client for the [LexQ](https://lexq.io) execution API, to be published on Maven
Central as `io.lexq:lexq-sdk`. It will need Java 17 or later, and it will work from Kotlin
and Scala as well.

**In development. There is nothing to install yet.**

Your backend sends facts, such as an order's amount, and LexQ evaluates your rules and
returns the decision. This library will make that call, with four safeguards:

- Every call has a deadline that covers its retries.
- An attempt that times out or hits a temporary error is sent again with the same
  `Idempotency-Key`. If LexQ has already finished an earlier attempt successfully, it
  returns that result instead of running the decision again. In an organization that
  uses [Fail-open](https://docs.lexq.io/glossary#billing-and-infrastructure), LexQ keeps
  running calls when an outage stops it from checking whether another request holds the
  key. A retry that arrives then, while an earlier attempt is still running or after
  that attempt failed, runs the decision again and is billed again.
- A circuit breaker stops calling LexQ for a while when calls keep failing.
- Every call requires a fallback: the decision your service uses when the call fails.

[spec/SPEC.md](spec/SPEC.md) spells out each safeguard and how the library reads
LexQ's responses. It is an unchanged copy of one version of the LexQ SDK specification.
[CONTRACT.md](CONTRACT.md) says which version that is, where the library does not yet do
what that version says, and how the specification maps onto Java.

To check that the copies in `spec/` match the SHA-256 values in `spec/spec.lock`, and
that `CONTRACT.md` names the version the lock records, run `java scripts/CheckSpec.java`
from the repository root.

To build the library and run its tests, run `./mvnw verify` (`.\mvnw.cmd verify` on
Windows) from the repository root with Java 17 or later. The first time it runs, the
wrapper downloads the Maven version that `.mvn/wrapper/maven-wrapper.properties` pins and
checks its SHA-256. `./mvnw` needs `unzip`: without it, the wrapper downloads a `.tar.gz`
archive, which fails that check. To write `src/main/java/io/lexq/sdk/Contract.java`
again from `spec/values.json`, run `java scripts/GenerateContract.java` from the
repository root.

Run the client on a server. It will refuse to start on Android, where the API key would
reach every user who installs the app. Keep it out of a desktop app for the same reason.

Until the first release, the [Java example](https://github.com/lexq-io/lexq-examples/tree/main/java)
in lexq-examples shows how to call the API, and
[When LexQ Is Slow or Unavailable](https://docs.lexq.io/api/execution#when-lexq-is-slow-or-unavailable)
covers what to do when a call fails.

## Maintenance

I'm Sanghyun Park, and I maintain this library. I write most of it with AI coding
tools, mainly Claude Code. I decide each change, read every commit and diff before I
push it, and merge every pull request myself. Dependabot pushes its own branches to
propose new versions of the CI actions and of the Maven plugins and libraries, and I read
those changes before I merge them.
The rules for changing the library are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Security

Report a vulnerability privately, as described in [SECURITY.md](SECURITY.md).

## License

[Apache-2.0](LICENSE)
