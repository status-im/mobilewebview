# 2. Apple (iOS/macOS) website data stores

Date: 2026-06-17

## Status

Accepted

## Context

On Apple platforms persistence is controlled by `WKWebsiteDataStore` set on the
`WKWebViewConfiguration`:

- `defaultDataStore` — a single shared persistent store (no per-account isolation).
- `dataStoreForIdentifier:` — a named persistent store keyed by `NSUUID`, giving true
  per-account isolation. Available **iOS 17+ / macOS 14+**.
- `nonPersistentDataStore` — in-memory only, the natural incognito store.

The project targets Qt 6.11, whose baseline is **iOS 17 / macOS 13**. `dataStoreForIdentifier:`
requires **iOS 17+ / macOS 14+**, so it is universally available on iOS but not on macOS 13.

**macOS 13 is explicitly out of scope.** We raise the macOS deployment target to 14 so the
identified-store API is available unconditionally and there is exactly one code path.

We want real per-account isolation (parity with desktop's `Profile_<userId>`), and a
stable mapping so the same account reuses the same store across launches.

## Decision

- Raise `CMAKE_OSX_DEPLOYMENT_TARGET` to **14.0** for macOS (iOS already 17). Drop the
  `defaultDataStore` fallback and any `@available` store-selection branching.
- **Standard**: use `dataStoreForIdentifier:` with a UUID **deterministically derived
  (UUIDv5) from `storageName`**, stable across launches with no persisted lookup table.
- **Incognito**: use `nonPersistentDataStore`. No identifier, freed when the WKWebView
  is destroyed.

## Consequences

- Full per-account isolation on both iOS and macOS; single code path, no version forks.
- macOS 13 is no longer supported by the library (deployment floor raised to 14).
- No bookkeeping file to migrate or corrupt; the identifier is a pure function of
  `storageName`.
- Deleting an account's persisted web data (e.g. on logout/account removal) requires a
  separate `removeDataStoreForIdentifier:` call; that lifecycle is out of scope for the
  initial change and left to the host app.
