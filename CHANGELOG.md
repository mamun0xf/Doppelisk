# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.0] — 2026-11-24

### Added

- Profile export and import. `Export profile (.json)` writes the profile alone;
  `Export profile + data (.zip)` adds the profile's container data. Import
  accepts both and gives an imported profile a fresh id when the original is
  already taken, so nothing is replaced. All file work runs in the root daemon;
  the manager streams through the Storage Access Framework.
- Network policy: `VPN visibility` with `default`, `on`, `off` and `reversed`.
  `default` reports the real VPN state, `off` hides a real VPN, `on` always
  claims one, and `reversed` inverts it. It applies to `NetworkCapabilities`,
  `NetworkInfo`, `LinkProperties` and `NetworkInterface`.
- Region & Carrier now reads the real SIM number. The manager requests
  `READ_PHONE_STATE` and `READ_PHONE_NUMBERS` at startup, which the manifest
  declared but nothing ever asked for.
- SIM slots are no longer capped at two. A profile can present one to four
  slots (`telephony.phone3`, `telephony.phone4`), the editor grows its
  phone-number rows with the declared count, and the validator requires the
  slots to be contiguous.

### Changed

- SIM surface: `SubscriptionManager.getActiveSubscriptionInfoList()` and
  `getActiveSubscriptionInfoForSimSlotIndex(int)` now return one coherent entry
  per profile slot, even on a phone with no SIM at all. A missing slot is
  filled from a real entry when there is one and built from the profile
  otherwise, so the list is never shorter than `getActiveSubscriptionInfoCount()`
  — the invariant SIM-bound apps such as bKash check first.
- Added `getSubscriptionIds`, `isActiveSubscriptionId`,
  `isValidSubscriptionId`, `getSubscriptionId(int slot)`,
  `getDefaultDataSubscriptionInfo`, `getDefaultVoiceSubscriptionInfo`, the
  active-id arrays, `TelephonyManager.getSubscriptionId`,
  `getActiveSubscriptionInfo` and `ServiceState` operator/roaming hooks, plus
  carrier-id and GID1 hiding and per-slot `SubscriptionInfo` fields.
- `SubscriptionInfo` copies are allocated with `Unsafe` instead of
  `Object.clone()`. The class is `Parcelable`, not `Cloneable`, so the old copy
  path threw and never produced an entry.

## [1.1.0] — 2026-11-23

### Added

- The native shim is open source. `libdoppelisk_shim.so` is now built from
  `app/src/main/cpp/doppelisk_shim.c`, with the Makefile, an NDK/CMake path and
  a protocol reference next to it.

## [1.0.0] — 2026-11-23

First public release.

### Added

- Per-app device profiles derived from a chassis catalog, with stable
  identifiers per target package.
- Manager UI: profiles, app browser, app detail, category editor, device picker,
  custom device, audit, and settings.
- Hook coverage for build fields, system properties, identifiers (SSAID, GAID,
  serial, IMEI, MAC), display, hardware, telephony, network, location, sensors,
  camera, WebView and HTTP `User-Agent`, plus root-surface hiding.
- Region and Carrier section: country and operator selection from a 238-country
  catalog, MCC/MNC coherence, timezone and location, and two SIM slots.
- Phone numbers generated from libphonenumber's regional mobile plans, using the
  selected operator's allocated prefix block where the numbering plan defines
  one. Real SIM numbers can be adopted per slot.
- Coherence validation before a profile is applied, and an audit view of what a
  target app observed.
- Per-profile containers for app data and keystore separation.
- Network policy per profile: direct, SOCKS5 exit, or blocked.
- Scope management: lists of apps in scope and apps waiting for scope, with a
  request action.

### Known limitations

- `SubscriptionManager.getActiveSubscriptionInfoList()` is not yet spoofed.
- Hardware-backed attestation and Play Integrity strong verdicts are not forged.
- Release builds are not minified; shrinking needs a tested keep-rule set.
