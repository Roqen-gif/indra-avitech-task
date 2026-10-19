# User Sessions

A console application in plain Java 21 (no frameworks) that reads commands from standard input, processes them asynchronously and stores the results in an embedded H2 database.

## Build and run

Requirements: JDK 21 or newer (verified on 21 and 27) and Maven 3.9+.

```bash
mvn clean package                         # build, static analysis, tests, coverage gate
java -jar target/user-sessions-1.0.0.jar  # run (data is stored in ./data/app.mv.db)
```

For command history and arrow-key editing when typing by hand, wrap it with [rlwrap](https://github.com/hanslub42/rlwrap): `rlwrap java -jar target/user-sessions-1.0.0.jar`.

An optional first argument overrides the JDBC URL, e.g. `java -jar target/user-sessions-1.0.0.jar jdbc:h2:mem:demo`.

Example session:

```text
$ java -jar target/user-sessions-1.0.0.jar
Commands: LOGIN(user_id), LOGOUT(user_id), DATA_MODIFY(user_id), STATS(), EXIT(). Ctrl+D also exits.
LOGIN(alice)
OK: alice logged in
DATA_MODIFY(alice)
OK: modification recorded for alice
DATA_MODIFY(alice)
OK: modification recorded for alice
DATA_MODIFY(bob)
WARN: modification ignored, bob is not logged in
LOGIN(alice)
WARN: alice is already logged in
FOO(1)
WARN: unknown command: FOO
STATS()
Logged-in users: 1
Modifications per user:
  alice: 2
EXIT()
Shutting down...
```

## Commands

One command per line, in the form `NAME(argument)`.

| Command | Effect | Rejected when |
|---|---|---|
| `LOGIN(user_id)` | starts a session | the user is already logged in |
| `LOGOUT(user_id)` | ends the session | the user is not logged in |
| `DATA_MODIFY(user_id)` | records one modification | the user is not logged in (ignored) |
| `STATS()` | prints the number of currently logged-in users and the number of data modifications per user | — |
| `EXIT()` | processes all queued commands, closes resources and exits | — |

Each processed command prints a confirmation (`OK: …`) or a rejection (`WARN: …`). Invalid input also prints a `WARN:` line, and the application keeps running. A one-line usage hint is printed to standard error on start, so it is visible in a terminal but never mixes into redirected output.

## Architecture

```
STDIN ─► ConsoleApp ─► CommandParser ─► CommandDispatcher ─► UserService ─► UserRepository ─► H2
         (main thread: read + parse)    (N single-thread      (rules)        (JDBC)
                                          workers)
```

| Package | Class | Responsibility |
|---|---|---|
| `command` | `Command`, `ParseResult`, `CommandParser` | sealed command model; parsing that never throws |
| `dispatch` | `CommandDispatcher` | all concurrency: workers, per-user ordering, snapshots, shutdown |
| `user` | `UserService`, `UserRepository`, `UserStats`, `Outcome` | business rules and the storage contract |
| `persistence` | `Database`, `JdbcUserRepository` | schema, connections, SQL |
| `console` | `ConsoleApp` | the read loop and output formatting |
| — | `Main` | wires everything together by hand |

### Concurrency model

- **Asynchronous processing.** The main thread only reads and parses. Each command is handed to a worker and reading continues immediately.
- **Per-user ordering.** There are N single-thread executors (N = available processors). A user's commands always go to worker `Math.floorMod(userId.hashCode(), N)`, so they run in the order they were entered. Commands of different users run in parallel.
- **Atomicity in the database.** The database is the single source of truth for sessions:
  - a duplicate `LOGIN` is detected by the primary key on `sessions.user_id`, not by a check-then-insert;
  - `DATA_MODIFY` is one `INSERT … SELECT … FROM sessions WHERE user_id = ?`, so the "is logged in" check and the insert cannot be separated.

  The business logic is therefore correct even without the ordering guarantee.
- **Connections.** Each database operation opens its own connection with try-with-resources. Connections are never shared between threads. `Database` also holds one idle connection for its whole lifetime. An embedded H2 database closes when its last connection closes, so without it the file would be reopened on almost every operation (about 3.5× slower).
- **Failure isolation.** Every task is wrapped in a try/catch that logs the error, so one failing command never stops a worker.

## Design decisions

| Question | Decision |
|---|---|
| Case sensitivity | Command names are case-insensitive (`login(alice)` works). User ids are case-sensitive (`alice` and `Alice` are different users). |
| Valid user id | 1-64 characters from the POSIX portable filename set (`A-Z a-z 0-9 . _ -`) plus one optional `@`, starting with a letter or digit. Names, emails and UUIDs work (`alice`, `john.doe@corp.sk`, `3f2b9c1e-…`). Control characters, terminal escape sequences and values longer than the database column are rejected by the parser with a `WARN`, before reaching the database. Whitespace around the argument is trimmed. |
| "Modifications per user" | All recorded modifications per user, including earlier sessions and earlier runs (the history is persistent). Sorted by user id, uppercase before lowercase. |
| `STATS()` semantics | Reflects **exactly** the commands entered before it. Every worker first finishes its earlier commands and then waits at a `CyclicBarrier`. The last worker to arrive runs the query while the others are paused, then all continue. Commands entered after `STATS()` are not included. The reader is never blocked. |
| `EXIT()` / end of input / Ctrl+C | End of input (Ctrl+D, end of a piped file) behaves exactly like `EXIT()`. Ctrl+C (SIGINT) also shuts down gracefully through a JVM shutdown hook. Lines after `EXIT()` are ignored. All already queued commands are still processed (30 s timeout), then the database is closed. |
| Output order | Output is asynchronous. Responses of one user always appear in command order; responses of different users may interleave, because they are processed in parallel. With piped input, `Shutting down...` may appear before the output of earlier commands, because reading finishes first. No command is lost. |
| Sessions across restarts | Sessions are cleared on startup, because a session belongs to one run of the program. The modification history is persistent. |
| Invalid input | Empty lines, unknown commands, missing parentheses, trailing characters, missing or unexpected arguments: a `WARN:` line is printed and processing continues. |

## Quality checks

`mvn clean package` fails if any of these fail:

- **Compiler:** `-Xlint:all -Werror` (no warnings allowed).
- **[Error Prone](https://errorprone.info):** static analysis for common Java bugs.
- **[Spotless](https://github.com/diffplug/spotless):** google-java-format formatting check (`mvn spotless:apply` reformats the code).
- **JUnit 5 tests:** H2 in-memory database. Concurrency tests synchronize with latches (never `Thread.sleep`), and every one has a `@Timeout`.
- **JaCoCo:** at least 80% line coverage. The report is in `target/site/jacoco/index.html`.

| Test class | Covers |
|---|---|
| `CommandParserTest` | valid and invalid lines (parameterized); invalid input never throws |
| `JdbcUserRepositoryTest` | SQL behaviour, primary-key handling, statistics, error wrapping |
| `UserServiceTest` | business rules, including the example session |
| `CommandDispatcherTest` | per-key ordering, failure isolation, `STATS` snapshot, graceful shutdown |
| `ConcurrencyTest` | 50 simultaneous logins of one user → exactly one wins; 10 users × 100 modifications → exactly 1000 rows |
| `ConsoleAppTest` | end-to-end scripts via an in-memory reader: example session, EOF, invalid lines |
