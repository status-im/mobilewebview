# MobileWebView — Context Glossary

A glossary of the domain language used across the MobileWebView library. Definitions
only — no implementation details.

## Terms

### Storage Profile
The set of persisted web data bound to a WebView: cookies, HTTP cache, and DOM
storage (localStorage, IndexedDB, service workers). A WebView always reads and writes
through exactly one Storage Profile for its lifetime. Does not include the WebView's
HTTP User Agent.

### HTTP User Agent
The string a WebView presents on HTTP requests to identify itself. Owned by the
WebView, independent of its Storage Profile. An empty value means the platform
default agent string.
_Avoid_: browser identity, profile user agent.

### Standard mode
A WebView backed by a **persistent** Storage Profile. Data survives across app
sessions: cookies keep the user logged in, cache speeds up reloads, DOM storage is
retained.

### Incognito mode (off-the-record)
A WebView backed by an **ephemeral** Storage Profile. Nothing is written to disk;
all cookies, cache, and DOM storage live only in memory and are discarded when the
WebView (or its profile) is torn down. Incognito is only meaningful once Standard
mode is genuinely persistent.

### Partition (storageName)
The identity of a persistent Storage Profile. Two WebViews sharing a partition share
cookies/cache/storage; different partitions are isolated. Mirrors the desktop
`WebEngineProfile.storageName`. Ignored for Incognito (which is never persisted).

### Clear browsing data
Erasing a caller-selected subset of web-data **categories** (cookies, HTTP cache,
DOM storage) across an entire **Storage Profile**, covering every site at once.
The only clear that exposes category choice.
_Avoid_: Clear site data, profile-wide clear.

### Clear current site data
Erasing **all** web data (cookies, cache, DOM storage, service workers) belonging
to the site of the WebView's current URL, while preserving data belonging to other
sites in the **Storage Profile**. Always all-or-nothing for that one site — never
a category subset and never an arbitrary off-screen origin. Site identity is
**best-effort at host/eTLD+1 level**, not strict scheme+host+port. Completes with
a **cache bypass** reload of the current view.
_Avoid_: Clear browsing data, per-site clear, forget this site.

### Cache eviction vs. cache bypass
Two distinct operations on the HTTP cache. **Eviction** (`clearHttpCache`) deletes
stored cache entries from the Storage Profile. **Bypass** (force reload) leaves the
cache populated but refetches every resource from the network for a single
navigation, ignoring what's stored. Eviction is profile-wide and persistent; bypass
is per-view and one-shot.

### Clearing
The busy state of a WebView while one or more **Clear browsing data** or **Clear
current site data** operations on that view have been started and not yet completed.
Overlapping clears are allowed; Clearing remains active until every in-flight clear
has completed. Completion order across overlapping clears is not part of the
contract.
_Avoid_: busy, in-flight clear, clear in progress.

### Snapshot
An asynchronous capture of the WebView's current frame as an image. Taken either
on the host's explicit request or as the first step of a Freeze.
_Avoid_: screenshot, thumbnail.

### Freeze
Replacing the live WebView with its last captured Snapshot rendered in the Qt
scene. While a Freeze is active the native view is hidden and the page appears
static; unfreezing shows the live view again. Used to keep the page visually
present when the native view cannot be (e.g. during scene transitions).
_Avoid_: pause, suspend.

### Download
A file transfer the library performs and tracks on the host's behalf; it is not a
navigation. Triggered either by page content the WebView will not render inline
(a response marked as an attachment, an unrenderable MIME type, an `<a download>`
link) or explicitly by the host (e.g. "save link").
_Avoid_: save, export, fetch.

### Download Request
The moment a Download is detected, surfaced to the host with its metadata
(source URL, suggested filename, MIME type, expected size) before any bytes are
written. A Download Request is pending until the host accepts it (supplying a
Download Target) or cancels it.

### Download Target
The host-chosen destination a Download is written to: a local file path or
platform content URI. The host owns this choice because only it knows the
platform's storage rules (Android scoped storage, app sandbox). Required —
a Download with no Target is never written.

### Download State
The lifecycle stage of a Download: **Requested** (awaiting a Target),
**InProgress** (bytes transferring), **Completed** (fully written),
**Cancelled** (stopped by host or by a profile switch), or **Interrupted**
(failed mid-transfer). A Download in any of the last three is terminal.
