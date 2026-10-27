# Running more than one instance

The application can run as many identical instances as you like behind a load balancer. Two things used to live inside one
instance's memory and therefore only worked for one: **realtime pushes** and **rate limits**. They now go through shared
services, so any instance can serve any user.

| Concern | One instance (default, development) | Several instances (production) |
|---|---|---|
| Realtime pushes (WebSocket) | in-memory broker: `ridehail.realtime.broker=simple` | **RabbitMQ** STOMP relay: `broker=relay` |
| Rate limits | in-memory counters: `ridehail.ratelimit.store=memory` | **Redis**: `store=redis` |

Everything else was already safe to run several times: state is in Postgres; schema migrations take a lock; competing writers are
settled by version columns (payments, payouts, offers); the first-boot setup tolerates instances starting together.

## Try it

```bash
LOCATION_RATE=0.2 docker compose up --build        # Postgres, Redis, RabbitMQ, TWO app instances, nginx gateway
python3 scripts/verify-realtime.py                 # pip install websocket-client first
```

`docker compose` gives you instance A on :8081, instance B on :8082 and a gateway on :8080 that spreads both REST and WebSocket
traffic over them. RabbitMQ and Redis are on the internal network only. `verify-realtime.py` connects a rider to one instance and a
customer to the other and drives a ride, checking each push crosses instances, that live-location pushes are throttled once
for both instances together, and that the rider's rate limit is one allowance shared by both. CI runs it on every push.

```
 phones ──▶ nginx ──▶ app A ──┐                  ┌── Postgres   system of record
  (REST + WebSocket)  app B ──┼── RabbitMQ (STOMP)   ── pushes between instances
                              └── Redis              ── rate-limit counters
```

## How a push finds its user

A user holds their WebSocket on exactly one instance. When something happens on another instance (a customer books on A, the
rider's socket is on B), the instance addresses the push to the *user*; RabbitMQ gets it to the instance that has them.

- Each WebSocket session has its own transient queue at the broker. A push for a user is published to their session(s).
- Instances share who is connected where (every ~10 s), so **a user with two devices on two instances receives on both**.
- A user who connected within the last ~10 s is not yet known to the sender. Such a push is broadcast as "unresolved" and the
  instance that has the user delivers it. So delivery works immediately for a single device; a *second* device on another
  instance joins the fan-out after that window. (Tested: both behaviours.)

Delivery is **at most once, best effort**, as before: a push to someone who is disconnected is gone, and the app must refetch state over
REST when it reconnects (`GET /api/riders/me/offers`, `GET /api/trips/{id}`, `GET /api/trips/{id}/rider-location`).

### The streams are `/user/topic/*` (this changed from `/user/queue/*`)

Clients subscribe to **`/user/topic/offers`**, **`/user/topic/trips`** and **`/user/topic/location`**. Earlier versions used `/user/queue/*`.
The rename is deliberate and was forced by how RabbitMQ treats the two prefixes, which I measured against a real broker:

| RabbitMQ destination | Queue it creates | Publisher side effects |
|---|---|---|
| `/queue/…` | **durable, never deleted**, survives the subscriber | publishing to a departed session **re-creates it**: one orphan per session, forever. Any hygiene setting on the subscription (`auto-delete`, expiry) makes the publisher's own declaration conflict, and **every push then fails**. |
| `/topic/…` | transient, **owned by the subscriber's connection**, removed when it ends | publishing to a departed session is accepted and leaves nothing behind; message TTL and length limits can be set on the subscription |

The in-memory broker uses the same names so behaviour cannot differ between development and production.

## Rate limiting across instances

A load balancer spreads one rider's requests over several instances, so per-instance counters would multiply every limit by the
instance count. With `store=redis`:

- each limit is a token bucket in Redis, updated by one atomic Lua script (no read-modify-write race between instances);
- the script reads **Redis's own clock**, so instances never need synchronized clocks;
- buckets expire when idle (a full bucket is the same as no bucket), so Redis does not accumulate keys.

**Redis is never a single point of failure.** If it is unreachable the limiter answers from local counters (still limiting, now per
instance), skips Redis for `redis-cool-off` (5 s) so no request waits on a dead connection, and returns to Redis by itself. The Redis
health indicator is deliberately disabled: a Redis outage must not mark an instance unhealthy and get it removed from rotation.

## Configuration

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `ridehail.realtime.broker` | `REALTIME_BROKER` (prod) | `simple` (`relay` in prod) | `simple` or `relay` |
| `ridehail.realtime.relay.host` / `.port` | `RABBITMQ_HOST` / `RABBITMQ_STOMP_PORT` | `localhost` / `61613` | RabbitMQ with the STOMP plugin |
| `…relay.virtual-host` | `RABBITMQ_VHOST` | `/` | |
| `…relay.*-login`, `*-passcode` | `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | dev values (none in prod) | the application's credentials |
| `ridehail.ratelimit.store` | `RATELIMIT_STORE` (prod) | `memory` (`redis` in prod) | `memory` or `redis` |
| `spring.data.redis.host` / `.port` / `.password` | `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | `localhost` / `6379` | |
| `ridehail.ratelimit.redis-cool-off` | | `5s` | how long to use local counters after a Redis failure |

With `broker=relay`, a missing host, user or password makes startup fail immediately and say which (the production profile has no
usable defaults for them).

## RabbitMQ requirements

- Enable the **STOMP plugin** (`rabbitmq-plugins enable rabbitmq_stomp`, or an `enabled_plugins` file as in `docker/rabbitmq/`).
- Create a dedicated user for the application with access to the virtual host. The built-in `guest` user only works from localhost.
- **Do not expose port 61613 to the internet or to phones.** Phones connect to the application's `/ws`; only the application connects to
  RabbitMQ. The STOMP port carries no per-user authentication, so network placement is its security.
- **TLS and clustering are not configured.** The relay connects to one `host:port` in plain text: run it on a private network, and put
  a load balancer or virtual address in front if you need broker high availability.
- **Capacity:** every connected WebSocket client costs **one TCP connection to RabbitMQ** (Spring's relay design; verified: n clients,
  n+1 connections, released when they leave). Size RabbitMQ's file-descriptor and connection limits for your peak concurrent users.

### What the application does to protect the broker

Because the relay forwards client frames to RabbitMQ, the WebSocket gatekeeper treats them as untrusted:

- **`CONNECT` and its alias `STOMP`** must carry a valid bearer token. (An unauthenticated `STOMP` frame used to slip through the check;
  with a relay each one would have cost a broker connection. 25 such frames now cost none, tested.)
- **Subscriptions** are limited to the three user streams, by role. The broker's internal destinations (`/topic/simp-user-registry`,
  `/topic/unresolved-user-destination`) and other users' session destinations are refused.
