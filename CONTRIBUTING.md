# Contributing

These rules cover every change to this repository. What the library must do is in the
LexQ SDK specification, copied into [spec/SPEC.md](spec/SPEC.md), and how it does that
in Java is in [CONTRACT.md](CONTRACT.md). So far the repository holds the build,
`Contract.java` generated from `spec/values.json`, and the CI checks. The code that calls
LexQ, its contract tests, and the release workflow come in later changes.

- Unit and contract tests use a fake server. A change to how a call is sent or read
  also gets one real call against a live tenant before it lands. Outputs pasted into
  the README or comments come from a real run, and the pull request says which run.
- Sanitize pasted output. API keys, tenant IDs, user IDs, and personal data become
  placeholders. Apart from those, decision payloads stay exactly as they came back.
- Response types list only the fields LexQ sends, taken from the Execution API
  reference and a real response. What the client's own result adds is in
  [Returning a decision](spec/SPEC.md#returning-a-decision).
- `spec/` holds `SPEC.md` and `values.json`, unchanged copies of one version of the
  specification, and `spec.lock`, which records that version and the SHA-256 of each
  copy. Never edit these files by hand. To report a problem in the specification, open
  an issue in this repository. Maintainers replace the three files together when the
  library moves to another version of the specification, in one pull request that also
  names that version in `CONTRACT.md` and either changes the code and the tests to match
  or lists in `CONTRACT.md` what the code does not do yet. CI fails when `spec/` does
  not hold exactly the three files, when one of them is not a regular file, when a
  copy's SHA-256 differs from the lock, or when `CONTRACT.md` names a version other than
  the lock's, names the lock's more than once, or leaves it out. Any string written like
  `v1.2.3` counts as a version, even one meant as a tag of this library.
- `spec/values.json` holds the LexQ enums and constants the specification uses, the
  error codes it names, and the defaults. From it, `scripts/GenerateContract.java` writes
  every default and the LexQ constants the script lists into
  `src/main/java/io/lexq/sdk/Contract.java`, and the tests fail when that file is not what
  the script writes.
- Add a runtime dependency only with a reason in the pull request. Every dependency
  ships into every customer's server. A customer's build also resolves each library to
  a single version, so the version their application already uses can replace the one
  this library was tested with.
- Errors and logging follow [Errors](spec/SPEC.md#errors) in the specification.
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
