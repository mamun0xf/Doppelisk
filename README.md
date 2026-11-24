# Doppelisk

Per-app device identity for Android, with coherence and audit.

Doppelisk is an LSPosed module and manager that gives each app its own consistent
view of the device: build fingerprint, identifiers, display, hardware, telephony,
network and location. Every value comes from one chassis profile and is
validated before it is applied, so the app sees a device that could exist.

Android 8.1+ (API 27) · arm64-v8a · root and LSPosed required

## Features

- **Per-app profiles.** Two apps can see two different devices. The manager
  itself keeps seeing the real one.
- **Chassis-based identity.** Build, hardware, display, camera and sensor values
  are taken from a real device model.
- **Derived identifiers.** SSAID, advertising ID, serial, IMEI and MAC addresses
  are stable per profile.
- **Region and carrier.** Country, operator, MCC/MNC, timezone, location and up
  to four SIM slots. Phone numbers follow the operator's numbering plan, and the
  editor reads the real SIM number once the phone permissions are granted.
- **Network.** Wi-Fi and cellular state, SSID, HTTP and WebView User-Agent,
  optional SOCKS5 exit or block per profile, and a VPN visibility policy
  (`default`, `on`, `off`, `reversed`).
- **Root surface.** su paths, mounts, build tags, package visibility and virtual
  `/proc` and `/sys` entries.
- **Coherence and audit.** Contradictory profiles are rejected before apply; the
  Audit tab shows what a target app actually observed.
- **Containers.** Separate app data and keystore per profile.
- **Portable profiles.** Export a profile as JSON, or as a zip that also
  carries its container data; import either shape. An imported profile that
  collides with an existing id is given a fresh one, never overwritten.
- **No network permission.** The manager cannot send anything off the device.

## Install

1. Download `app-release.apk` from [Releases](../../releases).
2. Install and open it. The daemon is started from the manager.
3. Enable **Doppelisk** in LSPosed and select the apps it should apply to.
4. Force-stop and reopen those apps.

Profiles are stored in `/data/adb/doppelisk/` and survive reinstalls.

## Build

```sh
make -C app/src/main/cpp  # rebuild the native shim into app/src/main/jniLibs
gradle assembleDebug     # debug APK
gradle assembleRelease   # signed release
```

The native library is checked in prebuilt, so `gradle assembleRelease` works
without a C toolchain. `make -C app/src/main/cpp` rebuilds it from
[app/src/main/cpp/doppelisk_shim.c](app/src/main/cpp/doppelisk_shim.c) and needs a
cross compiler plus a copy of the device's bionic libraries;
[app/src/main/cpp/README.md](app/src/main/cpp/README.md) explains
both that path and the NDK/CMake one.

## Limitations

Doppelisk changes what Android APIs report. It does not forge hardware-backed
attestation, so apps using Play Integrity strong verdicts or key attestation can
still detect an altered device. Use it on devices you own, for apps you are
allowed to run.

## Credits

Chassis data is compiled from public firmware metadata and device specifications.
Carrier data comes from libphonenumber and [mcc-mnc-list](https://github.com/pbakondy/mcc-mnc-list).
See [NOTICE](NOTICE).

## Contact

Abdullah Al Mamun · mamun0xf@proton.me

## License

[Apache License 2.0](LICENSE).
