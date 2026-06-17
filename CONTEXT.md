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
