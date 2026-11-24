# Taint / IAST capability audit

Date: 2026-11-23 (third pass: async carry, sanitiser framework, array/builder parity)
Scope: `weaver-girl-api` taint package, `weaver-girl-core` JDK advice and the concat transformer,
the taint runtime plugin, and the servlet / JDBC plugins that feed sources and sinks.

## Verdict

The base now carries the **engine half** of an IAST product: request-scoped source tagging,
slice-aware propagation across the string idioms modern compilers emit (including Java 9+
`invokedynamic` concatenation), correct range arithmetic for mutable builders, sanitisers, a
generic sink check with call-site attribution, and findings on the normal export path. What it
still does not ship is the **product half**: a rule/source/sink registry, broad source and sink
coverage, and vulnerability rules. Those are deliberately out of scope for this pass.

## What exists

| Capability | Entry point | Notes |
| --- | --- | --- |
| Request scope, nested | `Taint.openScope()` / `closeScope()` | One scope per servlet `service` / `doFilter`; marks in outer scopes remain visible |
| Source tagging | `Taint.tag(Object, String)`, `HttpSourceCapture` | Parameters, headers, cookies, request URI / path info / query string (`http.parameter:q`, `http.header:x`, `http.cookie:sid`, `http.uri`, …) |
| Slice-precise propagation | `TaintPropagation` | Character ranges `[start,end)` per source through concat/substring/append/toString and builder edits |
| Java 9+ concat | `TaintConcatTransformer` + `TaintConcat` | Rewrites `makeConcat` / `makeConcatWithConstants` call sites; precise argument offsets |
| Whole-result transforms | `StringDerivedAdvice`, `StringJoinAdvice` | `trim`, `strip*`, `toUpperCase`/`toLowerCase`, `replace*`, `String.join` |
| Mutable builder edits | `StringBuilderDelete/Insert/Replace/SetLength/Reverse/SetCharAt` advice | Ranges shift, split, clip and mirror with the text; applies to `StringBuffer` too |
| Array round trips | `toCharArray`, `getChars`, `getBytes`, `new String(char[]/byte[])`, `append(char[])`, `append(char[],int,int)`, `insert(char[])`, `insert(int,char[],int,int)` advice | `char[]` keeps per-character slices (a `getChars` write replaces only the window it wrote); `byte[]` keeps an object-level mark that `new String(byte[])` inherits whole |
| Object rendering | `append(Object)`, `insert(int,Object)`, `String.valueOf(Object)` advice | The object's mark covers exactly the window the rendering added |
| Builder seeding | `new StringBuilder(CharSequence)` / `new StringBuffer(CharSequence)` advice | A builder created from a tainted string starts with the same slices |
| Sanitisers | `Taint.untaint(Object)` / `untaint(Object, String)` / `untaintAll(Object...)` | Removes marks in every scope of the current thread; `untaintAll` returns how many values were cleaned |
| Sanitiser framework | `TaintSanitizerInterceptor.returnValue()` / `.argument(int)` / `.returnValueAndArgument(int)` | Drop-in `Interceptor` for encoders, validators and casts; registered like any other interceptor |
| Generic propagation | `Taint.propagate(Object source, Object target)` | Copies distinct labels onto the whole target; object-level mark for non-strings |
| Generic sink | `Taint.sink(String sinkKind, Object... values)` | Emits one finding per tainted slice and returns whether any value was tainted |
| Sinks | `SqlExecutionSink`, `CommandExecutionSink` | JDBC observes `prepareStatement`/`prepareCall` and `execute`; JDK advice observes `Runtime.exec` / `ProcessBuilder.start` |
| Findings | `TaintFindings` → `InterceptorEvent` type `iast-finding` | Same listener/exporter path as every other event; trace/span ids stamped |
| Finding location | `callerClass` / `callerMethod` / `callerLine` attributes | First non-library, non-agent frame; `className`/`methodName` carry the same caller |
| Thread hand-off | `TaintBridge.wrap(Runnable/Callable)` installed on `ThreadPoolExecutor.execute`, `ForkJoinPool.execute`, its `submit` overloads, and the one-shot `ScheduledThreadPoolExecutor.schedule` overloads | Snapshot is taken at submission, installed around the task, and the worker's previous scopes are restored afterwards; a submission without a scope stays clean. Periodic scheduling is deliberately not wrapped, so a request scope cannot be pinned for the process lifetime |
| Manual context carry | `Taint.hasScope()`, `captureContext()`, `restoreContext(Object)`, `wrap(...)` | For boundaries no framework wraps: custom threads, third-party callbacks, `new Thread` |

