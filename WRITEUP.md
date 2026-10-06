# Seat Selection: Write-up

A JSON API that sells assigned seats for a show and stays correct when thousands of buyers hit the same seats at once. Spring Boot 4 (Java 21, virtual threads), Postgres, jOOQ, Flyway.

**Diagrams:** [Reservation Flow Map](https://claude.ai/artifact/3dvXypsjPtJYn87m5inQve) shows who calls whom, the steps and lock order inside `reserve()`, and the reservation lifecycle.

**Live URL:** https://paytm-seat-selection.onrender.com. How to run everything is in the [README](README.md).

> [!IMPORTANT]
> ## ⏱ Cold start: the live service sleeps when idle
> **The live service runs on Render's free tier. After about 15 minutes without requests, Render shuts the instance down. The next request starts it again, which takes about a minute** (the container boots, the JVM starts, and the app connects to the database). That first request is slow, not broken.
>
> **Wake it up before testing**, and wait until this prints `{"status":"UP",…}`:
> ```bash
> until curl -sf --max-time 120 https://paytm-seat-selection.onrender.com/actuator/health/readiness; do sleep 5; done
> ```
> **The first burst after waking is also slower** while the JVM warms up. Running `burst.sh` twice gives the more representative second result.

## 1. The atomic decision

**Mechanism:** a pessimistic row lock taken in a fixed order (`SELECT … ORDER BY seat_name FOR UPDATE`), followed by a conditional update guarded on current state (`UPDATE seats … WHERE status = 'AVAILABLE'`), inside one transaction that also claims the idempotency key and the per-user quota.

Every decision is made by Postgres inside one transaction, never by reading a value into Java and then writing based on it. `ReservationService.reserve()` runs three guarded steps in a fixed order. Any decline throws, which rolls back every step.

| Step | SQL | What it guarantees |
|---|---|---|
| 1. Claim the idempotency key | `INSERT INTO reservations … ON CONFLICT (user_id, idempotency_key) DO NOTHING` | One reservation per key (section 2) |
| 2. Per-user limit | `INSERT INTO user_show_holds … ON CONFLICT DO UPDATE SET seat_count = seat_count + n WHERE seat_count + n <= limit` | A user can never exceed the limit, even with parallel requests |
| 3. Seats | for several seats, `SELECT … ORDER BY seat_name FOR UPDATE` first; then `UPDATE seats SET status = … WHERE … AND status = 'AVAILABLE'` | A seat can never be sold twice |

**Before step 1,** two plain reads answer the requests that cannot create anything: a retry whose key already has a reservation gets that reservation back, and a request for a seat that is already gone gets `409 seat_taken`. Under an on-sale that is most of the traffic, so most requests never write a row or wait on a lock. These reads can be stale, but only in one direction: they can turn a buyer away from a seat that was taken a moment ago, never give one away. Taking a seat is always decided by step 3.

**Why step 3 is race-free.** The row lock means only one transaction at a time can decide a given seat. For a single seat, the guarded `UPDATE` takes that lock itself; for several seats, the `SELECT … FOR UPDATE` takes them first, in name order. When 500 requests race for A12, one takes the lock, updates the row and commits. The other 499 wait on that lock. Under Postgres's default isolation (READ COMMITTED), each of them re-reads the row once the winner commits, sees it is no longer `AVAILABLE`, updates 0 rows and gets a clean `409 seat_taken`. The `status = 'AVAILABLE'` condition is a second guard: even without the lock, the update can only take a seat that is still available. The schema adds a third layer. `seats` has one row per seat with exactly one status, and a `CHECK` requires a held or confirmed seat to point at a reservation, so the database itself refuses an inconsistent seat.

**Per-user limit.** The guarded upsert in step 2 is a single statement, and it locks that user's counter row. Ten parallel requests from one user therefore queue on one row, each seeing the count the previous one committed. Different users have different rows and never wait on each other.

**Multi-seat requests: all-or-nothing.** If a user asks for `["A12","A13"]` and only one is free, nothing is reserved and the response is `409 seat_taken`. The guarded update reports how many seats it took; anything short of the full request rolls back the whole transaction, including steps 1 and 2. A declined request therefore uses up neither the user's limit nor their idempotency key.

**Avoiding deadlock.** Every write path takes its locks in the same global order: the reservation row, then the user's counter row, then seats **sorted by name**. `ReserveCommand` sorts the seat list when it is built, so `["B2","B1"]` and `["B1","B2"]` both lock B1 first. Two requests can make each other wait, but never wait on each other in a cycle. Confirm, cancel and expiry follow the same order.

**Measured locally.** `burst.sh` fires 18,500 reserve requests at a fresh 1,000-seat show:
- **Hot-seat storm:** 5 hot seats × 500 users each. Exactly 5 winners; 2,495 × `409 seat_taken`.
- **Parallel retries:** 50 keys × 20 copies. 50 reservations; 950 replays.
- **Stampede:** 15,000 requests for random seats.
- **Totals:** zero 5xx and zero failed connections, no seat sold twice, and `available + held + confirmed == total` in every snapshot taken during the burst.

**Measured on the live service.** On the Render free instance (a fraction of one CPU), a 2,250-request burst (5 hot seats × 50 users, 50 keys × 20 retries, 1,000 stampede requests) passed every check with zero 5xx. Latency there is high (seconds per request at 300 in flight), because the instance is CPU-bound. Two changes made it hold up:
- **Fast JVM warm-up** (`-XX:TieredStopAtLevel=1`): on half a CPU, the first burst after a start went from 73 s to 18 s.
- **Queueing for database connections** for up to 5 minutes instead of 60 s (section 4), so an overloaded instance answers slowly rather than with errors.

`ReservationConcurrencyTest` proves the same properties against a real Postgres. When the `status = 'AVAILABLE'` guard is removed, that test fails with 100 out of 100 racers "winning" one seat.

## 2. Idempotency

- **Where the key lives:** in the `reservations` row itself, with `UNIQUE (user_id, idempotency_key)`. It is accepted as an `Idempotency-Key` header or an `idempotency_key` body field. The key is scoped per user, so two users who happen to choose the same key never collide, and one user can never see another's reservation through a shared key.
- **Exactly once:** the key is claimed by the first statement of the transaction. If two requests with the same key arrive together, the second one's insert blocks on the unique index until the first transaction ends:
  - **The first commits:** the second finds the existing row and becomes a replay.
  - **The first is declined (rolled back):** the second's insert succeeds and it is evaluated fresh. A declined request never consumes its key, so a client can retry after a decline.
- **A genuine retry** (same show, same seats in any order, same mode) returns the original reservation with `200` and `Idempotent-Replayed: true`. It is reported as of now, so a reservation cancelled since shows as `cancelled`. Returning `200` rather than a second `201` keeps "exactly one 201 per hot seat" true even when clients retry.
- **Same key, different request** returns `409 idempotency_key_reused`. The comparison uses the stored `show_id`, the sorted `seats` and the `mode`; there is no request hash.

## 3. Holds and expiry

There are both release models.

- **Confirm (default):** `POST /shows/{id}/reserve` confirms the seats immediately, as in the brief's example response.
- **Hold:** `"mode": "hold"` creates a time-boxed hold (`HOLD_TTL`, default 120s). `expires_at` is computed with the **database clock**, the same clock every expiry check uses, so app-server clock drift cannot matter.
- **Confirm a hold:** `POST /reservations/{id}/confirm` confirms the caller's own hold. A confirm after `expires_at` is declined even if the sweeper has not run yet.
- **Cancel:** `POST /reservations/{id}/cancel` releases the caller's own held or confirmed reservation. Another user's reservation returns `404`, so its existence is not leaked. Cancelling twice is harmless.
- **Expiry:** `HoldSweeper` runs every second and expires overdue holds in batches of 200 using `SELECT … FOR UPDATE SKIP LOCKED`. It never waits on a hold that a user is confirming or cancelling at that moment, and several instances can sweep at once without blocking each other.
- **Lifecycle rules:** the allowed moves live in one table, `ReservationTransition`: HELD → CONFIRMED, CANCELLED or EXPIRED; CONFIRMED → CANCELLED; the rest are final. Repeating a move is a no-op; anything else is `409 not_active`.
- **A release never frees someone else's seat.** Seats are returned to available only `WHERE reservation_id = <this reservation>`. If an old hold expired and the seat was resold, a later cancel of the old hold cannot touch it.
- **Limit quota:** each release gives the user back their seats in the same transaction, so they can book again.

## 4. Consistency vs availability under a partition

The service chooses **consistency**. There is one Postgres primary and it is the only source of truth: no seat state is cached in the app, and nothing is decided without it.

- **When the app cannot reach Postgres:**
  - **Reserve and cancel fail.** They return an error, never a guess. Selling a seat without the database could sell it twice, which is the one outcome that is never acceptable.
  - **Readiness fails closed.** `/actuator/health/readiness` uses its own short-lived connection with 2–3 second timeouts. It returns `503` within about 2 seconds, so the platform stops routing traffic. Liveness stays `200`, so the instance is not restarted in a loop over a problem a restart cannot fix.
  - **Saturation is not an outage.** A connection pool that is merely busy during a burst does not fail readiness, so a healthy instance is not pulled mid-stampede.
- **When no connection is free:** a request waits for a pooled connection for up to 5 minutes (`DB_CONNECTION_TIMEOUT_MS`).
  - **Under a burst,** this is what the pool is for: requests queue and then get their real answer, a 201 or a clean 409, instead of an error.
  - **If no connection frees up in time,** the response is `503` with `reason: "overloaded"` and a `Retry-After` header. Nothing has been decided or written at that point, so the client can safely retry with the same idempotency key.
  - **During a database outage,** the same wait applies, so in-flight requests hang for up to 5 minutes before the 503. Readiness takes the instance out of rotation within seconds, so new traffic stops arriving. The zero-5xx guarantee covers contention, not an outage.
- **Running more app instances** does not change this. They share the database, row locks and `SKIP LOCKED` work across instances, and no instance holds state the others need.

## 5. Observability: what would page me at 2am

**What's exposed**
- **Metrics** at `/actuator/prometheus` (Prometheus format), protected with HTTP Basic auth on the live service and scraped by Grafana Cloud every minute, so they're kept over time:
  - `reservations_confirmed_total`, `reservations_held_total`
  - `reservations_declined_total{reason=seat_taken|per_user_limit|idempotent_replay|idempotency_key_reused|unknown_seat}`
  - `reservations_released_total{reason=cancelled|expired}`
  - per-show gauges `seats_available`, `seats_held`, `seats_confirmed`, `seats_capacity`
  - `reservations_holds_overdue`
- **How the counters stay accurate:** they are driven by domain events delivered **after commit** (declines after rollback), so a counter never runs ahead of the database. `burst.sh` checks that the counter changes equal the responses exactly.
- **Logs:** structured JSON (ECS) on stdout, visible in Render's log viewer, and also shipped to Grafana Cloud Loki in the same region as the service. Grafana Cloud isn't public, so access is shared on request.
  - **Correlation:** every line carries a `request_id`, from `X-Request-Id` or generated, and echoed in the response and in every error body.
  - **Volume:** one access line per request, and one decision line per reservation outcome (`reserve succeeded` / `declined` with a reason / `replayed`, `hold confirmed`, `reservation cancelled`, `hold expired`).

**Pages**

| Alert | Why it matters |
|---|---|
| `reservations_holds_overdue > 0` for 2 minutes | The sweeper has stopped; expired seats are not coming back. The service looks healthy while silently wrong, so this is the alert I care about most. |
| Readiness failing | The database is unreachable; nothing can be sold. |
| Any 5xx in the access logs | Declines are 4xx by design, so a 5xx means something actually broke. A `503 overloaded` means requests waited 5 minutes for a database connection. |
| `hikaricp_connections_pending` high together with high reserve latency | The database or pool is the bottleneck; buyers are waiting. |

**Dashboards, not pages:** declines by reason (a `seat_taken` spike is just a busy on-sale), and the seat gauges, which add up to capacity by construction.

## 6. AI usage

I built this with Claude (Claude Code, in VS Code) as a pair programmer. It wrote most of the code. I directed the work, reviewed and questioned each piece, and made every commit myself. This write-up was also drafted with Claude, using examples from our conversations; I reviewed and edited it.

**Directed: what I decided and told Claude to do**
- **Platform and stack:** Spring Boot / Postgres. Java 21 for virtual threads. Render for hosting, Grafana Cloud for logs and metrics.
- **Product behaviour:** both release models (confirm plus TTL holds); all-or-nothing for multi-seat requests.
- **Data access:** jOOQ with code generated from the Flyway migration, instead of JPA or hand-written models; native Postgres enums.
- **Schema simplifications:** dropping the request hash (replays compare stored columns), dropping a seat-ordering column, renaming `label` to `seat_name`.
- **The refactor for readability:** I led it. I asked for the restructure, chose the patterns from the options Claude laid out, and decided the package structure.
- **Logging:** decision logs written directly in the service rather than through events.
- **Testing split:** functional rules in unit and integration tests; `burst.sh` kept to the load test, rewritten in plain bash until it read clearly, with every check printing its calculation.
- **Build gate:** unit tests run in the image build, so a broken rule stops a deploy.
- **Commits:** an incremental history, split by feature.

**Decided: choices Claude made that I reviewed and kept**
- **The locking design:** the three guarded statements, the global lock order, and `SKIP LOCKED` for the sweeper.
- **Metrics on after-commit events,** so counters never run ahead of the database. I questioned whether event-driven counting was normal or would be delayed before keeping it.
- **The readiness check** with its own connection: the built-in check took 60s to notice a dead database.
- **The `reservations_holds_overdue` alarm.**
- **The Testcontainers concurrency tests,** including the sabotage run that proves they can fail.

**What I caught or pushed back on**
- **Test-isolation bug:** shared user names across tests collided on idempotency keys.
- **False passes:** an early `burst.sh` let a failed check pass silently, which is why every check now shows its numbers.
- **Over-engineering:** I rejected event-driven logging, two convenience-only schema columns, and a Python burst script, and narrowed `burst.sh` to the load test.

**Token usage** (from the local Claude Code logs, totalled the way `ccusage monthly` does):

| Month | Model | Input | Output | Cache write | Cache read | Total |
|---|---|---|---|---|---|---|
| 2026-10 | claude-opus-5-5 | 756 | 479,382 | 1,908,379 | 122,305,266 | 124,693,783 |

Output is what Claude wrote: code, explanations and drafts. Almost all of the total is cached conversation context re-read on each turn.

## 7. What I'd do next

- **Fail fast during a database outage:** today an outage and a busy pool look the same, so requests wait the full 5 minutes either way. A circuit breaker driven by the readiness check would answer `503` at once while the database is down, and keep queueing only when it's merely busy. Also retry deadlock or serialization errors (none have been seen, but a retry is cheap insurance).
- **Real authentication, and limiting bots:** `/auth/token` is a demo issuer that will mint a token for any user id, so today one person could act as thousands of users and get around the per-user limit. In production:
  - Tokens come from an identity provider, tied to verified accounts (email or phone), so the per-user limit means one limit per real person.
  - A CAPTCHA or similar challenge when entering the waiting room, so scripts can't join the queue at scale.
  - Rate limits per account, device and IP, so one buyer can't flood the reserve endpoint.
  - Short-lived, signed entry tokens from the waiting room, required by the reserve endpoint, so bots can't skip the queue by calling the API directly.
- **Hot-show scaling:**
  - A message queue for rate limiting (Kafka or SQS), as a waiting room: buyers join the queue and are admitted at a rate the database can take, instead of every request hitting it at once. The seat decision itself stays a synchronous transaction; the queue only controls who gets to make a request and when.
  - Shed obvious losers and retries before they reach the database with a cache (e.g. Redis): sold seats and committed idempotency keys, written after commit and cleared when a seat is released. A cache hit answers with 409 `seat_taken` or the original reservation; a miss falls through to the database, which still makes every decision. Today every loser and retry still runs a transaction.
  - Isolate shows from each other: today every show shares one connection pool, so a single hot on-sale can starve every other show. Per-show pool limits, or routing hot shows to their own database, keep the rest of the catalogue responsive.
- **Scaling the read path:** during an on-sale, buyers refresh the seat map constantly. Serve `GET /shows/{id}` from a short-lived cache or a read replica, and push seat changes to clients (server-sent events or WebSockets) instead of letting them poll. Every seat decision still runs on the primary database.
