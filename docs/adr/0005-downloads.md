# 5. Downloads: library-driven transfer with host-chosen target

Date: 2026-06-25

## Status

Proposed

## Context

`MobileWebViewBackend` reaches feature parity with desktop on navigation, history,
storage profiles, and JS injection, but has no notion of file downloads (parity
gap #13, rated High / XL). Desktop (qutebrowser) runs a full self-managed download
manager: it intercepts the response, writes bytes itself, and tracks
progress/state/cancel per item. We want the same capability surface on mobile while
keeping the Status host app declarative, the way storage profiles did (ADR 0001).

The native engines expose very different primitives, and this asymmetry shapes the
whole design:

- **Apple** `WKWebView` (iOS 14.5+ / macOS 11.3+): a navigation/response policy can
  return `.download`, after which a `WKDownload` drives the transfer and asks the
  `WKDownloadDelegate` **asynchronously** for a destination file URL and reports
  progress. There is no download API below that OS floor.
- **Android** `WebView.setDownloadListener`: fires with URL + content-disposition +
  MIME + content-length but performs **no** transfer. We must either hand off to the
  system `DownloadManager` (its own notification UI, separate cookie jar) or fetch
  the bytes ourselves.

A hard mobile constraint also applies: the library cannot freely pick where files
land. Android scoped storage (API 29+) and the iOS app sandbox mean the **host**
must choose the destination (a path or content URI).

## Decision

**The library performs and tracks downloads itself** (not signal-only delegation,
not handing off to the OS download UI), exposing a per-transfer model to QML.

- **Triggers (v1):** page-initiated only — `Content-Disposition: attachment`,
  non-renderable MIME types, and `<a download>` links. Explicit "download this URL"
  and "save page as MHTML" are deferred.
- **Async accept model:** when a Download is detected the backend emits
  `downloadRequested(MobileWebViewDownload* download)` carrying metadata only
  (source URL, suggested filename, MIME, expected size) before any bytes are written.
  The host calls `download.accept(target)` with a **host-chosen Download Target**
  (path/content URI) or `download.cancel()`. With no handler / no accept, the
  Download is cancelled (safe default — nothing is written to an arbitrary location).
- **`MobileWebViewDownload`** is a `QObject` exposed to QML: `id`, `url`,
  `suggestedFileName`, `mimeType`, `totalBytes` (−1 if unknown), `receivedBytes`,
  `state` (Requested / InProgress / Completed / Cancelled / Interrupted),
  `destinationPath`, `errorString`; method `accept(target)` / `cancel()`; signals
  `stateChanged`, `receivedBytesChanged`, `finished`. Transfer speed is left to the
  host to derive from `receivedBytes` deltas.
- **Lifecycle ops (v1):** cancel only. Pause/resume (Apple resume data) and retry are
  deferred.
- **Ownership:** the backend owns each Download object until it reaches a terminal
  state, then `deleteLater`. No global cross-view registry; downloads are surfaced
  per `MobileWebViewBackend`, and the host aggregates if it wants a unified list.
- **Freeze / profile switch:** a Download is independent of view freeze and keeps
  running while the WebView is frozen. A profile switch (`destroyNativeView` →
  `initNativeView`, ADR 0001) **cancels** any in-flight Download bound to the old
  native session, consistent with "the new view inherits nothing."
- **Platform transfer mechanism:**
  - Apple: return `.download` from the response policy and bridge `WKDownload`
    progress/completion into the Download object. Below the OS floor, downloads are
    reported unsupported.
  - Android: **self-fetch** on a background thread (reusing the WebView's cookies and
    User-Agent) writing to the host-supplied Target, so we get real progress — rather
    than the system `DownloadManager`, which would fork the cookie jar and own the UI.

## Considered alternatives

- **Signal-only delegation** (host does the transfer): cheapest, but abandons the
  progress/save-as parity goal and pushes platform networking into every host.
- **OS download UI hand-off** (Android `DownloadManager` / iOS share sheet): minimal
  code but inconsistent cross-platform, no in-app progress, and an authentication
  mismatch (separate cookie store from the WebView session).

## Consequences

- New cross-platform surface: a C++ `MobileWebViewDownload` model, a
  `downloadRequested` signal on the backend, and platform delegates
  (`WKDownloadDelegate` on Apple; a Java `DownloadListener` + background fetcher on
  Android).
- The host gains responsibility for choosing the Download Target and any save-as UI;
  the library never writes to a path it picked itself.
- Below the Apple OS floor downloads are unavailable and reported as such, mirroring
  the storage-profile floor handling.
- Pause/resume, retry, explicit-URL and MHTML downloads, and a unified download list
  are explicitly out of scope for v1 and can extend this model later.
