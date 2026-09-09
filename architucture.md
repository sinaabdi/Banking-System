## Database

`USER`
```
id                PK
first_name
last_name
username          unique
password_hash     -- BCrypt hash, via Spring Security's PasswordEncoder; never stored/logged raw
email             unique
status            (ACTIVE/DISABLED/DELETED)
role              (USER/ADMIN) -- see Authentication & Authorization below; promoted/demoted via
                  -- UserService.promoteToAdmin/demoteToUser (the seeded "system" user starts as ADMIN)
created_at
updated_at
```

`ACCOUNT`
```
id                PK
user_id           FK -> USER, not null -- even SYSTEM accounts have an owning user (a seeded "bank operations" user)
account_number    unique -- a DB sequence value with a Luhn check digit appended
account_type      (CHECKING/SAVINGS/SYSTEM)
currency
status            (ACTIVE/FROZEN/CLOSED)
version           -- optimistic lock; protects direct mutations to this row (freeze/close/activate) only.
                  -- does NOT protect the withdraw/transfer balance check - see Concurrency below.
created_at
updated_at
```

`TRANSACTION`
```
id                        PK
type                      (TRANSFER/DEPOSIT/WITHDRAWAL/FEE/REVERSAL) -- FEE is defined but not yet wired to any operation
status                    (PENDING/POSTED/FAILED/REVERSED)           -- FAILED is defined but no code path sets it yet
idempotency_key           unique, not null
reversed_transaction_id   FK -> TRANSACTION, nullable, self-referencing -- set on the reversal, pointing back at what it reverses
created_at
updated_at
```

`LEDGER_ENTRY`
```
id                 PK
transaction_id     FK -> TRANSACTION
account_id         FK -> ACCOUNT
direction          (CREDIT/DEBIT)
amount             -- Long, minor units (e.g. cents) to avoid floating-point rounding; always positive, direction carries the sign
currency
created_at         -- no updated_at, no setters: append-only. A mistake is corrected by posting a
                   -- new reversal, never by editing an existing entry.
```

