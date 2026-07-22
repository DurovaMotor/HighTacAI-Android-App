# ADR 0008: Android Direct Cloud Access

## Status

Accepted, 2026-07-22.

## Context

Advisor and price lookup must remain available when a phone is away from the
site Wi-Fi. Routing those requests through the Windows HighTac Platform coupled
Internet features to the reachability and health of the light-finding backend.

## Decision

- Advisor intent planning, final answers, and image recognition call the
  configured OpenAI-compatible Responses relay directly from Android over HTTPS.
- A user-initiated price refresh calls the JianDaoYun entry, widget, and data
  list APIs directly. Normal price searches read the complete local cache.
- Android injects JianDaoYun application/form identifiers in its data layer.
  Provider credentials are compiled from Git-ignored `local.properties`; they
  are never rendered in UI or written to logs. APK reverse-engineering risk is
  explicitly accepted for this deployment.
- Windows remains the path only for light-finding products, bindings, tags,
  stations, light commands, migration, and realtime events. Windows status and
  errors must not disable or relabel advisor/price state.
- The first process start after install or upgrade launches one non-blocking
  full price refresh. A persistent completion flag is written only after the
  complete dataset is fetched and cache persistence succeeds. Failure,
  cancellation, or stale-cache fallback leaves the flag unset; the same process
  does not retry, while the next process start does.
- Legacy Windows OpenAI/JianDaoYun mobile proxies stay operational and are marked
  deprecated for rollback to old APKs. Current Android source has no dependency
  on their transport or routes.

## Security and failure boundaries

Both direct transports require HTTPS, reject redirects, bound response bodies,
and add provider authorization only inside an OkHttp request. Error messages and
logs contain status/type information but no header or secret value. JianDaoYun
images are accepted only from the exact HTTPS `files.jiandaoyun.com` origin.

OpenAI failures never fall back to Windows. JianDaoYun refresh failures may use
the existing local cache but never fall back to Windows. Light-finding failures
remain scoped to the Windows-backed feature.
