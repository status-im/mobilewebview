# MobileWebView — Context Glossary

A glossary of the domain language used across the MobileWebView library. Definitions
only — no implementation details.

## Terms

### Storage Profile
The set of persisted web data bound to a WebView: cookies, HTTP cache, and DOM
storage (localStorage, IndexedDB, service workers). A WebView always reads and writes
through exactly one Storage Profile for its lifetime.

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

### Profile-wide clear
Erasing web data across an entire Storage Profile — every origin at once (all
cookies, the whole HTTP cache, all DOM storage). The coarse "clear browsing data"
action.

### Per-site clear
Erasing **DOM storage** for a single site, leaving every other site in the same
Storage Profile untouched. The "forget this site" action. Cookies and HTTP cache
are **not** cleared per-site (no honest native primitive exists for them at that
granularity), mirroring desktop, where the only per-origin clear is DOM storage.
Granularity is **best-effort at host level**, not strict scheme+host+port: the
platforms group stored data by host/eTLD+1, so sibling schemes or subdomains may be
cleared together.

### Cache eviction vs. cache bypass
Two distinct operations on the HTTP cache. **Eviction** (`clearHttpCache`) deletes
stored cache entries from the Storage Profile. **Bypass** (force reload) leaves the
cache populated but refetches every resource from the network for a single
navigation, ignoring what's stored. Eviction is profile-wide and persistent; bypass
is per-view and one-shot.

### Download
A file transfer originating from page content that the WebView will not render
inline (a response marked as an attachment, an unrenderable MIME type, or an
`<a download>` link). The library performs and tracks the transfer itself; it is
not a navigation.
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
