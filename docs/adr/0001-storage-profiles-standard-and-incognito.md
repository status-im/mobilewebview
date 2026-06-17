# 1. Storage profiles: standard and incognito modes

Date: 2026-06-17

## Status

Accepted

## Context

The mobile browser must reach feature parity with desktop, which supports two
modes per tab:

- **Standard**: persistent profile (cookies, HTTP cache, DOM storage survive sessions),
  partitioned per Status account.
- **Incognito (off-the-record)**: ephemeral profile, nothing persisted.

Desktop models this with a `WebEngineProfile` chosen from `(userId, offTheRecord)`
and switches a live tab between modes by swapping its `profileParams`, after which
the `WebEngineView` reloads the current URL under the new profile.

`MobileWebViewBackend` currently creates a single native view at construction with the
platform default data store and exposes no notion of profile, partition, or incognito.

A hard platform constraint shapes everything: a `WKWebView`'s `websiteDataStore` is
fixed on its configuration at creation, and an Android `WebView`'s profile must be set
before first use. Neither store can be changed on a live native view.

## Decision

`MobileWebViewBackend` exposes two QML properties mirroring desktop semantics:

- `bool offTheRecord` — incognito when true (ephemeral store).
- `QString storageName` — identity of the persistent partition (e.g. `Profile_<userId>`);
  ignored when `offTheRecord` is true.

Switching either property on a live webview is supported by **a clean destroy + init
of the native view** — never a partial mutation of the live view. The platform backends
expose a symmetric pair:

- `initNativeView()` — build a native view bound to the current store (already exists).
- `destroyNativeView()` — full teardown (extracted from today's destructor / `cleanupJni`).

The switch sequence is therefore identical to a cold construction:

```
destroyNativeView()        // release old WKWebView / Android WebView + its data-store binding
initNativeView()           // new view bound to the NEW store
setupNativeViewImpl()      // re-attach to host view + sync state
ensureBridgeInstalled()    // re-install message bridge + re-apply user scripts
loadUrl(m_url)             // auto-reload current URL; per-view history resets
```

The new native view inherits nothing from the old one (new view, new data store, fresh
clients/observers, empty history). This matches desktop's reuse-the-tab functional logic
and keeps the QML adapter declarative — it only sets the two properties.

During recreation the backend must neutralize tails of the old view: cancel any in-flight
snapshot/`freeze`, and ignore pending async callbacks from the old instance (on Android
`mNativePtr` is already zeroed in `destroy()`, which suppresses stale JNI callbacks; on
Apple the KVO observer and delegates are detached in `destroyNativeView()`).

## Consequences

- The QML `MobileWebViewAdapter` becomes a thin translator of `profileParams`
  (`offTheRecord`, `storageName` from `userId`) and sets `supportsIncognito: true`.
- The backend must own a teardown→rebuild→rebridge→reload sequence and guard against
  in-flight loads/snapshots during recreation.
- Per-platform store selection is delegated to the platform backends (see ADR 0002,
  0003).
- Reversing this (e.g. construction-only mode) would push special-casing into the
  shared desktop/mobile browser QML, which we explicitly avoid.
