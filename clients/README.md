# Customer and rider apps

Four apps on one shared client, in an npm workspace:

| Path | What | Status |
|---|---|---|
| `packages/api` | `@ridehail/api`: typed REST client, token refresh, STOMP realtime, zod schemas | **built, tested** |
| `apps/rider-mobile`, `apps/customer-mobile` | Expo / React Native | not started |
| `apps/rider-web`, `apps/customer-web` | Next.js | not started |

```bash
cd clients
npm install
npm run typecheck && npm test                                   # 35 tests; the live ride below is skipped
RIDEHAIL_E2E_URL=http://localhost:8080 npm test --workspace @ridehail/api   # with the API running: a whole ride
```

## The shared package

```ts
import { createApi, bearerAuth, saveSession, connectRealtime, toWebSocketUrl } from "@ridehail/api";

const store = secureStoreTokens();                      // mobile: expo-secure-store (keychain), never AsyncStorage
const api = createApi({ baseUrl, auth: bearerAuth({ store, baseUrl, onSignedOut }) });

await saveSession(store, await api.auth.login({ phone, password }));
const quote = await api.fares.quote({ vehicleType: "SEDAN", pickupLat, pickupLon, dropLat, dropLon });
const trip = await api.trips.book(quote.quoteId);

connectRealtime({
  url: toWebSocketUrl(baseUrl), role: "CUSTOMER",
  getToken: async () => (await store.load())?.accessToken ?? null,
  onMessage: (m) => { /* TRIP_ACCEPTED, RIDER_LOCATION, PAYMENT_CAPTURED ... or UNKNOWN, never a throw */ },
  onConnected: () => { /* refetch current state over REST: pushes are best-effort and never replayed */ },
});
```

- **Every response is validated** (zod). If the backend and an app drift apart the error names the field (`ContractError`)
  instead of the app showing `undefined`.
- **Errors a person can read.** `ApiError` has `status`, `isNetwork` (status 0), `isRateLimited` and `retryAfterSeconds`
  (location updates are limited to 1/s; on a 429, wait that long and drop the position).
- **Refresh is single-flight.** Three requests failing with 401 together cause one refresh (the server rotates refresh
  tokens, so parallel refreshes would invalidate each other). A refresh the server *refuses* signs the person out; one that
  could not reach the server does not.
- **Realtime by role.** Riders subscribe to offers and trips, customers to trips and location; the server disconnects a client
  that subscribes to a stream that is not for its role. Pushes carry little and are never replayed, so on every
  `onConnected` the app refetches state over REST first.

## The contract

`fixtures/*.json` are real responses. The backend's `ClientApiContractTest` drives a ride through the API and fails if a
response stops having the same fields and types as its fixture; `packages/api/src/contract.test.ts` fails if the schemas can
no longer parse them. Change the API on purpose with `mvn test -Dfixtures.write=true`, review the fixture diff, and the
package's tests then say what to update.

## Decisions the apps follow

- **Mobile keeps tokens in the keychain/keystore** (`expo-secure-store`) behind `TokenStore`, and uses `bearerAuth`.
- **Web keeps tokens out of page JavaScript**, as the admin panel does: a same-origin BFF holds the session in `httpOnly`
  cookies and proxies API calls (`cookieAuth`). The one exception is the WebSocket, which needs a bearer token in the STOMP
  CONNECT frame: the BFF hands the browser a short-lived access token for it, held in memory only. The web app's origin must
  be in the API's `ALLOWED_ORIGINS`.
- **Cards and payout accounts are never typed into our apps.** The customer app passes the `clientSecret` from
  `customer.startCardSetup()` to Stripe's SDK; the rider app opens the `url` from `rider.startPayoutOnboarding()`.
  With the Stripe provider the API refuses client-supplied tokens, so there is no shortcut to build.
- **Money is shown from the server's numbers** (`fare`, `total`, `available`), never recomputed on the device.
