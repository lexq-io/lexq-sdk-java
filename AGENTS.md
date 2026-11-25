# AGENTS.md: LexQ Java SDK

This repository builds `io.lexq:lexq-sdk`, the Java client for the
[LexQ](https://lexq.io) execution API. Read three files before changing anything:

- [spec/SPEC.md](spec/SPEC.md): an unchanged copy of one version of the LexQ SDK
  specification, which says what the client must do when it calls LexQ
- [CONTRACT.md](CONTRACT.md): the version of the specification this client follows,
  where the client does not yet do what that version says, and how the specification
  maps onto Java
- [CONTRIBUTING.md](CONTRIBUTING.md): how a change is tested, versioned, and released

The API reference is also served as Markdown:
[Execution API](https://docs.lexq.io/api/execution.md),
[error codes](https://docs.lexq.io/api/errors.md),
[page index](https://docs.lexq.io/llms.txt).
