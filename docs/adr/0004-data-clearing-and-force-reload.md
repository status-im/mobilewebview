# 4. Data clearing (browsing data, current-site data) and force reload

Date: 2026-07-01 (revised 2026-07-13)

## Status

Accepted (revised — supersedes the original per-site DOM-storage-only decision)

## Context

Popular browsers (Chrome, Brave, Firefox — desktop and mobile) converge on **two
distinct user actions**, not one:

- **Clear browsing data** — profile-wide, with a **category** choice (cookies, cache,
  DOM storage), covering every site at once.
- **Clear (current) site data** — an **all-or-nothing** wipe of one site's data
  (cookies, cache, DOM storage, service workers), leaving other sites intact.

Status Desktop (WebEngine) historically exposed a single "clear site data" that was a
dishonest **hybrid**: `clearHttpCache()` + `deleteAllCookies()` cleared **globally**,
while `localStorage`/`sessionStorage`/IndexedDB/Cache API/service-workers were cleared
**only for the current origin** via JavaScript injected with `runJavaScript`
(`site_utils.js`, status-desktop `1260e3dc`). The JS route exists because
`QWebEngineProfile` has **no per-origin access** to web storage — the injected script
only ever sees the page it runs in. This means desktop's action matches neither of the
two canonical browser actions.

Mobile engines are more capable than WebEngine here:

- **Apple `WKWebsiteDataStore`** removes data by type at profile scope
  (`removeData(ofTypes:modifiedSince:)`) **and per data record**
  (`fetchDataRecords` → `removeData(ofTypes:for:)`), where a record is grouped by
  host/eTLD+1. A per-site record removal covers cache, cookies, and all web storage.
- **Android `androidx.webkit` `WebStorageCompat.deleteBrowsingDataForSite()`**
  (feature `DELETE_BROWSING_DATA`, androidx.webkit ≥ 1.13; we pin 1.16) deletes
  **network cache, cookies, and all JavaScript-readable storage + service workers**
  for a site, honestly. This is the only honest per-site primitive on Android:
  `WebStorage.deleteOrigin()` reliably covers only WebSQL, `CookieManager` has no
  delete-by-domain (the expire-by-rewrite hack misses `HttpOnly`), and
  `WebView.clearCache()` is whole-profile.

So a full, honest **per-site** clear is achievable natively on both mobile platforms —
something desktop's WebEngine cannot do. **Visited links** still have no public clear
API on either platform and remain out of scope (distinct from back/forward history,
which `clearHistory()` covers).

Separately, Chrome's force reload (Cmd+Shift+R) is a **cache bypass** — refetch every
resource from the network for one navigation without evicting the stored cache — which
the library's soft `reload()` does not offer.

## Decision

Model the two canonical browser actions as **two honest, separate capabilities**, and
clear web data **natively** on mobile — never porting desktop's JS-injection hybrid.

```cpp
// Clear browsing data (profile-wide, category-selectable) — WebEngine-shaped verbs
Q_INVOKABLE void clearHttpCache();     // whole-profile HTTP cache
Q_INVOKABLE void deleteAllCookies();   // all cookies, all origins
Q_INVOKABLE void clearDomStorage();    // localStorage/IndexedDB/SW/Cache API, all origins
Q_INVOKABLE void clearProfileData();   // = all three (the "all categories" shortcut)

// Clear current site data (all-or-nothing, current URL's site)
Q_INVOKABLE void clearSiteData();      // full native per-site wipe, then cache-bypass reload
Q_PROPERTY(bool clearSiteDataSupported READ clearSiteDataSupported CONSTANT)

// Force reload
void reloadAndBypassCache();           // ↔ QWebEnginePage::ReloadAndBypassCache
```

Semantics:

