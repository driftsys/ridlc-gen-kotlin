# Blocking or suspending

|              | `CabinClient`                                                                         | `CabinAsyncClient`                                                                    |
| ------------ | ------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| A signal     | `temperature()`: the current sample, without waiting                                  | `temperature`: a shared `StateFlow` of samples, polled while collected                |
| A call       | blocks the calling thread until its outcome                                           | suspends until its outcome                                                            |
| Timeout      | `timeout` on the client, for every call and `nextEvent`; `null` waits without a bound | none of its own: wrap the call in `withTimeout`                                       |
| Cancellation | none: a call ends with its outcome, its timeout or the member's deadline              | cancelling the coroutine forgets the call                                             |
| `nextEvent`  | returns `null` at the timeout                                                         | suspends until an occurrence; concurrent calls take occurrences one at a time         |
| Concurrency  | use it from one thread at a time                                                      | one call at a time: a second call waits for the first; use a second client to overlap |
| Serving      | `Cabin.serve(handler, provider, timeout)`, on a thread of its own                     | `Cabin.serveAsync(handler, provider)`, in a coroutine                                 |

Whichever you pick, each command and query also has its own deadline, the
response bound the `.ridl` file declares (`@[..50ms]` for `setLevel`), or 1 s
for an untimed command and 3 s for an untimed query. Past it, a call fails even
when the client's timeout is longer.

Pick the blocking client for code that already runs on its own threads, such as
a command-line tool or a test. Pick the suspending client when the application
uses coroutines, so that a wait does not hold a thread.
