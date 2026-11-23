# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
