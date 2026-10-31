# Customer and rider apps

Four apps on one shared client, in an npm workspace:

| Path | What | Status |
|---|---|---|
| `packages/api` | `@ridehail/api`: typed REST client, token refresh, STOMP realtime, zod schemas | **built, tested** |
| `apps/rider-web` | Next.js rider app | **built, tested** ([below](#rider-web)) |
| `apps/rider-mobile` | Expo / React Native rider app | **built, tested; not run on a device** ([below](#rider-mobile)) |
| `apps/customer-web` | Next.js customer app | not started |
| `apps/customer-mobile` | Expo / React Native customer app | not started |

```bash
cd clients
npm install
npm run typecheck && npm test                                   # package, rider-web and rider-mobile tests; the live ride below is skipped
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

## rider-web

`apps/rider-web` (port 3001): sign in or create a rider account, go online, get offers by push with a countdown, accept, run
the trip (arrived, start, complete, with a navigate link to the pickup and drop-off), and see earnings, withdraw, and set up payouts.

```bash
cp apps/rider-web/.env.example apps/rider-web/.env.local   # BACKEND_URL, ALLOW_INSECURE_COOKIES=true for plain-http local use
npm run dev --workspace ridehail-rider-web                 # http://localhost:3001, with the API on :8080
```

- **Security model** is the admin panel's: an `httpOnly` cookie session behind a same-origin BFF, a CSP nonce, a custom-header +
  Origin check on every call. The BFF only forwards the rider endpoints it lists (`lib/allow.ts`), refuses sessions that are
  not `RIDER`, and answers a customer's or admin's sign-in exactly like a wrong password.
- **Position.** While online the browser's position is sent every 4 s (the server accepts about 1/s and drops the rest; on a
  429 the app waits as long as `Retry-After` says). If the browser cannot supply one, the rider can set a position by hand
  (kept for the tab), and a warning explains that dispatch can no longer see them. **A web page only reports while it is open
  and in front**, which is the reason a phone app (`rider-mobile`) exists: it can keep reporting with the screen off.
- **Pushes mean "refetch"**: offers, cancellations and earnings arrive over the WebSocket, and each push (and every reconnect)
  makes the app read the truth over REST. Everything also polls slowly in case a push is lost.
- Backend additions this needed: `GET /api/trips/active` (find the current trip again after a reload), pickup/drop coordinates
  on trips, and the fare and coordinates on `GET /api/riders/me/offers`.

## rider-mobile

`apps/rider-mobile` (Expo SDK 58, expo-router): the same flows as rider-web as a phone app (sign in or create an account, go
online, live offers, accept, run the trip, earnings and payouts), plus what only a phone can do: **keep reporting the rider's
position with the screen off**.

```bash
cd apps/rider-mobile
EXPO_PUBLIC_API_URL=http://<your computer's address>:8080 npx expo start    # a real phone needs the computer's address, not localhost
npx expo start --web                                                        # the screens in a browser (no background location, no keychain)
npm test                                                                    # Jest: the background handler, token storage, session, screens
```

- **Tokens live in the OS keychain/keystore** (`expo-secure-store`) via `TokenStore`; the shared client refreshes them, and
  `auth.accessToken()` renews one that is about to expire before every WebSocket reconnect.
- **Position:** while the app is open it reports every 4 s like the web app. With "Always" location allowed, an OS background
  task (`src/lib/background.ts`) keeps going with the screen off; on Android a foreground-service notification keeps it alive.
  The task sends only the latest position, at the server's pace, and stays quiet while the app is open (the foreground
  reporter is already sending). Without "Always" the app says so and the rider is only visible while it is open.
- **Needs a development build for background location.** Expo Go cannot run it. `npx expo run:ios` / `run:android`
  (or an EAS development build) produce one; both need Xcode or the Android SDK.
- **What has and has not been verified.** Verified: types, 29 Jest tests, a Metro bundle for iOS and Android (in CI), and the
  screens driven in a browser through Expo's web build against the real API over HTTP and WebSocket (sign-in, live offer, accept,
  trip panel). **Not verified: running on an iOS simulator, Android emulator or a real phone**, so the keychain, the OS
  permission prompts and background location itself are untested. Do that before relying on them.
