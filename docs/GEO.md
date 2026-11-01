# Address search and road routes

The customer and rider apps draw the road between two points and let a customer search for a destination by name. Both come from the API,
never straight from a phone or browser to a third party.

```
app ──GET /api/places/search?q=…──▶ API ──▶ search provider (Nominatim-compatible)
app ──GET /api/routes?from…&to…───▶ API ──▶ routing provider (OSRM-compatible)
```

Why through the server: the provider's address, any key and its usage limits live in one place; answers are remembered, so the same
addresses and roads are not asked for again and again (the providers are slow, rate-limited and may cost money); and a person's searches and
trips are not sent to a third party by their phone.

## The two endpoints

Both need a customer or rider sign-in (an administrator gets 403).

| | |
|---|---|
| `GET /api/places/search?q=<text>[&lat=&lon=]` | 3 to 200 characters. `lat` and `lon` (both or neither) say where the person is: results near it are preferred. Returns `[{name, address, lat, lon}]`, at most 6. |
| `GET /api/routes?fromLat=&fromLon=&toLat=&toLon=` | Returns `{distanceKm, durationMin, points: [[lat, lon], …]}`: the road from the start to the end, thinned to at most 250 points (Ramer-Douglas-Peucker, keeping both ends and the shape). |

Errors: `400` for a bad question (too short or long, one of `lat`/`lon` alone, coordinates out of range), `429` with `Retry-After` when a
person, or everyone together, asks too fast, and `502` when the provider cannot answer. **The apps treat 502 and 429 as "no road" or "search
unavailable"**: the map falls back to a straight dashed line and the booking screen to its list of popular places. A provider outage never
stops a ride.

## Providers

`ridehail.geo.provider` is `osm` (the default) or `fake`.

- **`osm`** speaks the Nominatim search API and the OSRM routing API, which many services implement. The defaults point at the **public**
  servers (`nominatim.openstreetmap.org`, `router.project-osrm.org`). **Their usage policies allow light use only** (the public Nominatim:
  one request a second in total, a valid identifying User-Agent, no autocomplete traffic). That is fine for a demo. For real traffic run your own
  (both are open source and can be self-hosted) or use a paid service that speaks the same APIs, and set:

  | Setting | Environment | |
  |---|---|---|
  | `ridehail.geo.search-url` | `GEO_SEARCH_URL` | Base URL of the Nominatim-compatible service |
  | `ridehail.geo.routing-url` | `GEO_ROUTING_URL` | Base URL of the OSRM-compatible service |
  | `ridehail.geo.user-agent` | `GEO_USER_AGENT` | **Set this to your application's name and a contact address.** Nominatim's policy requires it |
  | `ridehail.geo.api-key` | `GEO_API_KEY` | For a paid provider; sent as `?key=`. Never logged, never in an error message |

  The API logs which hosts it uses (never the key), and warns at startup when it is on the public servers.
- **`fake`** needs no network: six well-known places in the launch city and routes that are a straight line. For tests and working offline.

The apps themselves send nothing to the providers. The apps' **map tiles** are a separate thing, fetched by the app from the tile server
(`MAP_TILE_URL`, see [../clients/README.md](../clients/README.md)); only an address or a route goes through the API.

## Limits and memory

| | Default |
|---|---|
| Searches per person | 1 a second, burst of 5 |
| Routes per person | 2 a second, burst of 10 |
| Searches to the provider, everyone together | 1 a second, burst of 2 (the public Nominatim's limit) |
| Routes to the provider, everyone together | 5 a second, burst of 10 |
| A search is remembered for | 24 hours (the question is trimmed, case-folded and its position rounded to about a kilometre) |
| A route is remembered for | 1 hour (coordinates rounded to about 11 metres) |

Repeats cost nothing at the provider, so the provider limit only counts questions it has not answered before. Failures are not remembered. The
limits are shared across API instances when Redis is configured (see [SCALING.md](SCALING.md)); the answers are remembered per instance.

## Fares from the road (optional)

`ridehail.geo.fares-use-roads=true` (`GEO_FARES_USE_ROADS`) quotes fares from the road distance and driving time instead of the straight
line times a road factor, so the price matches the route the apps draw. It is **off by default**, because it changes what a trip costs. When a road
is not available (provider down or busy) a quote falls back to the straight-line estimate, so a fare quote never fails because of a map service.

## What is not here

- No turn-by-turn directions: the drivers' apps hand off to the phone's navigation app.
- No traffic: the driving time is the provider's free-flow estimate.
- Destinations are not stored as addresses on a trip (only coordinates), and there is no history of what a person searched for.
