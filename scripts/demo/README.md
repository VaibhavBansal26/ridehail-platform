# The demo video

[`docs/demo/ridehail-demo.mp4`](../../docs/demo/ridehail-demo.mp4) (and the preview gif the README shows) is made by scripts driving
the real apps in a real browser. Nothing is mocked. It shows, with a spoken narration:

1. A customer and a driver sign up and the customer adds a card (the development test card; the simulated payment gateway is used, so no
   Stripe account or keys are involved).
2. The customer **searches for a destination by name** (`DESTINATION`, default "Lalbagh"), sees the **road** to it on a map and the upfront
   fare, and requests a ride. The nearest driver gets the offer in real time with the road on a map and accepts.
3. The customer watches the **car move live** on the map, from the driver's approach to the drop-off, in the picture of the driver's vehicle, **along that road**.
   The driver's phone is moved by the script along the same route the API gave the map; the app reports its position every few seconds, as a real one does.
4. The card is charged, the driver is credited. Then the **busy case**: three customers request a ride at the same instant, the nearest driver
   sees all three offers at once (each on its own map), takes one, and the others go to drivers nearby, all on the operations map.
5. A tour of the admin panel: what needs attention, payments, the ledger, a chargeback and its evidence, the audit log.

## Making it

You need Node 24, Maven and JDK 21, `ffmpeg`, and [Playwright](https://playwright.dev) with its Chromium (`npm i playwright` and
`npx playwright install chromium`, somewhere outside this repository; the scripts find it with `NODE_PATH`, default `~/node_modules`).

1. **The API, with a demo city:**

   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments=--ridehail.demo-data.enabled=true
   ```

2. **The three web apps, built for production** (a development server would draw its own overlay into the video). The customer app is built
   with the development "use a test card" button, which the simulated gateway accepts and a real one refuses:

   ```bash
   (cd admin-web && npm ci && BACKEND_URL=http://localhost:8080 npm run build)
   (cd clients && npm ci && NEXT_PUBLIC_DEV_CARD=true npm run build --workspace ridehail-rider-web \
                          && NEXT_PUBLIC_DEV_CARD=true npm run build --workspace ridehail-customer-web)

   (cd admin-web && BACKEND_URL=http://localhost:8080 ALLOW_INSECURE_COOKIES=true npx next start --port 3000) &
   (cd clients/apps/rider-web && BACKEND_URL=http://localhost:8080 ALLOW_INSECURE_COOKIES=true npx next start --port 3001) &
   (cd clients/apps/customer-web && BACKEND_URL=http://localhost:8080 ALLOW_INSECURE_COOKIES=true DEV_CARD_SHORTCUT=true npx next start --port 3002) &
   ```

   Map tiles, address search and road routes come from OpenStreetMap's public services (the API's default provider, see [docs/GEO.md](../../docs/GEO.md)), so the
   machine needs internet access, and the API must not be started with `GEO_PROVIDER=fake`. If the routing service is down the script drives a straight line.

3. **Narrate, record and assemble, in one command** (about 6 minutes). Restart the API first for tidy demo data:

   ```bash
   ELEVENLABS_API_KEY=... [ELEVENLABS_VOICE_ID=...] scripts/demo/build-demo.sh
   ```

   Without `ELEVENLABS_API_KEY` it uses a macOS system voice, as a stand-in.

## The voice

The words are in [`narration.json`](narration.json): edit them there. [`narrate.cjs`](narrate.cjs) turns each line into audio with
[ElevenLabs](https://elevenlabs.io) (`ELEVENLABS_VOICE_ID` picks the voice; the default is one of their stock voices) and measures how long
each line takes. The recorder then **waits for each line to finish before moving on**, so the picture and the voice stay together whatever
voice you use; `assemble.cjs` places each line where its scene started.

- The key is read from the environment only. It is never written to a file, a log or this repository, and ElevenLabs error messages
  are shown without it.
- Re-run the **whole** `build-demo.sh` after changing the voice or the words: the recording is paced to the audio, so a new voice needs
  a new recording. `assemble.cjs` warns if a line would run into the next one.
- The narration step has been run with the system voice only. It has not been run against ElevenLabs from this repository, so the first run
  with your key is its first real test: if the request fails it prints ElevenLabs' answer (status and reason) and stops.

## How it works, and what to know when it breaks

- **Two phone-sized browsers side by side.** The apps refuse to be shown in a frame (`frame-ancestors 'none'`), so the customer and the
  driver are recorded as two separate videos and joined with `ffmpeg`'s `hstack`. They start a moment apart, so they can be about
  a second out of step.
- **The destination is searched, not picked.** The first result for `DESTINATION` is what the customer chooses; a different place name may give a different
  road and so a different length of video, because the recording waits for the car to arrive.
- **The car moves because the phone moves.** The script changes the driver's browser geolocation every 4 seconds (the app's reporting
  interval); the server pushes each position to the customer, whose map glides the car from one point to the next.
- **The busy scene's extra people** (three customers, two nearby drivers) act through the API, the same one the apps use. They are set up
  before the cameras roll, and the browsers for the scene are signed in beforehand, so the scene starts straight away.
- **Captions, the pane labels and the click ripples are drawn inside the page**, so they are part of the recording. The apps' content security
  policy forbids inline styles, so the browser contexts are created with `bypassCSP`. Never do that outside a recording.
- **The driver only takes this customer's trip.** The demo city has trips of its own, so the driver is offered those too; the script reads
  the customer's fare, declines any offer that is not for it, and checks the accept took before it goes on.
- **Selectors are the apps' accessible names** (`Create account`, `Request ride`, `I've arrived`, ...). If a screen's wording changes the
  script waits for a control that is not there and fails with a timeout naming it; update the name here.
- **Re-recording one scene:** `SCENES=admin node scripts/demo/record-demo.cjs <clips-folder>` (also `title`, `ride`, `rush`, `end`) keeps the other
  clips, then run `assemble.cjs` again. It reuses the accounts from the last full run.
- The video is about 4 MB and the gif about 1 MB. Larger gifs do not play inline on GitHub.