- **Two commands, two scopes — never a hybrid.**
  - **Clear browsing data** is the only clear that exposes **category** choice. The
    host composes its own checkbox UI and calls the granular verbs (or
    `clearProfileData()` for all). No flags enum — named WebEngine-shaped verbs keep
    migration from WebEngine hosts friction-free.
  - **Clear current site data** (`clearSiteData()`) is **all-or-nothing** for exactly
    one site: cookies, cache, DOM storage, and service workers. It takes **no origin
    argument** — the site is derived from the WebView's current `url`. Clearing an
    arbitrary off-screen origin is deliberately not offered (it would not be "current
    site" and its reload would be meaningless).
- **Auto cache-bypass reload.** `clearSiteData()` completes with
  `reloadAndBypassCache()` on the current view, so the user immediately sees a fresh
  site without evicting other sites' cache.
- **Per-site is host/eTLD+1 granular**, best-effort — sibling schemes / subdomains may
  clear together (Apple records and Android `deleteBrowsingDataForSite` both key on the
  registrable domain).
- **Capability reporting.** `clearSiteDataSupported` mirrors the existing
  `findSupported` pattern. Apple: always `true`. Android: reflects
  `WebViewFeature.isFeatureSupported(DELETE_BROWSING_DATA)`. Hosts bind their
  "Clear current site data" control's visibility to it
  (`visible: webView.clearSiteDataSupported`).
- **Unsupported / empty target ⇒ no-op + warn + completion.** If `clearSiteData()` is
  called when the feature is unavailable (Android without `DELETE_BROWSING_DATA`), or
  when the current `url` has no clearable host (`about:blank`, `data:`, empty), it does
  nothing, logs, and still emits `clearSiteDataCompleted`. There is **no partial JS
  fallback** — a silently weaker clear (storage-only, missing `HttpOnly` cookies and
  network cache) would be more dangerous than an honest no-op.
- **Completion signals + Clearing.** Every clear method is `void` and
  fire-and-forget, but the backend emits parameterless completion signals
  (`clearHttpCacheCompleted`, `deleteAllCookiesCompleted`, `clearDomStorageCompleted`,
  `clearProfileDataCompleted`, `clearSiteDataCompleted`) and exposes a read-only
  `clearing` property (see CONTEXT: **Clearing**) so hosts can block UI and reload
  when a clear finishes.
- **Overlapping clears are allowed.** A second call to the same clear verb while the
  first is in flight is valid. Each call owns its own completion: both must emit their
  `*Completed` signal and both count toward **Clearing**. Completion signal **order is
  not guaranteed**. Overlapping `clearSiteData()` may each trigger their own
  cache-bypass reload. Platform impls must not share a single pending-completion slot
  (Android correlates Java→C++ callbacks with a per-call `requestId`, mirroring how
  Darwin captures each completion in its own `completionHandler` block). Sync Android
  clears (`clearHttpCache`, `clearDomStorage`) still report completion after the work
  runs on the UI thread — there is no OS store callback, unlike cookies / site data.
- **Profile-shared side effect.** Data lives in the Storage Profile (`storageName`,
  ADR 0001); clearing affects **every** view sharing that profile, and in incognito
  operates on the ephemeral store. Documented, not prevented.
- **No live native view ⇒ no-op + warn**, as with all clears (called before first
  init, mid profile-switch, or with no native handle).

**Force reload** is a per-view **cache bypass** for one navigation — it does not evict
the cache and does not touch cookies/storage (see CONTEXT: *cache eviction vs. cache
bypass*). Apple: `WKWebView.reloadFromOrigin()`. Android:
`settings.setCacheMode(LOAD_NO_CACHE)` → `reload()` → restore `LOAD_DEFAULT` on the next
`onPageFinished`. Requires a live native view; a no-op otherwise, like `reload()`.

## Considered alternatives

- **Single "clear site data" action (desktop's model).** Rejected: it matches neither
  canonical browser action and, on desktop, is a dishonest global-cookies/cache +
  current-origin-storage hybrid. Two honest commands map to real user intent.
- **Per-site DOM-storage-only clear (`clearDomStorage(origin)`), the original 0004
  decision.** Rejected/removed: it silently omitted cookies and cache, so "forget this
  site" left the user logged in. Android's `deleteBrowsingDataForSite` and Apple's
  per-record removal make a full, honest per-site clear possible, so the crippled
  variant is dropped.
- **Partial JS fallback for `clearSiteData()` on old Android WebView.** Rejected:
  `site_utils.js` clears DOM storage only and cannot touch `HttpOnly` cookies or the
  network cache; presenting that as "clear site data" would be dishonest. Gate on
  `clearSiteDataSupported` instead.
- **`clearSiteData(origin)` for arbitrary origins.** Rejected: not "current site", and
  a following cache-bypass reload would be meaningless. A settings-screen site list is
  a separate future feature if needed.
- **A composable flags enum** (`clear(DataTypes, origin)`). Rejected in favor of named
  WebEngine-shaped verbs.

## Consequences

- New cross-platform surface on `MobileWebViewBackend`: the profile-wide verbs
  (`clearHttpCache`, `deleteAllCookies`, `clearDomStorage`, `clearProfileData`),
  `clearSiteData()` + `clearSiteDataSupported`, and `reloadAndBypassCache()`. The prior
  `clearDomStorage(const QString &origin)` overload is **removed**.
- Platform impls: Apple uses `WKWebsiteDataStore.removeData` (per-type profile-wide;
  per-record for `clearSiteData`) and `reloadFromOrigin`; Android uses
  `CookieManager`/`WebStorage`/cache/cache-mode for profile-wide verbs and
  `WebStorageCompat.deleteBrowsingDataForSite` for `clearSiteData`.
- **`clearSiteData()` now requires an OS/WebView floor on Android**
  (`DELETE_BROWSING_DATA`), reported via `clearSiteDataSupported` — reversing the
  original 0004 note that clearing needed no capability gating. The profile-wide verbs
  still need no floor and work on the pre-113 default profile.
- **Platform divergence from Status Desktop, accepted and deliberate.** On mobile,
  "Clear current site data" is a full, honest native wipe (cookies + cache + storage +
  SW). On desktop (WebEngine), the same user action can only clear current-origin web
  storage via injected JS — cookies and cache stay profile-wide — because WebEngine
  exposes no honest per-site primitive. The two platforms therefore give this action
  **different guarantees**; the mobile library defines the honest contract and does not
  emulate desktop's hybrid. (Desktop lives in a separate repository; only the library
  contract is governed here.)
- Mobile does **not** reuse desktop's storage-clearing JS snippet.
- Visited-link clearing remains unavailable on mobile.