Nothing in the schema ties the two `LEDGER_ENTRY` rows of one `TRANSACTION` to the same `amount`/
`currency` - every transaction type held them equal only by convention, until cross-currency
`transfer` (see [FX-Rate Service](#fx-rate-service) below) became the first to legitimately post a
debit and credit with different amounts *and* different currencies on the same transaction.

Balance is never stored - it's always derived as `sum(CREDIT) - sum(DEBIT)` over an account's ledger entries, so it can never drift out of sync with the transaction history that produced it.

## Services

`USER` (UserService)
```
createUser              -- hashes the password before storing; rejects duplicate username/email
updateUser               -- updates first name, last name, email (not username)
changePassword            -- verifies current password via PasswordEncoder.matches, hashes the new one
disableUser
enableUser
promoteToAdmin           -- grants ADMIN
demoteToUser             -- revokes ADMIN; rejects a caller trying to demote their own account
getUserById
getUserByEmail
getUserByUsername
getAllUsers
```

`ACCOUNT` (AccountService)
```
createAccount              -- generates the account number from a DB sequence + Luhn check digit
freezeAccount
closeAccount
activeAccount              -- reactivates a frozen/closed account
getBalance                 -- computed from LEDGER_ENTRY, not stored
getAccountByAccountId
getAccountByAccountNumber
getAccountsForUser
```

`TRANSACTION` (TransactionService)

No generic "create transaction" - each operation is its own method with its own validation:
```
deposit    -- credits the account, debits the SYSTEM account for that currency (money entering
           -- the ledger from outside the bank)
withdraw   -- debits the account, credits the SYSTEM account; rejects if the amount exceeds the
           -- current balance; locks the account row for the duration (see Concurrency)
transfer   -- debits the source, credits the destination, no SYSTEM account involved; both
           -- accounts are locked, always in a fixed order, to avoid deadlock; only the source's
           -- balance can ever reject a transfer. If the two accounts' currencies differ, the
           -- destination amount is converted via fx-service before locking - see
           -- FX-Rate Service below
reverse    -- posts a new REVERSAL transaction whose entries mirror the original's with direction
           -- flipped; only a POSTED, not-already-reversed, non-REVERSAL-type transaction is eligible
getTransactionById
getTransactionByIdempotencyKey
```

`deposit`/`withdraw`/`transfer`/`reverse` all take a client-supplied `idempotency_key` and check for an existing transaction with that key first - a retried request returns the original result instead of creating a duplicate.

`LEDGER_ENTRY`

No dedicated service - accessed directly via `LedgerEntryRepository` from `AccountService`/`TransactionService`:
```
computeBalanceForAccount  -- the balance-derivation query
findByTransactionId       -- used by reverse to find the entries to mirror
```

`AUTH` (AuthenticationService)
```
login    -- authenticates username/password via AuthenticationManager (which delegates to
         -- AppUserDetailsService + PasswordEncoder under the hood), then issues a JWT
```

## Authentication & Authorization

Stateless JWT auth - no server-side session store. A client logs in once, gets a signed token back,
and sends it as `Authorization: Bearer <token>` on every later request.

**How a request gets authenticated:**
1. `POST /api/auth/login` (`AuthenticationController` -> `AuthenticationService`) verifies the
   username/password via Spring Security's `AuthenticationManager`, which under the hood calls
   `AppUserDetailsService` (loads the `USER` row, throws `UsernameNotFoundException` if missing)
   and checks the password against the stored BCrypt hash. A `DISABLED` user is rejected here too -
   see `AppUserPrincipal.isEnabled()`.
2. On success, `JwtService` signs a JWT (HMAC, key from `jwt.secret`) containing the username and
   an expiry (`jwt.expiration-ms`). The token can't be revoked early - a leaked token is only as
   dangerous as however long is left until it expires, which is why the expiry is kept short.
3. Every subsequent request passes through `JwtAuthenticationFilter` (runs once per request, before
   the rest of the chain). It validates the token's signature and expiry, loads the corresponding
   `AppUserPrincipal` again via `AppUserDetailsService`, and populates `SecurityContext` - or, if the
   token is missing/invalid, just leaves the request unauthenticated and lets `SecurityConfig`'s
   `authorizeHttpRequests` rules decide whether that's allowed for the endpoint being hit.

**Roles**: `USER`/`ADMIN` on `USER.role`, exposed to Spring Security as a `ROLE_USER`/`ROLE_ADMIN`
authority (`AppUserPrincipal.getAuthorities()`). Bank-operations actions - freezing/closing/
activating an account, reversing a transaction, disabling/enabling a user, listing all users - are
`@PreAuthorize("hasRole('ADMIN')")`-gated; an `ADMIN` can also act on any other user's resources.
Promotion itself (`UserService.promoteToAdmin`/`demoteToUser`) is admin-only too, with one extra
guard: an admin can't demote their own account, so there's always at least one ADMIN left to grant
roles - self-demotion throws `IllegalArgumentException` (400) rather than locking the caller out.

**Ownership**: everywhere else, a caller can only act on their own resources. Two different
mechanisms enforce this, depending on whether the identity being checked is already present in the
request or has to be looked up:
- Where the request already names a user id directly (a path variable like `UserController`'s
  `/{id}`, or a request-body field like `AccountController.createAccount`'s `userId`),
  `@PreAuthorize("#id == authentication.principal.id or hasRole('ADMIN')")`-style SpEL checks it
  against the authenticated caller with no database lookup at all.
- Where only an account/entity id is known (`AccountService.getAccountByAccountId`,
  `TransactionService.deposit`/`withdraw`/`transfer`), the row has to be loaded first to discover
  its owning user, then checked in the service layer (`checkAccountOwnershipOrThrow`) - an id alone
  carries no ownership information until the row behind it is read. A mismatch throws Spring
  Security's `AccessDeniedException`. `transfer` only checks the *source* account - the destination
  can belong to anyone, the same way paying another person works in any real bank.

## Concurrency

Two different locks protect two different things:

- **`@Version` (optimistic)** on `ACCOUNT` protects direct mutations to that row - `freeze`/`close`/`activate`.
- **A pessimistic row lock** (`findByIdForUpdate`, `SELECT ... FOR UPDATE`) protects the `withdraw`/`transfer` balance check. `@Version` alone can't do this job: balance is derived from `LEDGER_ENTRY`, not stored on `ACCOUNT`, so nothing ever writes to the account row during a withdrawal for `@Version` to catch. The row lock is acquired *before* computing the balance and held until the transaction commits, so a second concurrent withdrawal on the same account has to wait its turn rather than reading the same stale balance and overdrawing it.
- `transfer` locks **both** accounts, always in ascending id order regardless of which is source and which is destination - otherwise two transfers between the same two accounts in opposite directions could deadlock, each holding one lock while waiting on the other's.

A concurrency test (`TransactionServiceConcurrencyTest`) fires many simultaneous withdrawals at the same account against a real Postgres instance and asserts the final balance is exactly what it should be - this is the one thing the Mockito unit tests can't prove, since there's no real locking to verify without a real database and real threads.

## Error handling

A single `GlobalExceptionHandler` maps exceptions to HTTP responses: `NoSuchElementException` -> 404, `IllegalArgumentException` -> 400, `AuthenticationException` -> 401 (wrong credentials, unknown username, or a disabled account), `AccessDeniedException` -> 403 (a valid, authenticated caller who isn't the owner or an admin - see Authentication & Authorization above), anything else -> 500 (logged server-side with full detail; the caller only sees a generic message).

## API docs

`springdoc-openapi` is wired in - Swagger UI at `/swagger-ui/index.html`, raw OpenAPI spec at `/v3/api-docs`.

## Containerization

Each service has its own `Dockerfile`, colocated in its own directory (`banking/Dockerfile`,
`notification-service/Dockerfile`, `fraud-service/Dockerfile`, `fx-service/Dockerfile`) with its own
`.dockerignore`, not one shared root-level Dockerfile per service distinguished by suffix. This scales
better as more services get added, and it means each service's `docker-compose.yml` `build.context`
points at that service's own directory, so its Dockerfile's `COPY` paths are relative to itself rather
than the repo root.

`banking/Dockerfile` is a two-stage build:
1. **`builder`** (`eclipse-temurin:25-jdk-*`) - copies the Gradle wrapper and `build.gradle` first and
   resolves dependencies before copying `src/`, so editing source code doesn't invalidate the
   dependency-download layer on rebuild. Then runs `./gradlew bootJar`.
2. **runtime** (`eclipse-temurin:25-jre-*`) - copies only the built jar out of `builder` via
   `COPY --from=builder`. No JDK, no Gradle, no source ever reaches this image - just a JRE and one
   jar.

`notification-service/Dockerfile`, `fraud-service/Dockerfile`, and `fx-service/Dockerfile` all follow
the same shape with Go's toolchain instead: a `golang:*-alpine` builder stage runs `go build`, and a
bare `alpine` runtime stage copies out just the compiled binary - no Go toolchain or source in the
final image either. (A real bug caught while writing `fx-service/Dockerfile`: its runtime stage
originally `COPY --from=builder`'d `/app/fraud` - a copy-paste leftover from `fraud-service/Dockerfile`
- while the builder stage actually built `/app/fx`. Docker failed the build outright with `"/app/fraud":
not found`, proven directly rather than assumed, and fixed by copying the binary the builder stage
actually produces.)

`docker-compose.yml` runs `core`, `notification`, `fraud`, `fx`, `postgres`, and `rabbitmq` together,
all on a `backend` bridge network so Compose's internal DNS resolves each by service name - unlike the
host workflow, `localhost` inside any one container means that container itself, not any of the
others. `core`, `notification`, `fraud`, and `fx` only `depends_on` the services they actually need to
be up first - `notification` and `fraud` each just need RabbitMQ, not each other and not `core`, since
a fanout consumer's only real startup dependency is the broker itself; `fx` needs nothing at all, not
even RabbitMQ, since it never touches the transaction-events exchange (see
[FX-Rate Service](#fx-rate-service)) - chaining any of them onto `core` would reintroduce the exact
coupling this design exists to remove.

This is why `application.properties`'s datasource/RabbitMQ settings are `${DB_URL:localhost}`-style
placeholders (and, on the Go side, `os.Getenv` with a hardcoded fallback, since Go has no built-in
placeholder syntax) rather than anything hardcoded: the defaults keep `./gradlew bootRun`/
`go run main.go` working unchanged on the host, while each container's `env_file: .env` overrides them
to point at the right service names instead.

`postgres` and `rabbitmq` each have their own healthcheck (`pg_isready`, `rabbitmq-diagnostics ping`),
and `core`/`notification` declare `depends_on: <service>: condition: service_healthy` rather than a
bare `depends_on` - a container starting isn't the same moment as the thing inside it actually being
ready to accept connections (especially on a first run), and neither Spring Boot nor the Go program
retries a failed initial connection.

## Kubernetes Deployment

`k8s/` holds the manifests for a `banking` namespace containing:

- **`banking-config`/`banking-secret`** - one shared `ConfigMap`/`Secret` for both pods below, since
  Postgres and the app need the same values from opposite sides of the same connection. Each side
  maps a key to its own env var name via `valueFrom.configMapKeyRef`/`secretKeyRef` - e.g. the
  Postgres pod's `POSTGRES_DB` env var reads the `ConfigMap`'s `DB_NAME` key, the same key the app
  reads as `DB_NAME` directly. `secret.yaml` is gitignored (base64 is encoding, not encryption -
  committing it would be no different from committing `.env`); only a shared `config.yaml` is
  tracked.
- **`postgres`** - a single-replica `Deployment` (a real DB doesn't need `StatefulSet`'s
  ordered-scaling guarantees at this scale) backed by a `PersistentVolumeClaim` (minikube's default
  storage class auto-provisions it, tied to whichever node the provisioner runs on - fine for a
  learning cluster, not a production storage story), fronted by a `Service` **named `postgres`**.
  That exact name is what makes `DB_URL=postgres` (the same value already used for Docker Compose)
  resolve correctly via Kubernetes' internal DNS, with zero app-side changes from the Docker stage.
- **`core`** - the app `Deployment`, 2 replicas (scheduled across the two worker nodes, demonstrating
  the cluster actually load-balancing rather than just running single-node), exposed via a `NodePort`
  `Service`. Its `env` also carries `RABBITMQ_HOST`/`RABBITMQ_PORT`/`RABBITMQ_USERNAME`/
  `FANOUT_EXCHANGE_NAME`/`FX_SERVICE_URL` (from `banking-config`) and `RABBITMQ_PASSWORD` (from
  `banking-secret`). Now pulls a real `ghcr.io/sinaabdi/banking-core:<git-sha>` image (see
  [CI/CD Pipeline](#cicd-pipeline-github-actions) below) rather than a locally-loaded one - see that
  section for how `imagePullPolicy: Never` and manual `minikube image load` were retired in favor of
  this, and are kept only as a manual local-testing fallback.
- **`fx`** - **`replicas: 1`, deliberately**, for yet another reason than `rabbitmq`'s or `fraud`'s:
  each pod keeps its own independent, in-memory, independently-drifting rate table with no shared
  store between replicas. With 2+ replicas, two clients hitting `GET /rate` at the same instant could
  get two genuinely different answers depending purely on which pod the `Service` happened to route
  to - not because time passed, but because the pods' drift diverged independently. A real fix (a
  shared store like Redis, one process owning the drift) is deferred - `replicas: 1` sidesteps the
  inconsistency entirely for now, and a single Go HTTP server doing a mutex-guarded map lookup handles
  far more throughput than this project will ever generate.
- **`rabbitmq`** - **`replicas: 1`, deliberately, not 2.** Unlike `core`/`notification`, RabbitMQ isn't
  stateless - running 2 replicas via a plain `Deployment` with no clustering configuration would give
  two completely independent, unconnected broker instances, each with their own separate exchanges and
  queues. The `Service` in front would load-balance each new connection randomly between them, so
  `core`'s publish and `notification`'s consume could easily land on *different* instances - a message
  would then just silently never arrive, with no error anywhere. Real RabbitMQ clustering needs a
  `StatefulSet`, stable per-pod identity, and a peer-discovery mechanism; none of that exists here, so
  a single replica is the only correct choice at this scale - same reasoning as `postgres`. Backed by
  its own `PersistentVolumeClaim` mounted at `/var/lib/rabbitmq/` - without it, a pod restart would wipe
  every durable queue and anything waiting in it, defeating the entire point of the durability work in
  [Notification Service](#notification-service) below.
- **`notification`** - 2 replicas, `env` sourced the same way as `core`'s RabbitMQ settings, plus
  `NOTIFICATION_QUEUE_NAME`. No `PersistentVolumeClaim` - it's stateless (an in-memory store), same as
  `core`.
- **`fraud`** - **`replicas: 1`, deliberately, for a different reason than `rabbitmq`'s.** It isn't a
  broker-clustering problem here - it's that `fraud`'s velocity tracker and flag store are both plain
  in-process memory with no shared backing store. If it ran with 2 replicas, both would bind the exact
  same queue name (`fraud.transaction-events`), making them competing consumers of one queue - RabbitMQ
  would split messages between the two pods rather than give each a full copy, so a single user's rapid
  transactions could land on two different pods, each holding an incomplete view of that user's recent
  activity, silently breaking the velocity rule. `env` sourced the same way as `notification`'s, plus
  the three scoring thresholds (`LARGE_AMOUNT_THRESHOLD`/`VELOCITY_WINDOW_SECONDS`/
  `VELOCITY_MAX_COUNT`).

**Health probes**: added `spring-boot-starter-actuator` with
`management.endpoint.health.probes.enabled=true`, which exposes two Kubernetes-specific endpoints -
`/actuator/health/liveness` ("is this instance fundamentally broken, kill and restart it") and
`/actuator/health/readiness` ("can this instance currently serve traffic", reflecting real dependency
state like Postgres reachability, not just "the JVM didn't crash"). Both are permitted in
`SecurityConfig` alongside `/api/auth/**`, since the kubelet calling them has no JWT to send.

**Image distribution to a multi-node cluster (manual fallback only, superseded by the pipeline below)**:
`eval $(minikube docker-env)` only points at one node's Docker daemon, insufficient for a multi-node
cluster - `minikube image load` is the tool that actually loads a locally-built image onto every node.
Everything in this subsection was the *only* way images reached the cluster before
[CI/CD Pipeline](#cicd-pipeline-github-actions) and [GitOps Deployment](#gitops-deployment-argocd)
existed; it's kept working deliberately, purely for quick manual testing without going through the
pipeline - the real deployment path no longer touches any of it.

**Host access on the `docker` driver**: unlike a Linux-native setup, `NodePort`/node-IP access isn't
directly routable from the host on Windows/WSL with the `docker` driver - `minikube service <name>
--url` opens an active tunnel process (must stay running) rather than just printing a static URL.
This is a property of the driver/host combination, not something fixed by the cluster configuration.

**`imagePullPolicy: Never` doesn't guarantee a rebuild actually takes effect**: `minikube image load`
can silently fail to refresh an already-loaded tag while a pod is still running on it. The reliable
sequence is scale the deployment to 0 first (so nothing holds the old image), remove it on each node
(`minikube ssh -n <node> -- docker rmi <image>:local`), reload, then scale back up.

**A `ConfigMap` edit only takes effect once re-applied** - encountered directly while wiring up `fraud`:
adding new keys to `k8s/config.yaml` and saving the file changes nothing in the cluster on its own.
A pod referencing a key that isn't in the *live* `ConfigMap` yet fails immediately with
`CreateContainerConfigError` (`kubectl describe pod` names the missing key directly), even though the
key is sitting right there in the file. `kubectl apply -f k8s/config.yaml` is what actually updates
what the cluster sees; the deployment then needs a pod-template change (or a manual
`kubectl rollout restart`) to pick up the new values, since editing a `ConfigMap` alone doesn't restart
the pods that reference it.

**A brand-new RabbitMQ used to be able to drop its very first message** - encountered directly while
first deploying this, and since fixed at the source rather than left as a standing caveat: the exchange
used to only be created lazily, on `core`'s first publish, so on a truly fresh broker a consumer's
queue bind could race it. See [Notification Service](#notification-service) below for how `core` now
declares the exchange eagerly at startup instead, closing this permanently rather than just documenting
it as a one-time risk.

## Notification Service

The first genuinely polyglot piece: [notification-service/](notification-service/) is a standalone Go
service that reacts to every posted transaction, over **RabbitMQ**. This started as a direct
synchronous HTTP call and was deliberately migrated to a message broker specifically to support more
than one independent consumer of the same event without `core` ever needing to know how many consumers
exist - see [Fraud-Scoring Service](#fraud-scoring-service) below for the second one, added with zero
changes to this service or to `core`'s publish path.

**Event flow (Java side)**: `TransactionService`'s `deposit`/`withdraw`/`transfer`/`reverse` each
publish a `TransactionPostedEvent` (via `ApplicationEventPublisher`) immediately after
`transaction.postedTransaction()` - for `reverse`, this is the newly-posted *reversal* transaction,
not the original being marked `REVERSED`. `TransactionEventPublisher` (`events/` package, renamed from
`TransactionNotificationListener` once its job stopped being "call notification-service specifically"
and became "publish a domain event for whoever's listening") consumes it via
`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`, not a plain `@EventListener`:

- **Why `AFTER_COMMIT` specifically**: publishing inside the same `@Transactional` method and reacting
  to it immediately would mean the broker call happens *while* the DB transaction (and, for
  `withdraw`/`transfer`, its row locks) is still open; and if the transaction then rolled back for an
  unrelated reason, an event would already have gone out for data that never actually committed.
  `AFTER_COMMIT` guarantees the listener only runs once the change is durably saved.
- **Fanout exchange, not direct/topic**: `RabbitMQConfig` declares a durable `FanoutExchange` named
  `banking.transaction-events`. A fanout exchange forwards every published message to *every* queue
  bound to it, ignoring routing keys entirely - exactly the shape needed for "N independent consumers,
  each wants their own full copy of every event," as opposed to routing different message *categories*
  to different places (what direct/topic exchanges are for). If `notification` and `fraud` both
  consumed from the *same* queue instead, RabbitMQ would split messages between them (competing
  consumers - each message goes to exactly one consumer, right for scaling one service horizontally,
  wrong for two different services that each need every message).
- **Eager exchange declaration, not lazy**: Spring AMQP's `RabbitAdmin` auto-declares every registered
  `Exchange`/`Queue`/`Binding` bean, but only once something opens a real connection to the broker -
  by default that's lazy, triggered by the first actual `RabbitTemplate.convertAndSend(...)` call, not
  at startup. On a completely fresh broker, that used to leave a real window where a consumer's queue
  bind could race `core`'s first-ever publish and lose. Closed by declaring `RabbitAdmin` explicitly
  (this Spring Boot version doesn't auto-configure one) and adding an `ApplicationRunner` bean that
  calls `rabbitAdmin.initialize()` - an `ApplicationRunner` only runs once every singleton bean in the
  context (including `fanoutExchange()`) already exists, and always before the app finishes starting,
  so the exchange is guaranteed to exist before any transaction can possibly be posted, on every
  startup, not just after the first real publish on a given broker. (An earlier attempt called
  `rabbitAdmin.initialize()` directly from `RabbitMQConfig`'s constructor instead - that doesn't work,
  because `fanoutExchange()` itself isn't registered in the context yet at that point: a
  `@Configuration` class's own instance has to be fully constructed before Spring can call its `@Bean`
  factory methods to produce their beans.)
- **Why the publish is still fire-and-forget**: an event is a side effect, not part of the banking
  domain's correctness - `RabbitTemplate.convertAndSend(...)` is wrapped in try/catch that logs and
  swallows any failure rather than rethrowing, same guarantee the old direct HTTP call had, just
  protecting against a broker being unreachable instead of an HTTP endpoint. A deposit succeeds or
  fails on its own merits regardless of RabbitMQ's availability.
- **JSON over AMQP isn't automatic**: unlike `RestClient`, Spring AMQP's default `RabbitTemplate` only
  serializes `String`/`byte[]`/`Serializable` payloads - a record like `TransactionEventPayload` isn't any
  of those and gets rejected at send time with a clear error, not silently mishandled. Fixed by adding
  a `Jackson2JsonMessageConverter`/`JacksonJsonMessageConverter` (the latter is this project's Spring
  AMQP version's actual class name - the "2" suffix was dropped once Jackson 1.x support was removed)
  bean; Spring Boot auto-wires it into the `RabbitTemplate` it builds once exactly one
  `MessageConverter` bean exists.
- **Field-name bridging**: the outgoing payload is a separate `TransactionEventPayload` record, not the
  event itself reused - Go's struct expects `snake_case` keys (`transaction_id`, `account_id`, etc.),
  so every mismatched field gets `@JsonProperty` on that DTO rather than on the event (the enum fields
  `type`/`status` need no annotation - Jackson serializes an enum as its `name()` by default).
- **What the event actually carries**: beyond `transactionId`/`type`/`status`, `TransactionPostedEvent`
  (and the wire payload built from it) carries `amount`, `currency`, `accountId`, `userId`, and nullable
  `counterpartyAccountId`/`counterpartyUserId` - added specifically so a fanout consumer can do
  anything beyond "log that something happened" without needing its own database. Since it's a fanout
  exchange, one published body already reaches every consumer, so enriching the one shared event was
  the right move rather than standing up a second, consumer-specific message.
  **Resolution rule**: every transaction has exactly two `LedgerEntry` rows (one `DEBIT`, one `CREDIT`).
  Whichever of those two *aren't* a `SYSTEM`-type account are the real parties. If only one side is
  non-system (deposits, withdrawals, and their reversals), that account is `accountId` and there's no
  counterparty. If both sides are non-system (transfers and transfer reversals), the `DEBIT` side is
  `accountId` - "whose funds decreased in *this* transaction" - and the `CREDIT` side is
  `counterpartyAccountId`. One private helper in `TransactionService`, fed a transaction and its ledger
  entries, implements this once and covers all four transaction types plus reversals of each, rather
  than branching on `TransactionType` at every one of the four publish call sites. Filtering on
  `AccountType.SYSTEM` specifically (rather than, say, direction alone) matters more than it looks: the
  seeded system account belongs to a real user (`system`, seeded as `ADMIN`), so a rule that didn't
  filter it out could leak that user's id into `userId` and quietly merge every deposit/withdrawal in
  the whole bank into one velocity bucket.

**The Go service itself**: its first real third-party dependency (`github.com/rabbitmq/amqp091-go`),
replacing the stdlib-only approach used until this stage. On startup it connects, declares its own
durable queue (`notification.transaction-events`), and binds it to the shared fanout exchange - then
consumes in a dedicated goroutine, running concurrently with `http.ListenAndServe` (which blocks
forever, so the consumer has to be a separate goroutine to run at all). Notifications are stored in the
same package-level slice guarded by a `sync.Mutex` as before; `GET /notifications` (still there) lists
everything received. `POST /notifications` is gone - messages arrive via the queue now, not an HTTP
push.

- **Manual ack, not auto-ack**: `ch.Consume(..., autoAck=false, ...)`, with `msg.Ack(false)` called only
  after a message is successfully stored. With auto-ack, RabbitMQ considers a message resolved the
  instant it's delivered - if the consumer then crashed while handling it, the message would just be
  gone. Manual ack means an unacknowledged message (consumer crashed, or never called `Ack`) goes back
  to the queue for redelivery instead. (A real, encountered bug from getting this backwards:
  `autoAck=true` *combined with* still calling `msg.Ack()` afterward is actually a protocol violation -
  acking an already-auto-acked delivery gets the whole channel force-closed by the broker, silently
  killing the consumer with no visible error after exactly one message.)
- **Durability**: the exchange, the queue, and each published message are all separately marked
  durable/persistent - all three have to agree for anything to survive a RabbitMQ restart. This is the
  concrete improvement over the old HTTP design: previously, if `notification-service` was down, an
  event was lost forever (a logged warning, nothing else); now it waits safely in the queue until a
  consumer is available - proven directly by stopping the Go service, depositing, confirming the
  deposit still succeeds, then restarting the service and watching it drain the backlog.

**Config**: `RABBITMQ_HOST`/`RABBITMQ_PORT`/`RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD` (Java:
`application.properties`; Go: `os.Getenv` with hardcoded fallbacks, since Go has no built-in
placeholder syntax) plus `FANOUT_EXCHANGE_NAME`/`NOTIFICATION_QUEUE_NAME`, all defaulting to
`localhost`/dev credentials for the host workflow and overridden via `.env`/Kubernetes
`ConfigMap`/`Secret` elsewhere - both `docker-compose.yml` and `k8s/` now fully cover this service.

## Fraud-Scoring Service

[fraud-service/](fraud-service/) is the second consumer bound to the shared `banking.transaction-events`
exchange - its own durable queue (`fraud.transaction-events`), completely independent of
`notification-service`'s. It's purely event-driven, with no database of its own: everything it needs to
score a transaction is already denormalized onto the shared event (see
[Notification Service](#notification-service) above for exactly what that event carries and why), which
is the whole payoff of enriching one shared message instead of giving each consumer a bespoke one.

**Two scoring rules, both real logic rather than placeholders:**
- **Large-amount threshold**: `amount >= LARGE_AMOUNT_THRESHOLD` (env-configured, minor units) flags
  `"large_amount"`. Still currency-naive - a $10,000 threshold and a €10,000 one are the same raw
  number - even though [FX-Rate Service](#fx-rate-service) below now gives `core` a real conversion
  rate to call on. `fraud-service` never sees it: the shared event still carries only the raw amount
  and currency code, with no rate attached, so this remains a deliberate simplification to revisit,
  not something FX-Rate Service happened to fix as a side effect.
- **Velocity, keyed by `userID` rather than `accountID`**: a `VelocityTracker` type holds
  `map[int][]time.Time` behind its own `sync.Mutex`. On every event, for that user's slot: prune
  timestamps older than `VELOCITY_WINDOW_SECONDS`, append "now", and if what's left exceeds
  `VELOCITY_MAX_COUNT`, flag `"high_velocity"`. Keying by user rather than account is deliberate - it's
  what catches someone spreading rapid activity across *several* accounts they own, the actual reason
  `userId`/`counterpartyUserId` were worth adding to the shared event at all, not just the account ids.
  A transaction can trigger both rules at once; a flag records every reason that fired, not just the
  first.

**A real correctness bug, encountered and fixed while building this**: the fraud-side `Transaction`
struct's `ID` field is an internal, per-process sequence number (`t.ID = len(transactionQueue) + 1` -
"the Nth event this process has ever received"), separate from `TransactionID`, the real id decoded off
the wire message. An early version of the scoring code built each `RiskFlag` with `TransactionID: t.ID`
instead of `t.TransactionID` - it looked correct in testing because the very first event a fresh process
receives happens to get internal id 1, coincidentally matching a low real transaction id, and only
diverged visibly once the process had handled more than one event. Caught by deliberately sending a
second transaction and checking the flag's `transaction_id` against the real one from `core`'s own
response, not by code review alone - exactly the kind of bug that "looks right" until you check it
against a second data point.

`RiskFlag` (`ID`, `TransactionID`, `AccountID`, `UserID`, `Reasons []string`, `FlaggedAt`) is stored the
same way `notification-service` stores its notifications - a package-level slice guarded by a
`sync.Mutex` - and `GET /flags` (`:9091`) lists everything flagged so far.

**Config**: the same RabbitMQ connection vars as `notification-service`, plus its own
`FRAUD_QUEUE_NAME`, and the three scoring thresholds
(`LARGE_AMOUNT_THRESHOLD`/`VELOCITY_WINDOW_SECONDS`/`VELOCITY_MAX_COUNT`) - same `os.Getenv`-with-
fallback pattern throughout, and both `docker-compose.yml` and `k8s/` fully cover this service. See
[Kubernetes Deployment](#kubernetes-deployment) above for why this service specifically needs
`replicas: 1` - a different reason than `rabbitmq`'s, but just as real a constraint.

## FX-Rate Service

[fx-service/](fx-service/) is the third and last of the originally-planned Go services, and the first
one that isn't a RabbitMQ consumer at all - it has no queue, no exchange binding, and no dependency on
the broker or on `core`'s startup. It exists to unblock a real feature: `transfer` used to hard-reject
any currency mismatch between the two accounts outright, with no way to actually move money between,
say, a USD account and a EUR account. This is what closes that gap.

**Why synchronous HTTP, not RabbitMQ - a deliberate exception, not a regression.**
`notification-service`/`fraud-service` are fire-and-forget because `core` doesn't need their answer to
proceed - that's exactly why async fits. An FX rate is different in kind: `core` cannot correctly
compute how much the destination account should receive without it, so this is a request/response data
dependency, not a side-effect notification. `FxRateClient` (`services/`) wraps a single `RestClient`
call to `fx-service`'s `GET /rate`; unlike the notification/fraud publish path, a failed call is *not*
caught and swallowed - it propagates uncaught through `GlobalExceptionHandler`'s catch-all (500),
because a transfer that silently skipped conversion would be a correctness bug, not a missed
notification.

**Simulated rates, not a real external FX API** - confirmed as the intended design, not a shortcut: one
base table of "USD-per-unit" rates for the currencies already seeded in
`V3__seed_system_account.sql` (EUR/GBP/JPY/CAD/AUD/CHF), with a background goroutine nudging each one
by a small bounded random drift (±0.5%) every few seconds, guarded by a `sync.Mutex` - same "map +
mutex" shape as `fraud-service`'s velocity tracker, just protecting a background-writer/HTTP-reader
relationship instead of concurrent event writers. A rate between any two non-USD currencies is derived
by triangulating through USD (`rate(A->B) = rate(B)/rate(A)`) rather than maintaining an N² pairwise
table - USD itself is pinned and never drifts, since it's the fixed reference point everything else is
quoted against. `GET /rate?from=X&to=Y` returns the derived rate; `GET /rates` lists the full live
table, the easiest way to watch the drift happen over repeated calls.

**A real concurrency bug, encountered and fixed while building this**: the drift goroutine's
`time.Sleep` was originally placed *inside* the per-currency loop, with the mutex held for the entire
outer loop around it - meaning the lock stayed held for the whole multi-second sleep, cycle after
cycle, with almost no window for an HTTP handler to ever acquire it. Proven directly with a real
`curl` request against the built binary: it hung for the full 20-second client timeout with no
response. Fixed by moving `time.Sleep` before `mu.Lock()` and outside the inner loop, verified by the
same request completing in single-digit milliseconds afterward. A related, smaller bug from the same
stage: USD was drifting like every other currency at first (empirically caught by sampling `/rates`
twice and watching USD's own value move), when it's supposed to be the fixed anchor - fixed with an
explicit `continue` skipping USD inside the drift loop.

**Go tests** (`main_test.go`, the first in this project) cover `triangulate` (pure division, tested via
exact `assert.Equal` - safe here specifically because the test's expected value is computed with the
identical floating-point expression the function itself uses, so it's bit-for-bit identical rather than
just numerically close) and `applyDrift` (inherently random, so tested via a bounds check across
thousands of calls on a fixed input instead of exact equality). The bounds test itself went through two
real bugs before it caught anything: a `for rate := 0.01; rate < 1.0; rate++` loop where `rate++` on a
`float64` adds `1.0`, not a small step - so the loop body ran exactly once instead of "many times"; and
a boolean condition (`!(got <= upperBound) && !(got >= lowerBound)`) that used `&&` where it needed
`||` - `got` can never be simultaneously above the upper bound *and* below the lower bound, so the
assertion was unreachable and the test could not have failed no matter how broken `applyDrift` was.
Proven by temporarily widening `applyDrift`'s bound to ±5% and confirming the test only started failing
once both bugs were fixed.

**Java side - lock-hold-time drives the ordering in `transfer`.** Fetching the rate happens *before*
the pessimistic row locks are acquired, not after: `transfer` already holds both accounts' row locks
for its full duration, and a network call to another service while holding them would extend that
hold time unnecessarily - worse, a hung `fx-service` could tie up both accounts indefinitely. So
`transfer` reads both accounts unlocked first (just to learn their currencies), decides via
`resolveTransferAmounts` whether conversion is needed, and only *then* proceeds into the existing
lock-acquire/ownership/balance-check flow - unchanged from before this stage, now with the destination
amount already in hand. Same-currency transfers skip the `FxRateClient` call entirely, so the common
path pays no added latency or dependency.

`resolveTransferAmounts` (private helper + `FxDetails` record: `sourceAmount`/`sourceCurrency`/
`destinationAmount`/`destinationCurrency`) does the actual conversion math in `BigDecimal`, not `double`
- money multiplied by a rate has to round to an exact integer minor-unit amount, and floating point
risks silent off-by-one-cent drift. Rounds half-up to the nearest minor unit via
`setScale(0, RoundingMode.HALF_UP)`, then converts back to the `Long` `LedgerEntry` expects via
`longValueExact()` specifically, not `longValue()` - proven directly with a synthetic large-amount
repro (a plausible USD amount converted at a JPY-scale rate): `longValue()` silently truncated to a
completely different, wrong `Long` with no error at all, while `longValueExact()` correctly threw
`ArithmeticException`. For money, a loud failure on an amount too large to represent is far safer than
a silent wraparound into a nonsense value.

`checkCurrencyMatchForTransferOrElseThrow` relaxed from "both accounts must match the request currency"
to "only the source account must" - `TransferRequest`'s existing `amount`/`currency` fields are
reinterpreted as the *source* side (matching how a real transfer works: you specify what leaves your
account), with the destination now allowed to differ. The credit `LedgerEntry` uses the resolved
*destination* amount and currency, not the request's - a real bug caught partway through this stage,
where the credit entry briefly paired the correctly-converted amount with the source's currency string
still attached, which would have silently mislabeled the destination account's own ledger entries.

**`TransactionResponse` gains four new nullable fields** - `sourceAmount`/`sourceCurrency`/
`destinationAmount`/`destinationCurrency` - populated for transfers via a `fromTransfer` factory
overload, `null` for every other transaction type via the original `from`. The idempotency-replay path
inside `transfer` itself needed the same treatment: a retried request for an already-posted transfer
now looks up that transaction's two `LedgerEntry` rows and rebuilds the response from the persisted
`DEBIT`/`CREDIT` amounts and currencies, rather than falling back to the plain `from` (which would have
silently dropped the conversion details on a retry, even though the original response had shown them).

**Config**: `fx.service.url` (Java, `${FX_SERVICE_URL:http://localhost:9092}` - same single-full-URL
pattern as `notification.service.url`, not a host+port split, since nothing here needs to compose a URL
from separate parts). `fx-service` itself takes no configuration at all - no database, no RabbitMQ, no
external dependency of any kind.

**Containerization/Kubernetes wiring**, originally deferred here, was completed in a follow-on stage -
`fx-service` now has its own `Dockerfile`, `docker-compose.yml` entry, and `k8s/` manifests
(`fx-deployment.yaml`/`fx-service.yaml`), fully covered the same as `notification-service`/
`fraud-service`. See [Containerization](#containerization) and [Kubernetes Deployment](#kubernetes-deployment)
above.

## CI/CD Pipeline (GitHub Actions)

[.github/workflows/ci.yaml](.github/workflows/ci.yaml) automates what used to be an entirely manual
sequence: `docker build` -> `minikube image load` -> (if replacing a running image) scale-to-0 ->
remove the old image on every node -> reload -> scale back up -> `kubectl apply -f k8s/`. Four jobs:

- **`java-test`**: `./gradlew build` (which runs `check`/`test` internally) against **real Postgres and
  RabbitMQ containers** via GitHub Actions' `services:` block - not mocks, for the same reason
  `TransactionServiceConcurrencyTest` needs a real database locally (see
  [Concurrency](#concurrency)): the pessimistic-locking guarantee and full-Spring-context tests
  (`BankingApplicationTests`) can't be verified any other way.
  - **A real networking bug caught here**: the job's `env` originally pointed `DB_URL`/`RABBITMQ_HOST`
    at the service block's own key names (`postgres`, `rabbitmq`) - which only resolve as DNS hostnames
    when the *job itself* also runs inside a container on the same Docker network GitHub creates for
    that case. This job has no `container:` key, so its steps run directly on the bare runner VM, where
    service containers are reachable only via `localhost:<mapped-port>` instead. Fixed by pointing both
    at `localhost`.
  - **A real `options:` quoting bug**: GitHub's `options:` field is appended as raw arguments to
    `docker create`, unlike Compose's `healthcheck.test:` (a plain YAML string). An unquoted multi-word
    `--health-cmd rabbitmq-diagnostics -q ping ...` gets tokenized word-by-word by Docker's own CLI
    parser - proven directly with a real `docker create` call, which got confused badly enough to try
    pulling an image literally named `ping` instead of `rabbitmq:4-management`. Fixed by quoting the
    whole health command as one string: `--health-cmd="rabbitmq-diagnostics -q ping"`.
- **`go-test`**: a `strategy.matrix` over `fx-service`/`notification-service`/`fraud-service`, each
  iteration running `go test ./...` then `go build`. A matrix reruns its *entire* step list once per
  matrix value, substituting `${{ matrix.app }}` - an early version mixed generic
  `${{ matrix.app }}`-driven steps with hardcoded per-service ones, which meant every matrix iteration
  built and tested whichever service happened to be hardcoded, under whichever name the matrix value
  currently was (e.g. `fraud-service`'s own code getting compiled into a binary named `fx`). Fixed by
  making every step in the matrix fully generic, none hardcoded to a specific service.
- **`build-and-push`** (`needs: [java-test, go-test]`, gated `if: github.ref == 'refs/heads/main' ||
  startsWith(github.ref, 'refs/tags/deploy*')` - only on the deployable branch or an explicit deploy
  tag, never on every branch/PR): builds and pushes all 4 images to **GHCR** (GitHub Container
  Registry), chosen specifically because it authenticates with the same `GITHUB_TOKEN` every workflow
  already gets for free, versus a separate Docker Hub account and a new secret to manage. Images are
  tagged by **git SHA, not `latest`** - an immutable tag per build is what makes the next job
  meaningful at all: each deploy is a real, distinct commit pointing at a real, distinct image. A
  `strategy.matrix.include` list pairs each service's build *context* directory with its desired
  *image* name separately (`{app: banking, image: banking-core}`, etc.) - necessary because the Java
  service's directory (`banking/`) and its desired published name (`banking-core`, matching the
  project's existing local-image naming convention) intentionally differ.
  - **Two real bugs here, both proven, not assumed**: `docker/build-push-action`'s `file:` input
    resolves relative to the *repo root*, not `context:` - so an explicit `file: ./Dockerfile` looked
    for a Dockerfile at the repo root, which doesn't exist (it lives at `<service>/Dockerfile` for
    every service). Fixed by removing `file:` entirely and letting it default to `{context}/Dockerfile`,
    which already matches every service's actual layout. Separately, the image tag was missing the
    `ghcr.io/` registry prefix entirely (`${{ github.actor }}/${{ matrix.image }}:...`) - a tag with no
    registry host defaults to Docker Hub, not GHCR, despite having just authenticated to `ghcr.io`
    specifically. Fixed to `ghcr.io/${{ github.repository_owner }}/${{ matrix.image }}:${{ github.sha
    }}` (also switching from `github.actor`, which varies per triggering user, to
    `github.repository_owner`, the stable account the images are meant to live under).
  - **Permissions are layered, and both layers have to agree**: a repo's Settings -> Actions -> General
    -> "Workflow permissions" radio button sets a *ceiling* on what `GITHUB_TOKEN` can ever do,
    regardless of what the workflow YAML asks for; the YAML's own `permissions: packages: write` is the
    *ask*, and needs the repo-level setting to actually allow it. Encountered directly as
    `denied: installation not allowed to Create organization package` on the first real push - GHCR
    packages also default to **private**, requiring a one-time manual switch to public in each
    package's settings so the cluster can pull them without needing `imagePullSecrets`.
- **`deployment`** (`needs: build-and-push`, deliberately **not** matrixed): bumps all 4 manifests'
  image tags to the new git SHA and commits the change back to the branch, with `[skip ci]` in the
  message. Running this from *inside* the matrix job was considered and rejected: 4 parallel matrix
  iterations each trying to commit and push their own one-file change to the same branch would race
  each other, since all 4 start from the same base commit and only the first push can ever succeed as
  a fast-forward. A single, separate, non-matrixed job downstream of the whole matrix sidesteps the
  race entirely. Without `[skip ci]`, this job's own commit would re-trigger the whole workflow,
  which would build new images, commit again, trigger again - forever; `[skip ci]` in a commit message
  is what GitHub Actions itself recognizes to skip firing a new run for that push. (A skipped job
  cascades: when `build-and-push`'s `if:` evaluates false, `deployment` - which only `needs:` it - is
  automatically skipped too, with no separate `if:` of its own required on `deployment`.)

## GitOps Deployment (ArgoCD)

[argocd/argocd-application.yaml](argocd/argocd-application.yaml) is what actually gets the new images
running in the cluster - `kubectl apply -f k8s/` is no longer part of the deploy path at all for
anything going through the pipeline above.

**Why an in-cluster, pull-based controller fits an intermittently-running local cluster better than a
push-based approach would.** ArgoCD runs as a set of pods *inside* minikube and polls *outward* to
GitHub - it never needs anything to reach *into* the cluster from outside. A GitHub Actions runner, by
contrast, has no route at all into a local cluster sitting behind a home network with no public
address, so having CI itself run `kubectl apply` directly (a push-based approach) genuinely couldn't
work here. When minikube is stopped, ArgoCD simply isn't running for a while; when it's started again,
it resumes and catches up on whatever changed on GitHub in the meantime - a genuinely better fit, not
just a technology preference.

**The `Application` resource itself is a Custom Resource**, not a built-in Kubernetes type - same YAML
shape as everything else in `k8s/`, just describing something ArgoCD's own controller watches for
rather than something the core Kubernetes API understands natively. Two details worth calling out:
- Its own `metadata.namespace` must be `argocd` (where the controller runs and watches), even though
  its whole job is describing and managing the `banking` namespace - ArgoCD only watches for
  `Application` objects living in its own namespace, not the one being managed.
- It deliberately lives in a separate top-level `argocd/` directory, **not** inside `k8s/` (the
  `spec.source.path` ArgoCD is told to sync). Had it lived inside `k8s/`, ArgoCD would end up
  syncing/managing its own `Application` resource as one of the things it watches - harmless in
  practice (it matches itself, so there's no drift to correct), but it would mean any future change to
  *this file itself* (like retargeting which branch to track) flows through the same
  git-commit-then-sync path as every other change, rather than being a deliberate, separate manual
  `kubectl apply` step under direct control.

**`syncPolicy.automated`** is what makes this actually automatic rather than "shows a diff and waits
for a human to click Sync" - `selfHeal: true` additionally means a manual, out-of-band change to
anything in the `banking` namespace gets reverted back to match git on the next reconciliation, and
`prune: true` means deleting a manifest from `k8s/` deletes the corresponding cluster resource too,
not just stops tracking it.

**`spec.source.targetRevision`** tracks whichever branch is actually deployable at any given time -
`gitops-dev` while this pipeline itself was being built and tested, switched to `main` once merged,
matching `build-and-push`/`deployment`'s own `main`-or-`deploy*`-tag gate. Proven end-to-end for real,
not just assumed: a genuine push all the way from a code change through CI, image build/push, the
automated tag-bump commit, to ArgoCD noticing that commit on its own and rolling out new pods - checked
directly via the `Application`'s `.status.sync.revision` matching the exact new commit SHA, and each
running pod's actual image tag matching it too, all without a single manual `kubectl` command.
