# 5. Downloads: library-driven transfer with host-chosen target

Date: 2026-06-25

## Status

Accepted

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

- **Triggers (v1):** page-initiated — `Content-Disposition: attachment`,
  non-renderable MIME types, and `<a download>` links — plus an explicit
  `downloadUrl(url, suggestedFileName)` slot. The explicit trigger reuses the same
  transfer machinery at near-zero marginal cost, and it is what makes host-side
  retry of a failed download possible in v1 and covers context-menu "save link".
  "Save page as MHTML" is deferred.
- **Inline Downloads (`blob:` / `data:`):** page-generated files
  (`URL.createObjectURL` + `<a download>`) are supported via a library-owned
  user-script + bridge path (FileReader → base64 → native bridge → decode → write
  on accept). Network self-fetch still rejects bare blob/data URLs without a
  payload. Cross-platform parity uses the script path on both Apple and Android
  (not WKDownload-only for blobs).
- **Async accept model:** when a Download is detected the backend emits
  `downloadRequested(MobileWebViewDownload* download)` carrying metadata only
  (source URL, suggested filename, MIME, expected size) before any bytes are written.
  The host calls `download.accept(target)` with a **host-chosen Download Target**
  (path/content URI) or `download.cancel()`. With no handler / no accept, the
  Download is cancelled (safe default — nothing is written to an arbitrary location).
  A default download directory is deliberately host policy, not a library property:
  a host wanting Chrome-like "always save to X" implements it in one line of its
  `downloadRequested` handler. A Request left pending is cancelled on backend
  destruction and on profile switch; there is no wall-clock timeout in v1 (a pending
  Request costs nothing on Android — the fetch has not started — and holds the
  response connection open on Apple, which we accept).
- **`MobileWebViewDownload`** is a `QObject` exposed to QML: `downloadId` (not
  QML's reserved `id`), `url`, `suggestedFileName`, `mimeType`, `totalBytes`
  (−1 if unknown), `receivedBytes`, `state` (Requested / InProgress / Completed /
  Paused / Cancelled / Interrupted), `destinationPath`, `errorString`; method
  `accept(target)` / `cancel()` / `pause()` / `resume()` / `retry()`; `isPaused`;
  signals `stateChanged`, `receivedBytesChanged`, `totalBytesChanged`, `finished`.
  Transfer speed is left to the host to derive from `receivedBytes` deltas.
- **Lifecycle ops:** cancel, pause/resume (non-terminal **Paused** state;
  `isPaused` for WebEngine-shaped bindings), and `retry()` on a terminal
  Interrupted/Cancelled Download which emits a **new** Download Request (new id)
  rather than reviving the dead object. Pause/resume: `WKDownload` resume data on
  Apple; HTTP `Range` with append to the host Download Target on Android
  self-fetch (partial bytes kept at the destination across pause; cancel deletes
  them) — **not** the system `DownloadManager`. Inline Downloads: pause/resume
  are not applicable (bytes are written on accept). Process-death survival /
  foreground service remains future work.
- **Download Request policy** (scheme support + filename guessing, including
  Content-Disposition) lives in common C++ (`DownloadPolicy`). Platforms pass raw
  inputs (URL, optional platform suggestion such as WKDownload `suggestedFilename`,
  Content-Disposition, MIME); Java does not re-implement the policy.
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
  - Android: **self-fetch** on a background thread (reusing the WebView's cookies
    and User-Agent, including the per-view `httpUserAgent` when set, ADR 0006)
    writing to the host-supplied Target, so we get real progress — rather than the
    system `DownloadManager`, which would fork the cookie jar, own the notification
    UI, and leak Incognito URLs into the system download log. This mirrors
    production browsers: Chromium/Brave on Android run the transfer in-process via
    their own network stack with profile cookies, and touch the system
    `DownloadManager` only to register *completed* files.
  - Android, on completion: register the finished file with the system Downloads UI
    (MediaStore / `addCompletedDownload` equivalent) — **Standard mode only**;
    Incognito downloads are never registered with the system.
- **Incognito:** downloads from an Incognito WebView are allowed (files saved to
  disk are an explicit user choice, as in all major browsers); the only difference
  is the absence of system-UI registration above. A stricter policy (e.g. blocking
  downloads in Incognito) is host policy via the `downloadRequested` handler.
  - In-progress downloads do not survive process death in v1 (they die with the
    app). Resumption / foreground-service hardening is explicitly future work.

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
- Pause/resume, MHTML/save-page, a unified download list, and process-death
  survival are explicitly out of scope for v1 and extend this model later; the
  pause/resume path is pre-committed above (resume data / Range requests).
