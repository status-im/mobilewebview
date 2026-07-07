# 4. Data clearing (cache, cookies, DOM storage) and force reload

Date: 2026-07-01

## Status

Accepted

## Context

Desktop (WebEngine) offers two data-clearing actions per profile:

- **Clear cache** — a single `QWebEngineProfile::clearHttpCache()`.
- **Clear site data** — a composite: `clearHttpCache()` + `cookieStore()->deleteAllCookies()`
  + `clearAllVisitedLinks()` (all via the WebEngine API), **plus** `localStorage.clear()`,
  `sessionStorage.clear()`, IndexedDB deletion, `caches.delete()`, and service-worker
  `unregister()` executed **as JavaScript injected into the current page** via
  `runJavaScript`.

Desktop injects JS for web storage only because `QWebEngineProfile` has **no per-origin
access** to localStorage / IndexedDB / Cache API. That JS runs inside one page, so it
can only ever clear the **current origin's** web storage, while cookies and cache are
cleared globally. Desktop's "clear site data" is therefore a hybrid: global
cookies/cache, current-origin web storage.

`MobileWebViewBackend` has no data-clearing surface at all. Two platform facts reshape
the desktop design on mobile:

- **The JS workaround is unnecessary.** Apple `WKWebsiteDataStore.removeData(ofTypes:modifiedSince:)`
  clears cache, cookies, localStorage, IndexedDB, service workers, and Cache API
  **natively**, at profile scope and (via `records(ofTypes:)` → `removeData(ofTypes:for:)`)
  per-record. Android `androidx.webkit` exposes `CookieManager`, `WebStorage`
  (incl. `deleteOrigin`), cache clearing, and `ServiceWorkerController` natively.
- **Origin granularity is uneven.** DOM storage can be cleared per-site on both
  platforms (best-effort, host/eTLD+1 grouped). Per-origin **cookie** deletion has no
  honest native primitive: Apple removes cookies only per host-grouped record, and
  Android `CookieManager` has no delete-by-domain API (only an expire-by-rewrite hack
  that misses `HttpOnly`). Per-origin **cache** eviction does not exist on Android and
  is clunky on Apple. **Visited links** have no public clear API on either platform
  (`WKWebsiteDataType` has no such member; Android `WebView` exposes none) — this is
  distinct from navigation (back/forward) history, which the library already clears via
  `clearHistory()`.

Separately, Chrome's force reload (Cmd+Shift+R) is a **cache bypass** — refetch every
resource from the network for one navigation without evicting the stored cache — which
the library's soft `reload()` does not offer.

## Decision

**Clear web data natively on mobile; do not port desktop's JS-injection workaround.**
The workaround exists to route around a WebEngine limitation that the mobile engines do
not have. Reproducing it would carry desktop's scar tissue onto platforms that can clear
web storage natively at any scope, and would leave profile-wide web-storage clearing
impossible (JS only sees the current origin).

Expose WebEngine-shaped, granular, honest methods on `MobileWebViewBackend`:

```cpp
// profile-wide granular (maps 1:1 to WebEngine verbs for easy migration)
Q_INVOKABLE void clearHttpCache();                        // whole-profile HTTP cache
Q_INVOKABLE void deleteAllCookies();                      // all cookies, all origins
Q_INVOKABLE void clearDomStorage();                       // localStorage/IndexedDB/SW/Cache API, all origins

// per-site (DOM storage only — honestly named; no per-site cookies/cache)
Q_INVOKABLE void clearDomStorage(const QString &origin);  // best-effort, host-granular

// profile-wide aggregate (the "clear browsing data" button)
Q_INVOKABLE void clearProfileData();                      // = clearHttpCache + deleteAllCookies + clearDomStorage
```

Semantics:

