# Client contract

This repository builds `io.lexq:lexq-sdk`, the Java client for the [LexQ](https://lexq.io)
execution API. Kotlin and Scala code uses the same client. A backend service sends facts,
such as an order's amount and the customer's tier, and LexQ evaluates the deployed rules
and returns the decision. This library makes that call. It does not evaluate rules itself.

The library starts with one endpoint, `POST /api/v1/execution/groups/{groupId}`.
Batch and composite execution are not in it yet. Managing rules belongs to the
[LexQ CLI](https://github.com/lexq-io/lexq-cli) and its MCP server. The endpoint is
described in the [Execution API reference](https://docs.lexq.io/api/execution), and
its failures in [error codes](https://docs.lexq.io/api/errors).

## Resilience Contract

A call to LexQ crosses the network, so it can be slow or fail. What the caller should
do then depends on the decision: a checkout can go ahead without a discount, but a
transfer should wait. This library exists for the four safeguards below, and every
release keeps all four.

### 1. Give every call a deadline

The default is finite. The client sets it, a call can override it, and neither can
turn it off. The deadline covers every attempt and every wait between attempts. Each
attempt also has its own timeout, cut to the time left before the deadline, so an
attempt that hangs leaves time for another. An attempt's timeout covers the whole
response, up to its last byte. A timeout set with `HttpRequest.Builder.timeout()` in the
JDK's HTTP client can stop counting once the headers arrive, so it cannot serve as the
attempt timeout on its own.

### 2. Retry with the same `Idempotency-Key` and the same bytes

When an attempt fails, send it again with the same key. If the caller passes no key,
generate one per call and use it for every attempt of that call. Serialize the body
once and resend those bytes: the same key with a different body fails with `I-001`,
and a body serialized a second time can differ. Retries are on by default. The client
or a single call can turn them off.

| What came back | Retry |
|---|---|
| Nothing: the attempt timed out or the connection failed | Yes, with backoff, while the deadline allows |
| `502`, `503`, or `504` | Yes, with backoff, while the deadline allows |
| `429` | Yes, after `Retry-After` if the response has one, or else after a longer wait, while the deadline allows. A request rejected with `429` did not use its key |
| `409` with `I-002` | Yes, with backoff, while the deadline allows. A request with this key is still running or failed less than a minute ago |
| `409` with `I-001` | Never. The key is spent, and only a new key works |
| `403` with `B-008` | Never. The monthly execution quota is used up, and every new execution fails the same way until it resets or the plan is upgraded |
| Anything else | No |

If this library adds batch execution, it must never retry a batch automatically.
Batch calls take no `Idempotency-Key`, so a retried batch runs and bills every item
again.

### 3. Keep a circuit breaker on by default

The breaker counts calls, not attempts. A call adds one when it ends with the fallback
because the deadline ran out, or because its last attempt timed out, failed to connect,
or got a 5xx. Any other call that got a response resets the count to zero. A call that
sent nothing, because the breaker was open or the call failed before sending, leaves the
count alone. So does a call that its caller cancelled, as described under Errors, even
when an earlier attempt got a response.

When the count reaches the threshold, the breaker opens: calls skip the network and go
straight to the fallback. After a cool-down, one trial call goes through with its
retries, while other calls keep getting the fallback. If the trial adds to the count,
the breaker opens again. If its caller cancels it, or it fails before sending, the next
call becomes the trial. Otherwise the breaker closes. The breaker belongs to the client
instance, so a service creates one client and shares it, and the client is safe to call
from many threads at once. The client can turn the breaker off.

### 4. Require a fallback on every call, and use it on every failure

The library never picks a decision for the caller, because only the caller knows which
side is safe. LexQ errors that are not retried use the fallback too. A rule change that
needs a new fact, or a policy group someone disabled to stop bad decisions, must not
take the caller's service down. The fallback is a parameter of every call: a function
that receives the error and returns the caller's own value. If it throws, the call
throws that exception, and an asynchronous call's future completes exceptionally with
it. The deadline covers the attempts to reach LexQ, not the fallback. The result is
either LexQ's decision or the fallback's value with the error, and it says which.

### Errors

Two kinds of mistake skip the fallback, because they are bugs in the calling code. One
is leaving out the fallback. It is a parameter of the call, so a call without one fails
to compile in Java, Kotlin, and Scala, and passing `null` in its place throws
`NullPointerException`. The other is an argument the client cannot use, such as a `null`
API key, which throws `NullPointerException`, or a deadline of zero, which throws
`IllegalArgumentException`. The client throws these exceptions before anything is sent,
from the constructor or from the call, and an asynchronous call throws them instead of
returning a future. The second kind does not include the contents of the facts or the
contexts, or the text of an idempotency key: when the client cannot send those, the
call uses the fallback, as described below.

A call that its caller cancels skips the fallback as well: a call whose waiting thread is
interrupted, or an asynchronous call whose future is cancelled. The client stops the
attempt in flight and does not run the fallback, because nothing is waiting for its
value. An interrupted call leaves the thread's interrupt status set and throws
`CancellationException`, as `join()` on a cancelled future does.

Everything else uses the fallback, including two failures that happen before anything
is sent. One is something in the facts or the contexts that the client will not write:
a value that JSON cannot carry as written, such as `NaN`, `Infinity`, or a map with a
key that is not a string; a value nested deeper than the client's limit; a number with
more digits than LexQ accepts; or a collection that is not a `List`, such as a `Set` or
the `values()` of a map. The client counts a number's digits before writing it, because
without an exponent `1E+10000000` is 10,000,001 characters long. The `Set` and
`Collection` interfaces promise no order: `Set.of` and the `values()` of `Map.of` can
iterate in a different order each time the JVM starts, so a repeat with the same key
could send a different body. The other failure is an idempotency key that is empty or
that a header cannot carry. For both, the fallback receives a `LexqException` whose
reason says the request was not sent.

The error the fallback receives is always a `LexqException`, an unchecked exception. Its
reason names what happened: an open breaker; the deadline; an attempt that timed out; a
failed connection; a LexQ error or a response the client could not use, with its status
and any `errorCode`; or a request the client did not send. The exception also lists
every attempt and carries the idempotency key the call used. When LexQ finished the
call's decision and it succeeded, repeating the call with that key within 24 hours
returns the decision instead of running a second one.

### Contract tests

Each safeguard must have a contract test that CI runs on every pull request. The tests
are the specification: a change to a safeguard changes its test in the same pull
request, and the description says why. The fallback test runs the JDK's compiler on a
call without a fallback and fails if that call compiles. Thresholds, waits, and default
values belong in the code next to their tests, not in this file.

## Wire Contract

### Reading a response

- Read the body for every status. LexQ reports failures in the body, for 5xx as well
  as 4xx. The JDK's HTTP client does not throw on an error status, and a replacement
  HTTP client must not either. Spring's `RestClient`, for one, throws by default.
- Never follow a redirect. The JDK's HTTP client follows none by default, but one built
  to follow them, such as with `Redirect.NORMAL`, sends the request's headers,
  `x-api-key` included, to the new address, even on another host, and after a `307` or
  `308` it sends the body too. A `3xx` is a failure that keeps its status.
- A body that is not JSON is a failure that keeps its status. A load balancer's 502
  page or the 404 from a wrong host is HTML.
- Read at most a fixed number of bytes. A larger body is a failure that keeps its
  status, even when it carries a decision, so one response cannot fill the caller's
  memory.
- A LexQ response has two branches. `result: "SUCCESS"` carries `data`, and
  `result: "ERROR"` carries `errorCode` and `message`. The key is `errorCode`, not
  `code`, and reading the wrong key fails silently: every error arrives without a
  code.
- A 2xx whose `result` is not `SUCCESS`, or a 4xx or 5xx whose `result` is `SUCCESS`,
  is a server defect. Treat it as a failure even if it carries `data`.
- Branch on `errorCode` as well as the status. `I-001` and `I-002` are both `409` and
  need opposite handling. Codes are stable, and messages may be reworded.

### Returning a decision

- Return every field LexQ sends in `data`. Besides the facts and `traceId`, that is
  `executedAt`, `totalProcessingTimeMs`, `inputContexts`, `executionTraces`, and
  `decisionTraces`. A field LexQ adds later reaches the caller too, through a map of
  every field in `data`, before the library's types name it. When they do, the type of a
  field added later accepts `null`, so it is never a primitive such as `long` or
  `boolean`, and the client accepts the field when it is empty: a retry that LexQ serves
  from the idempotency key can return a decision made before the field existed, with
  the field `null`, `0`, or `false`. A test reads the single-group response the
  Execution API reference shows and fails when one of its fields is missing from the
  result.
- Return the final facts as well as the three layers LexQ sends. `inputFacts` holds
  the facts as LexQ received them, `mutatedFacts` the facts a rule changed, and
  `generatedVariables` the keys the request did not send, such as a `SET_FACT` target.
  The final facts are `inputFacts` with `mutatedFacts` laid over it and then
  `generatedVariables`, so where two layers have the same key, the later layer wins.
- Make a block hard to miss. A `BLOCK` action does not fail the call. It sets
  `isBlocked` to `true`. The key arrives in `generatedVariables` when the request did
  not send `isBlocked`, and in `mutatedFacts` when the request sent another value. If
  the request already sent `true`, only `inputFacts` has it. The result for LexQ's
  decision has a top-level `isBlocked` that is `true` when the final facts have
  `isBlocked: true`, and `false` otherwise. A request that sends `isBlocked: true` gets
  `true` back even when no `BLOCK` ran, so the documentation must say to send an
  earlier block under another key, such as `previouslyBlocked`. A fallback's value has
  no such field.
- Return `data.traceId`. It is the execution's audit handle, and the caller stores it
  with the record the decision applies to. A retry that LexQ served from the
  idempotency key returns the original `traceId` and `executedAt`.
- Keep every number exact in both directions. LexQ calculates in decimal, and an
  amount can carry 34 significant digits or more. A `double` holds 15 reliably, and
  the library cannot tell an amount from a count. Parse responses so that no number
  loses digits, and give callers a way to send an exact amount as a JSON number. A
  string such as `"150000"` is not one: a fact whose type is NUMBER rejects it with
  `P-060`. Every number in the facts, the contexts, and the traces reaches the caller
  as a `BigDecimal` with the digits and the scale LexQ wrote, so `1.50` stays `1.50`.
  The same amount can arrive with different scales, such as `20` and `20.0`, and
  `equals` is false between them, as is `==` in Kotlin and Scala, so the documentation
  must say to compare numbers with `compareTo`. `totalProcessingTimeMs` is a `long`,
  because it counts milliseconds. A caller sends an exact amount as a `BigDecimal` or a
  `BigInteger`, and a `BigDecimal` from one decision goes back out unchanged. A
  `double` or a `float` becomes the decimal that `Double.toString` or `Float.toString`
  writes, without trailing zeros after the decimal point. So `0.1` goes out as `0.1`,
  not as the 55 decimal places of `new BigDecimal(0.1)`, and `0.0001`, which
  `Double.toString` writes as `1.0E-4`, goes out as `0.0001`, not `0.00010`. The client
  sends every number without an exponent, as `toPlainString()` writes it, so a number
  LexQ wrote as `0.0000001` goes back out the same way, not as the `1E-7` that
  `toString()` writes. For the same reason, the documentation must say to print numbers
  with `toPlainString()`. A contract test sends and reads back an amount with more than
  34 significant digits.

### Sending a request

- Keep the API key on the server. Send it in the `x-api-key` header, never in
  `Authorization`. The constructor throws on Android, where `java.vm.name` is `Dalvik`,
  because the key would ship to every user who installs the app. A desktop app hands
  the key to its users the same way, but its JVM looks like a server's, so the
  documentation must say to keep the client out of desktop apps. The constructor
  accepts an `http` base URL only for `localhost`, `127.0.0.1`, and `[::1]`.
- Send `null` as JSON `null` and keep its key, at every level of `facts` and
  `contexts`. LexQ treats a key sent as `null` differently from a key left out. A
  condition on a `null` value does not match, even in a rule that blocks. If an enabled
  rule reads a key that the request leaves out of `facts`, the request fails with
  `P-015`, which names the key, before any rule runs.
- Never shorten a caller's idempotency key. LexQ accepts up to 255 characters and
  answers a longer key with `I-003` rather than cutting it. Keys are stored with the
  execution, so tell callers to hash long IDs and keep personal data out of them.
- Send `trafficKey` in `contexts`. A/B routing reads `contexts.trafficKey`. In `facts`
  it is ignored, and the test version receives no traffic. The library does not move
  it, so the documentation for `contexts` must say where it goes. LexQ stores the whole
  `contexts` object with the execution, so the documentation must also say to keep
  personal data out of it and to make `trafficKey` a surrogate key or a keyed hash, never
  an email address or a phone number.
