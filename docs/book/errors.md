# Errors

| Thrown                  | By                                             | When                                                                                                                                                                                           |
| ----------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ConstraintViolation`   | `of` on a value object, a struct's constructor | the value breaks a constraint of its type                                                                                                                                                      |
| `ClientError.Send`      | a client call                                  | the call was not sent, for example `SendError.Busy` until the member's deadline                                                                                                                |
| `ClientError.Call`      | a client call                                  | the outcome is a failure: `Contract.PreconditionFailed`, `Contract.ContractBroken`, `Transport.Timeout` for a query past its deadline, `Transport.Undelivered` for a command past its deadline |
| `ClientError.Read`      | a client call, `nextEvent`                     | the port failed while the outcome was read                                                                                                                                                     |
| `ProviderError.Serve`   | `serve`, `serveAsync`                          | the handler refused the interface's members                                                                                                                                                    |
| `ProviderError.Claim`   | `serve`, `serveAsync`                          | the handler failed while a claim was read                                                                                                                                                      |
| `IllegalStateException` | a client's or publisher's constructor, `serve` | the port is attached to another catalog than the one the code was generated from                                                                                                               |

An exception your provider throws is not caught: it leaves `serve` or
`serveAsync` unchanged. A program that must not throw on a catalog mismatch
compares `port.catalog == Cabin.catalog` before it builds a client.
