# 6. HTTP User Agent is a per-view property

Date: 2026-07-15

## Status

Accepted

## Context

Desktop exposes `httpUserAgent` on `WebEngineProfile` and Status binds
`profileParams.userAgent` onto that profile. `MobileWebView` has no separate
Profile QML type — Storage Profile identity already lives as `offTheRecord` /
`storageName` on `MobileWebViewBackend` — and the native engines set UA on the
view (`WKWebView.customUserAgent`, `WebSettings.setUserAgentString`). The mobile
adapter currently cannot apply `profileParams.userAgent`.

## Decision

Expose `httpUserAgent` on `MobileWebViewBackend` as a per-view property:

- Empty string means “use the platform default”; the getter returns the declared
  override (`""` when not overriding), not the engine’s default string.
- Changing it mutates the live native view (no destroy/recreate, no auto-reload).
  Only subsequent navigations / network requests are guaranteed to use the new
  value.
- The override is independent of Storage Profile: after an
  `offTheRecord` / `storageName` recreate it is re-applied to the new native view.
- `MobileWebViewAdapter` binds `profileParams.userAgent` → `backend.httpUserAgent`.

## Consequences

- Desktop profile-scoped UA becomes per-view on mobile; shared behaviour still
  comes from the host passing the same `profileParams.userAgent` to each tab.
- Callers that need the new UA on an already-loaded page must reload themselves.
