# 7. Local content only through a Read Grant

Date: 2026-11-04

## Status

Accepted

## Context

Hosts open local files in a Web View: downloads, and files the OS hands over
through "Open with". On iOS/macOS WKWebView reads nothing local unless
`-loadFileURL:allowingReadAccessToURL:` grants it. The Android WebView had
`setAllowFileAccess(true)` and `setAllowContentAccess(true)` on every view, so
any view — a browsing tab included — could load `file:///data/user/0/<app>/…`
and show the app's own files (account data, logs). Scoped storage keeps
`/sdcard` out of reach, but not the app's private directories.

"Open with" on Android hands over a `content://` URI with a read grant, which
the WebView reads in place; a copy is not needed (measured: Files by Google,
Gmail, Drive all serve a seekable file descriptor).

## Decision

`loadFileUrl(url, readAccessUrl)` is the only way a view reaches local content,
and it gives that view a **Read Grant**:

- file:// — the file itself (only it is readable) or a directory holding it
  (everything under it is); empty means the file's own directory.
- content:// (Android) — the URI itself; `readAccessUrl` must be empty or the
  same URI.

The common backend refuses a grant that does not cover the file before the
view's URL changes, comparing paths with symlinks resolved as far as they
exist. Whether a platform reads content:// at all is a backend capability
(`readsContentUrisImpl`), not a build switch, so the desktop test runs cover
both rules.

On Android every view starts with no local access (`allowFileAccess` and
`allowContentAccess` off). `loadFileUrl` opens the one scheme its grant needs,
and the view checks every local request against the grant —
`shouldOverrideUrlLoading` for navigations, `shouldInterceptRequest` for
everything else (a refused request gets an empty 403). The grant lasts until
the next `loadFileUrl` on the view, so history within the preview keeps
working. `loadUrl()` / `url` never grants anything.

The Android view checks the rest itself and refuses rather than guesses: a
path the file system cannot name (a NUL byte, a symlink loop) is outside every
grant, and a lowercased `content://` authority (QUrl lowercases hosts) that
two visible providers spell differently names neither. A refused
`loadFileUrl` reports a failed navigation.

## Consequences

- A host that opened local files through `url = file://…` must switch to
  `loadFileUrl` (on iOS that path was already blank).
- A file origin still never reads other local files
  (`allowFileAccessFromFileURLs` and `allowUniversalAccessFromFileURLs` stay
  off).
- The rule lives in `LocalReadGrant`, pure Java, so the JVM tests state it.
