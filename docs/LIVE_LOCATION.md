# Live rider location

While a rider is on its way to a customer, or driving them, the customer's app sees the rider moving on the map.

```
rider app ──PUT /api/riders/me/location──▶ server ──WebSocket /user/queue/location──▶ customer app
              (every few seconds)           │  looks up the rider's active trip
                                            │  and sends ONLY to that trip's customer
```

## Rider app: report your position

`PUT /api/riders/me/location` (rider token)

```json
{ "lat": 12.9720, "lon": 77.5950, "heading": 90.0 }
```

`heading` is optional (degrees, 0–360); it is passed on to the customer's map and not stored.
Send it every 3–5 seconds while online. **Do not send faster than once a second**:

| | |
|---|---|
| Sustained limit | 1 update/second per rider |
| Burst allowed | 5 (e.g. after the network comes back and the app flushes a queue) |
| When exceeded | `429 Too Many Requests` with a `Retry-After` header (seconds). The update is **dropped**, not stored or forwarded. Back off for that long. |

Limits are per application instance and configurable (`ridehail.tracking.*`).

## Customer app: receive it

1. Connect to `/ws` with `Authorization: Bearer <access token>` (as for every other feed).
2. Subscribe to `/user/queue/location`. **Customers only**: a rider (or anyone else) who tries is disconnected.
3. You receive one message per push:

```json
{
  "type": "RIDER_LOCATION",
  "tripId": "…",
  "status": "ACCEPTED",          // ACCEPTED | ARRIVED | IN_PROGRESS
  "lat": 12.9720,
  "lon": 77.5950,
  "heading": 90.0,               // only if the rider's app sent it
  "at": "2026-10-26T14:03:11.482Z"   // server time the position was received
}
```

- **At most one message every 2 seconds per trip** (`push-min-interval-ms`). Intermediate positions are dropped, not queued,
  so you always get the freshest one. Animate between points on the client.
- Use `at` to ignore anything older than what you already show (messages are not guaranteed to arrive in order across
  reconnects).
- Pushes are best-effort. **When the app opens or reconnects, fetch the current position first**, then listen:

`GET /api/trips/{id}/rider-location` → `{ "tripId": "…", "status": "…", "lat": …, "lon": …, "at": "…" }`
(`409` if the trip is not active, `404` if the rider has not reported a position yet.)

## Who can see a rider's position

- Only the **customer of the rider's active trip** is ever sent it. The server works out the recipient itself from the trip;
  nothing a client sends can redirect it. A rider's position is never shared with other customers or other riders.
- It is shared only while the trip is **ACCEPTED, ARRIVED or IN_PROGRESS**: not before a rider is assigned, and it stops the
  moment the trip is completed or cancelled. The REST endpoint follows the same rule (admins and the trip's own parties only).
- The recipient is looked up from the database on every update and deliberately **not cached**: another server instance may
  have ended the trip, and a stale answer would keep broadcasting a rider's position after the ride.

## Operating notes

- The WebSocket broker is in-memory, so a push reaches the customer only if they are connected to the same instance that
  received the rider's update. Run several instances behind a STOMP broker relay (RabbitMQ) before scaling out; the same
  applies to the offer and trip-update feeds.
- Rate limits are per instance too. A strict global limit would need a shared store such as Redis.
- Each rider update costs two small queries (the rider row, then the active-trip lookup). At large scale, move live positions
  to Redis/streams and keep the database for the last-known position only.
- Not implemented: rejecting physically impossible jumps (GPS spoofing), and per-customer subscription rate limits.