## Verified propagation behaviour

Environment: Corretto 21.0.12, agent built from this tree with `taint=true`; probe classes compiled
with `--release 8` (StringBuilder concatenation) and `--release 17` (`invokedynamic`).

| Operation | JDK 8 bytecode | JDK 17+ bytecode | Finding at SQL sink |
| --- | --- | --- | --- |
| `a + b` (String) | yes | yes (concat transformer) | yes on both |
| `String.concat` | yes | yes | yes |
| `StringBuilder.append/toString` | yes | yes | yes |
| `StringBuilder.delete/deleteCharAt/insert/replace/setLength/reverse` | ranges follow the edit | same | correct slice, no stale range |
| `substring` | yes | yes | yes |
| `String.format` | yes (via `Formatter`'s `StringBuilder`) | yes | yes |
| `String.trim` / `strip*` | yes | yes | yes |
| `String.split` | yes (internally `substring` on this JDK) | yes | yes |
| `String.replace` / `replaceAll` / `replaceFirst` | yes (whole result) | yes | yes |
| `String.join` | yes (whole result) | yes | yes |
| `toUpperCase` / `toLowerCase` | yes (whole result) | yes | yes |
| `toCharArray` / `new String(char[])` / `new String(char[],int,int)` | precise slice | precise slice | yes |
| `getChars(int,int,char[],int)` | written window only | same | yes |
| `insert(int,char[],int,int)` | copied window only | same | yes |
| `getBytes` / `new String(byte[])` | object-level mark inherited whole | same | yes |
| `StringBuffer` seeding, append, insert, delete | yes | yes | yes |
| `String.valueOf(char[])` / `valueOf(Object)` | slices / whole window | same | yes |
| `ThreadPoolExecutor` / `ForkJoinPool` / `CompletableFuture` / `schedule` hand-off | scope visible in the worker | same | yes in worker |

Slice precision: concat, substring, append and builder edits keep exact offsets; the
whole-result transforms trade precision for zero false negatives (the finding still says which
source reached the sink, just with a `[0,length)` slice).

The `invokedynamic` rewrite is conservative: classes loaded by the bootstrap/platform loaders and
agent/JDK packages are skipped, the transform is idempotent, and a class that fails to rewrite is
returned untouched. Verified by a runtime-compiled (`--release 17`) probe defined after the agent
installed (`IastHookLaunchTest.javaNineConcatenationKeepsTheFlowThroughTheRegisteredTransformer`)
and by a direct transformer test (`TaintConcatTransformerTest`, 6 cases including boxing,
idempotency and skip rules).

## Remaining gaps

1. **No rule / source / sink registry or DSL.** Rules are still code: a product registers an
   interceptor and calls `Taint.sink` / `Taint.untaint`. This is the 2.0 product layer.
2. **Source coverage stops at the servlet envelope.** Parameters, headers, cookies and the URL
   (URI / path info / query string) are captured reflectively; there is no request body,
   multipart, JSON/XML binding, RPC, messaging, or second-order (database-to-response) taint.
   `HttpSourceCapture` uses reflection on `getParameterMap`, `getHeaderNames`, `getCookies`,
   `getRequestURI`, `getPathInfo`, `getQueryString`.
3. **Sink coverage is SQL + process execution only.** No XSS (response writer), path traversal,
   SSRF, LDAP/JNDI, deserialization, expression/template injection, redirect, or XXE. The generic
   `Taint.sink` entry makes adding them a small plugin, but none ship in the base.
4. **Whole-result propagation is coarse.** Transforms and `Taint.propagate` mark the entire result,
   so a finding through them can report a slice larger than the actual payload.
5. **Findings carry no rule id, severity, or confidence.** Location, source, sink, argument and
   slice are present; grading is left to the product.
6. **Cost is JVM-wide and permanent.** `taint=true` instruments `java.lang.String`,
   `StringBuilder`/`StringBuffer`, `ThreadPoolExecutor`, `ForkJoinPool`, `ProcessBuilder`,
   `Runtime`, and rewrites concat sites for classes loaded afterwards. Advice cannot be removed;
   the transform is skipped when a class has no `makeConcat`. The executor hook wraps every
   submission, so a taint-on JVM pays one snapshot per submitted task even when nothing is tainted
   (the wrapper is skipped entirely while no scope is open).
7. **Identity-based store has the usual aliasing caveats.** Interned or shared strings are
   indistinguishable from the request's value; a sanitiser only clears the current thread, not
   snapshots already handed to other threads.
8. **Thread hand-off covers executors, not raw threads.** `new Thread(taintedTask)` inherits
   nothing; the task must be built with `Taint.wrap` or run under `captureContext`/`restoreContext`.
   Parallel-stream internals (`ForkJoinTask.fork`/`invoke` on a task submitted elsewhere) and
   executor paths that bypass `execute`/`submit`/`schedule` (custom `ThreadFactory` callbacks,
   `invokeAll`/`invokeAny` on `ForkJoinPool`, periodic scheduling) are also unwrapped. Values that
   escape a worker carry no marks back, and `afterExecute` sees the wrapper rather than the user's
   task.
9. **Constructor advice has no exception path.** ByteBuddy cannot attach exit advice that runs on
   a throwable to a constructor, so `String(char[], int, int)` probes and immediately releases the
   re-entrancy guard instead of holding it. The bounds check throwing therefore leaves no state
   behind, at the cost of skipping the re-entrancy exclusion for that one call site.
10. **JDK-version assumptions.** The concat rewrite targets `StringConcatFactory` (Java 9+); older
    runtimes fall back to the `StringBuilder` advice, which already covered `+`. `strip`,
    `stripLeading` and `stripTrailing` simply match nothing on Java 8.

## Fixed in this pass

- Thread hand-off: `TaintBridge.wrap(Runnable/Callable)` plus the `ExecutorTransformer` on
  `ThreadPoolExecutor.execute`, `ForkJoinPool.execute` and the `ForkJoinPool.submit` overloads
  (`submit` overrides `execute`, so both had to be hooked). Submission-time snapshots avoid the
  stale-scope poisoning an `InheritableThreadLocal` would cause on pooled workers
  (`IastHookLaunchTest.executorSubmissionsCarryTheSubmittingScopeIntoTheWorker`,
  `commonPoolAndCompletableFutureCarryTheScope`, `TaintScopeTest`).
- Manual context API: `Taint.hasScope()`, `captureContext()`, `restoreContext(Object)`,
  `wrap(...)` for boundaries without an instrumented submission point.
- Sanitiser framework: `Taint.untaintAll(Object...)` and `TaintSanitizerInterceptor`
  (return-value / argument / combined modes) so a sanitiser is one interceptor registration
  (`TaintSanitizerTest`, `TaintSanitizerInterceptorTest`).
- Array and rendering parity: `toCharArray`, `getChars`, `getBytes`, `new String(char[]/byte[])`,
  `append(Object)`, `append(char[])`, `append(char[],int,int)`, `insert(int,Object)`,
  `insert(int,char[])`, `insert(int,char[],int,int)`, `setCharAt`, `String.valueOf`, and builder
  constructors. `StringBuffer` works throughout because every builder advice takes
  `CharSequence` instead of `StringBuilder` (`TaintPropagationTest`,
  `IastHookLaunchTest.builderConstructorsStringBufferAndArrayRoundTripsKeepTheFlow`,
  `getCharsAndInsertRangeMapTheirWindows`).
- Timed hand-off: the one-shot `ScheduledThreadPoolExecutor.schedule` overloads wrap their task
  like any other submission (`IastHookLaunchTest.scheduledTasksCarryTheSubmittingScope`).
- Guard correctness: every advice that takes the re-entrancy guard now releases it on every exit
  path, including the throwable path (`onThrowable` + `@Advice.Thrown` + `TaintBridge.release`), so
  an exception from `substring`, `append` or `getBytes` no longer silences propagation for the rest
  of the request (`IastHookLaunchTest.exceptionInsideAnInstrumentedCallReleasesTheGuard`,
  `TaintAdviceTest.aThrownExceptionReleasesTheGuardWithoutPropagating`). Constructor advice probes
  and releases in `onEnter`, because ByteBuddy forbids the throwable exit there.

## Fixed in the earlier passes

- Receiver-side bridge visibility: `TaintBridge.Range` / `lookup` are public, so `Taint.ranges()`
  works when the bridge lives in the bootstrap loader
  (`IastHookLaunchTest.rangesStayReadableAfterTheBridgeMovesToTheBootstrapLoader`).
- Sanitisers: `Taint.untaint(Object)` / `untaint(Object, String)` with `TaintBridge.clear`
  (`TaintSanitizerTest`).
- JDBC: SQL is observed at `prepareStatement` / `prepareCall` time, so prepared queries are seen
  (`JdbcPluginTest.prepareStatementEmitsSqlFindingForTaintedSql`).
- Java 9+ concat: `TaintConcatTransformer` spills concat arguments into fresh locals above
  `maxLocals`, runs the original indy, and calls bootstrap-loaded `TaintConcat.propagate` with a
  transform-time template. No branch targets move, original stack map frames stay valid
  (`TaintConcatTransformerTest`, `TaintConcatTest`, launch probe).
- Transform / mutation coverage: `trim`, `strip*`, case conversion, `replace*`, `join`, and
  `StringBuilder.delete/deleteCharAt/insert/replace/setLength/reverse/append(slice)` advice
  (`IastHookLaunchTest.transformAndMutationAdviceKeepTheFlowAndItsOffsets`,
  `TaintPropagationTest` range arithmetic, 7 new cases).
- Generic engine APIs: `Taint.propagate`, `Taint.sink`, `TaintBridge.emitValue`
  (non-string sinks), `TaintPropagation.observeSink` (flattens arrays, iterables and map values).
- Findings carry the application call site (`callerClass`, `callerMethod`, `callerLine`).
- Source capture adds cookies and the URL envelope (`http.cookie:*`, `http.uri`, `http.path`,
  `http.query`) alongside parameters and headers (`HttpSourceCaptureTest`).

## Recommended next steps

1. Build the 2.0 rule layer on top of the primitives: a source/propagator/sink/sanitiser
   descriptor plus a matcher, so rules are data instead of interceptor code.
2. Add the missing sinks as reference plugins: response writer (XSS), `File`/`Paths` (traversal),
   `URL`/HTTP clients (SSRF), `InitialDirContext`/`Context.lookup`, `ObjectInputStream`.
3. Add source plugins for request bodies (servlet input stream, Spring `@RequestBody`, JSON
   binders) and for cookies/path parameters.
4. Add a source-to-sink stack context (deep trace) so a finding can show the full propagation path;
   today the event carries the terminal slice only.
5. Consider a benchmark in CI for the taint-on path, including the concat transformer's per-class
   scan; the earlier numbers are a microbenchmark, not a regression gate.
