# HighTac Web Console

React/TypeScript/Vite administration console for the HighTac LAN platform. Production requests use same-origin `/api/v1`; realtime events use `/api/v1/ws/events` by default.

## Commands

```powershell
npm install
npm run dev
npm run typecheck
npm test
npm run build
npm run test:e2e
```

For UI development without the backend, `npm run dev:mock` enables the in-browser demo transport. Mock credentials are `Adam` / `Adam`; this mode is never enabled by the production build.

Set `VITE_API_PROXY_TARGET` in a local environment file to proxy development traffic to a different platform server. Do not put administrator, device, or MQTT credentials in Vite environment variables: values prefixed with `VITE_` are included in browser assets.
