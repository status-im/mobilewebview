# 3. Android multi-profile and incognito

Date: 2026-06-17

## Status

Accepted

## Context

Classic Android `WebView` shares a **process-global** cookie store (`CookieManager`),
DOM storage (`WebStorage`), and HTTP cache. There is no per-instance isolation and no
true incognito: clearing storage to fake incognito would also wipe standard data.

`androidx.webkit` adds a multi-profile API (`Profile` / `ProfileStore`), gated on
`WebViewFeature.MULTI_PROFILE` (WebView ≥ 113, ubiquitous since 2023). A profile is
bound to a WebView before first use via `WebViewCompat.setProfile`. Profiles isolate
cookies and storage but have no built-in ephemeral/incognito flavor.

## Decision

- **Standard**: a persistent named `Profile` whose name is `storageName`
  (`Profile_<userId>`), giving real per-account isolation matching desktop.
- **Incognito**: a dedicated profile named `Incognito_<random>` created on entry and
  **deleted on teardown** via `ProfileStore.deleteProfile()` after `WebView.destroy()`.
  This is a best-effort ephemeral store — transiently on disk, **not in-memory** like
  desktop/Apple off-the-record. This semantic difference is accepted and documented.
- **Crash safety**: on library init, sweep and delete any orphaned `Incognito_*`
  profiles left by a previous crash/kill.
- **Persistence correctness**: flush the (standard) profile's cookies on
  `onPageFinished` and on `destroy()`. Incognito never flushes.
- **Fallback**: on WebView < 113 (no MULTI_PROFILE), use the single default profile;
  incognito isolation is degraded and reported as unsupported.

## Consequences

- Android reaches isolation parity with desktop on modern WebView.
- Incognito data is briefly on disk; mitigated by delete-on-teardown + startup sweep.
- A new dependency on `androidx.webkit` (multi-profile) is introduced.
- Account web-data deletion (logout) maps to `ProfileStore.deleteProfile(storageName)`,
  left to the host app for now.