- **Scope, honestly.** Two explicit scopes — **profile-wide** and **per-site** — never
  a hybrid. Per-site clears **DOM storage only**; cookies and cache are profile-wide
  only, because no honest per-origin native primitive exists for them. This mirrors
  desktop, where the only per-origin clear is DOM storage. Per-site granularity is
  **best-effort at host level** (sibling schemes / subdomains may clear together).
- **Visited links dropped** as a desktop-only capability with no mobile public API.
  Navigation history is unaffected and remains covered by `clearHistory()`.
- **Completion signals + busy property.** Each clear method is still `void` and
  fire-and-forget from the caller's perspective, but the backend exposes parameterless
  completion signals (`clearHttpCacheCompleted`, `deleteAllCookiesCompleted`,
  `clearDomStorageCompleted`, `clearProfileDataCompleted`) and a read-only `clearing`
  property so hosts can block UI and reload when a clear finishes (desktop 2.36 parity).
  Apple invokes completions from native `completionHandler:` callbacks; Android invokes
  them immediately after the synchronous JNI calls (best-effort).
- **Profile-shared side effect.** Data lives in the Storage Profile (`storageName`,
  ADR 0001); clearing via one backend affects **every** view sharing that profile, and
  in incognito operates on the ephemeral store. This matches WebEngine (clear is a
  profile operation) and is documented, not prevented.
- **No live native view ⇒ no-op + warn.** If a method is called before first init,
  mid profile-switch (ADR 0001 `destroyNativeView`→`initNativeView`), or while there is
  otherwise no native handle, it does nothing and logs. A silently-deferred
  fire-and-forget clear would be more surprising than a no-op.

**Add force reload:**

```cpp
public slots:
    void reloadAndBypassCache();   // ↔ QWebEnginePage::ReloadAndBypassCache
```

- Per-view **cache bypass** for one navigation — does not evict the cache, does not
  touch cookies/storage (see CONTEXT: *cache eviction vs. cache bypass*).
- Apple: `WKWebView.reloadFromOrigin()`. Android: `settings.setCacheMode(LOAD_NO_CACHE)`
  → `reload()` → restore `LOAD_DEFAULT` on the next `onPageFinished`, so only this load
  is affected. Requires a live native view; a no-op otherwise, like `reload()`.

## Considered alternatives

- **Keep desktop's shared JS for web storage on mobile.** Rejected: it makes
  profile-wide web-storage clearing impossible (JS is current-origin only) and
  reintroduces the dishonest global-cookies/current-origin-storage hybrid. Native
  clears at any scope with no page loaded.
- **A composable flags enum** (`clear(DataTypes, origin)`). Rejected in favor of named
  WebEngine-shaped methods to minimize migration friction from WebEngine hosts.
- **Best-effort per-site cookies** (`deleteCookies(origin)`). Rejected: Android has no
  honest primitive (expire-by-rewrite misses `HttpOnly`) and it would exceed desktop,
  which never offered per-origin cookie deletion.
- **Completion signal / correlated ids.** Deferred: clears are rare and user-initiated;
  WebEngine parity is fire-and-forget.

## Consequences

- New cross-platform surface on `MobileWebViewBackend`: four clear methods (one
  overloaded for per-site) plus `clearProfileData()`, and a `reloadAndBypassCache()`
  slot. Platform impls call `WKWebsiteDataStore.removeData` / `reloadFromOrigin` on
  Apple and `CookieManager`/`WebStorage`/cache/cache-mode on Android.
- Mobile does **not** reuse desktop's storage-clearing JS snippet; the two platforms
  clear web storage by different mechanisms (native vs injected JS). Accepted.
- Per-site clearing is DOM-storage-only and host-granular; hosts wanting a full
  "forget this site" including cookies cannot get it honestly and must fall back to
  `deleteAllCookies()` (profile-wide).
- Visited-link clearing is unavailable on mobile.
- Clearing works even on the Android pre-113 default profile (no MULTI_PROFILE), so no
  OS-floor gating is needed for these methods, unlike storage-profile isolation
  (ADR 0003) or downloads (ADR 0005).