- **`SEND`, `ACK`, `NACK`, `BEGIN`, `COMMIT`, `ABORT`** are refused: the socket is server-to-client only.
- **Client headers are reduced to an allow-list**, so a client cannot choose how its queue is built (`durable`, `x-queue-name`,
  `x-max-length`, dead-lettering…). The application sets `x-message-ttl` (60 s: a stale position or offer is worthless) and
  `x-max-length` (100: a stalled phone cannot hoard messages). The client's bearer token is removed before the frame reaches the broker.

## Operating it

**Alert on** `ridehail.realtime.broker.available` = 0 (1 while an instance is connected to RabbitMQ). Metrics are admin-only:
`GET /actuator/metrics/ridehail.realtime.broker.available` with an admin token. Only `/actuator/health` and `/info` are open
(for load balancers).

What happens when things fail (the first three were exercised against the live compose stack):

| Failure | Effect | Recovery |
|---|---|---|
| **RabbitMQ restarts** | Metric drops to 0 on every instance; pushes are not delivered meanwhile; REST keeps working. Connected clients are dropped (their broker connection died). | Instances **reconnect by themselves, ~6 s** measured, no restart. Clients reconnect and refetch state over REST. |
| **An application instance dies** | Its WebSocket clients are dropped; the others, and the gateway, are unaffected (20/20 requests through the gateway succeeded while it was down; a ride entirely on the survivor worked). | Clients reconnect (the gateway sends them to a live instance). Replaced instances are picked up by the gateway within 5 s, no gateway restart. |
| **Redis down** | Rate limiting continues per instance; 8 requests took 0.11 s with Redis stopped (5 accepted, 3 refused, none waited). Health stays UP. | Back on shared counters automatically after the cool-off. |
| **RabbitMQ unreachable at startup** | The instance starts and serves REST; pushes are dropped and logged; metric is 0. | Connects when RabbitMQ appears. |
| **Instances start together on an empty database** | Both run the first-boot setup (admin user, platform ledger accounts); the loser of the race retries. | Automatic. (Tested with 8 concurrent bootstraps.) |

**Rolling deploys:** replace instances one at a time. Each replacement drops that instance's WebSocket clients, who reconnect to the
others; stagger it so the rest of the fleet absorbs the reconnections.

**Verify a deployment** with `scripts/verify-realtime.py --a <portA> --b <portB> --gateway <port>`.

## Limits and what is not done

- **Scheduled sweeps run on every instance** (offer expiry, payment/payout retries). They are safe, since version columns make exactly one
  instance win each item, but wasteful. A scheduler lock (e.g. ShedLock) would run each sweep once.
- **A second device on another instance** is added to the fan-out after up to ~10 s (see above).
- **The simulated payment gateway is per instance** (dev only); production uses Stripe, which has no such state.
- **Rate limits are per key**, not a global ceiling: they stop one rider flooding, not total load.
- **No TLS and no broker clustering** to RabbitMQ/Redis (see above).
- **Docker Compose is for development and verification**, with development credentials and the simulated payment gateway.
