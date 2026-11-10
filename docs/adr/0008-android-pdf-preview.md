# 8. Android PDF previews through PdfRenderer

Date: 2026-11-04

## Status

Accepted

## Context

The Android system WebView has no PDF viewer: a PDF handed to it turns into a
Download. For a `content://` URI the WebView asks the provider for the MIME
type and starts that Download before any request reaches
`shouldInterceptRequest`, so serving a viewer in place of the document is not
possible there. A page whose base URL is `content://` cannot load `content://`
subresources either; the renderer blocks them before the browser sees them.

Every provider measured for "Open with" (Files by Google, Gmail, Drive) hands
out a seekable file descriptor, which is what `PdfRenderer` needs.

## Decision

`loadFileUrl()` on Android sniffs the content for a `%PDF-` signature. A PDF
opens a `PdfRenderer` on the provider's (or the file's) own descriptor; only a
descriptor that cannot seek is copied to the app cache first, up to 200 MB. The
first copy in a process clears the copies an earlier run left behind. Content that
cannot be read before the signature (gone, a lapsed grant) is not a PDF: the
WebView loads it and reports what it finds. The view then loads a viewer page
with `loadDataWithBaseURL(url, html, …, historyUrl = url)`, so the Tab still
shows the document's URL and title.

Opening happens on a background thread of the view's own, since the provider,
a copy or a long document can take a while and `loadFileUrl` is called on the
Qt thread. The
load is applied on the UI thread unless a later load came first; a superseded
PDF is closed.

The viewer page lists one lazily loaded image per page, sized to the page's
shape. Images come from `https://pdf.mobilewebview.invalid/<token>/<page>?w=…`:
a host no network serves, which the WebView always offers to
`shouldInterceptRequest`, under a random token per open document so no other
page can request them. The view renders each page with `PdfRenderer` at the
requested width (bounded) and answers with a PNG. A PDF `PdfRenderer` refuses
(corrupt, password-protected) gets a page that says it can't be shown.

From Android 15, where `PdfRenderer` reads page text, each page also gets a
transparent text layer, so the WebView's own text selection, copy and
find-in-page work on the document. A page's text comes from
`…/<token>/<page>/text` as runs: words the page draws side by side on one row,
each placed over its rectangle and stretched to its width. `getTextContents()`
gives the page's text without positions; selecting each word by index gives its
rectangle, and a word drawn on several rows is placed character by character.
Runs break at line breaks, at gaps wider than the text is tall and where the
text jumps to another row: pdfium reports a row spread across the page, or two
cells of a grid, as one line in one wide rectangle. The viewer loads every
page's text, visible pages first, so find-in-page covers the whole document.

`MobileWebViewCapabilities::isPdfViewerSupported()` answers true on Android and
Darwin (WKWebView renders PDF itself).

## Consequences

- One Web View per Tab still: zoom, snapshot, freeze and history work as for
  any page.
- Before Android 15 pages are images only: no selection or find-in-page.
  `PdfRendererPreV` brings the same text API to Android 12–14 with SDK
  extension 13, at the cost of a second renderer type.
- Text costs about 9 ms a page on a Nothing Phone (3a); a 49-page document is
  searchable about 3 s after it opens. Until a page's text arrives, find does
  not see it.
- Find-in-page matches within a run: a phrase that wraps onto the next line, or
  crosses a gap, is not found, though each of its words is.
- The session closes with the next `loadFileUrl` or the view.
- pdfium parses files other apps hand over inside the app's own process: a
  crash in it closes the app, not one Tab, and before Android 15 it is only
  patched with the OS. Accepted for now; an `isolatedProcess` service (as
  androidx.pdf does) would contain it.
- Only the viewer page runs script. Any other preview, the file's own page, the
  media and image pages, loads with JavaScript off: a provider can serve
  `text/html` under any name.
