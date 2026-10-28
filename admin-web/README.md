# Ridehail Ops (admin web panel)

Next.js operations console for the ridehail platform. How it works, how to run it, its security model and its known limits:
[../docs/ADMIN_PANEL.md](../docs/ADMIN_PANEL.md).

```bash
cp .env.example .env.local   # BACKEND_URL, map tiles; ALLOW_INSECURE_COOKIES=true for plain-http local use
npm ci
npm run dev                   # http://localhost:3000
npm run lint && npm run typecheck && npm test && npm run build
```
