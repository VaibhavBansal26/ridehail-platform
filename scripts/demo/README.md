# The demo video

[`docs/demo/ridehail-demo.mp4`](../../docs/demo/ridehail-demo.mp4) (and the preview gif the README shows) is recorded by a script
driving the real apps in a real browser. Nothing is mocked: a customer and a driver sign up, the customer books a ride, the driver
accepts and drives it, the card is charged and the driver is credited; then the admin panel is toured. Payments are the **simulated**
gateway, so no Stripe account or keys are involved.

## Re-recording it

You need Node 24, Maven and JDK 21, `ffmpeg`, and [Playwright](https://playwright.dev) with its Chromium (`npm i playwright` and
`npx playwright install chromium`, somewhere outside this repository; the script finds it with `NODE_PATH`).

1. **The API, with a demo city** (the admin tour needs something to show; a fresh start also keeps the data tidy):

   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments=--ridehail.demo-data.enabled=true
   ```

2. **The three web apps, built for production** (a development server would draw its own overlay into the video). The customer app
   is built with the development "use a test card" button, which the simulated gateway accepts and a real one refuses:

   ```bash
   (cd admin-web && npm ci && BACKEND_URL=http://localhost:8080 npm run build)
   (cd clients && npm ci && NEXT_PUBLIC_DEV_CARD=true npm run build --workspace ridehail-rider-web \
                          && NEXT_PUBLIC_DEV_CARD=true npm run build --workspace ridehail-customer-web)

   (cd admin-web && BACKEND_URL=http://localhost:8080 ALLOW_INSECURE_COOKIES=true npx next start --port 3000) &
   (cd clients/apps/rider-web && BACKEND_URL=http://localhost:8080 ALLOW_INSECURE_COOKIES=true npx next start --port 3001) &
   (cd clients/apps/customer-web && BACKEND_URL=http://localhost:8080 ALLOW_INSECURE_COOKIES=true DEV_CARD_SHORTCUT=true npx next start --port 3002) &
   ```

3. **Record the clips, then join them:**

   ```bash
   NODE_PATH=$HOME/node_modules node scripts/demo/record-demo.cjs /tmp/ridehail-clips
   scripts/demo/make-video.sh /tmp/ridehail-clips docs/demo
   ```

   `CUSTOMER_URL`, `RIDER_URL`, `ADMIN_URL` and `ADMIN_PASSWORD` override the addresses and the admin password.

## How it works, and what to know when it breaks

- **Two phone-sized browsers side by side.** The apps refuse to be shown in a frame (`frame-ancestors 'none'`), so the customer and
  the driver are recorded as two separate videos and joined with `ffmpeg`'s `hstack`. They start a moment apart, so they can be
  about a second out of step.
- **Captions, the pane labels and the click ripples are drawn inside the page** so they are part of the recording. The apps' content
  security policy forbids inline styles, so the browser contexts are created with `bypassCSP`. Never do that outside a recording.
- **The driver only takes this customer's trip.** The demo city has trips of its own, so the driver is offered those too; the script
  reads the customer's fare and declines any offer that is not for it.
- **Selectors are the apps' accessible names** (`Create account`, `Request ride`, `I've arrived`, ...). If a screen's wording changes,
  the script waits for a control that is not there and fails with a timeout naming it; update the name here.
- **The admin tour is played at 1.25x** (see `make-video.sh`) because its pages take a moment to load.
- The video is about 1.5 MB, the gif under 1 MB. Larger gifs do not play inline on GitHub.
