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
  returns that result instead of running the decision again.
- A circuit breaker stops calling LexQ for a while when calls keep failing.
- Every call requires a fallback: the decision your service uses when the call fails.

[CONTRACT.md](CONTRACT.md) spells out each safeguard and how the library reads LexQ's
responses.

Run the client on a server. It will refuse to start on Android, where the API key would
reach every user who installs the app. Keep it out of a desktop app for the same reason.

Until the first release, the [Java example](https://github.com/lexq-io/lexq-examples/tree/main/java)
in lexq-examples shows how to call the API, and
[When LexQ Is Slow or Unavailable](https://docs.lexq.io/api/execution#when-lexq-is-slow-or-unavailable)
covers what to do when a call fails.

## Maintenance

I'm Sanghyun Park, and I maintain this library. I write most of it with AI coding
tools, mainly Claude Code. I decide each change, read every commit and diff before it
is pushed, and merge every pull request myself. The rules for changing the library
are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Security

Report a vulnerability privately, as described in [SECURITY.md](SECURITY.md).

## License

[Apache-2.0](LICENSE)
