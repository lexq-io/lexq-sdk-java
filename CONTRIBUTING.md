# Contributing

These rules cover every change to this repository. What the library itself must do
is in [CONTRACT.md](CONTRACT.md). The repository holds only documents so far: the
build, the tests, CI, and the generated files these rules name come in later changes.

- Unit and contract tests use a fake server. A change to how a call is sent or read
  also gets one real call against a live tenant before it lands. Outputs pasted into
  the README or comments come from a real run, and the pull request says which run.
- Sanitize pasted output. API keys, tenant IDs, user IDs, and personal data become
  placeholders. Apart from those, decision payloads stay exactly as they came back.
- Response types list only the fields LexQ sends, taken from the Execution API
  reference and a real response. The library's own result may add derived fields,
  such as `isBlocked`.
- LexQ's own limits and error codes that the client relies on, such as how deep a
  request body may nest, live in `contracts/lexq-manifest.sdk.json`, and a Java source
  file is generated from it. Change them by regenerating both from LexQ's contract
  manifest, never by hand. The tests fail when the two files disagree or the
  documentation states another value.
- Add a runtime dependency only with a reason in the pull request. Every dependency
  ships into every customer's server. A customer's build also resolves each library to
  a single version, so the version their application already uses can replace the one
  this library was tested with.
- Log nothing by default. Errors carry the status, `errorCode`, LexQ's `message`, and
  the idempotency key. They never carry the API key, a value from `facts` or
  `contexts`, or a raw response body, because callers log errors. For the same reason,
  every message is one line of bounded length, even where it quotes text the client did
  not write, such as LexQ's message or a key in `facts` or `contexts`. Report anything
  else through the result and an optional hook the caller owns.
- Publish only from a tag. Raise `version` in `pom.xml` inside the pull request, where
  a reviewer sees it beside the change that justifies it. After the merge, push the
  matching `v<version>` tag. CI signs that version and uploads it to Maven Central, and
  it goes public only when a maintainer publishes it there. Never upload a build by
  hand. Maven Central never changes or removes a published version, so a mistake is
  fixed only by releasing another one.
- Pick the version number by what breaks. A change is breaking when a call that used
  to work now fails or does something else. That includes removing or renaming an
  option, changing its type, making an optional argument required, narrowing the
  values it accepts, and changing a safeguard in a way a caller notices. Breaking
  changes also include changing a method's return type, which breaks code compiled
  against the previous version even where its source still compiles, and adding a
  value to an enum, which breaks a `switch` expression or a Kotlin `when` that lists
  every value with no `default` or `else`. Raising the minimum Java version is
  breaking too. Before `1.0.0`, a breaking change raises the minor number. From
  `1.0.0` on, it raises the major.
- Link only to lexq.io, docs.lexq.io, and public repositories.
- Commit messages take the form `type: description`.

Run commands and supported Java versions belong in the README and `pom.xml`.
