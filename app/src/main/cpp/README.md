# Native shim

`libdoppelisk_shim.so` is the below-Java half of the disguise. LSPosed loads it
into the target app process, where it repoints the app's own GOT slots for
`__system_property_*`, `open`/`stat`/`readlink`, `uname`, `getifaddrs`,
`ioctl(SIOCGIFHWADDR)` and the GL/EGL string queries at its own wrappers. Java
decides what the answers should be; the shim only applies them. It also exports
the daemon's `SO_ORIGINAL_DST` lookup. The header comment in `doppelisk_shim.c`
covers the hooking strategy and the configuration snapshot model.

## Line protocol

`NativeShim.buildConfig` in
`hook/src/main/java/com/doppelisk/hook/engine/NativeShim.kt` renders a profile as
one tab-separated directive per line:

| Line | Meaning |
| --- | --- |
| `V<TAB>1` | protocol version, always `1` |
| `P<TAB>key<TAB>value` | system property value to report |
| `H<TAB>path` | path to hide; any path containing this string fails as missing |
| `U<TAB>node<TAB>release<TAB>version<TAB>machine` | `uname` fields to report |
| `F<TAB>path<TAB>content` | replace the whole file |
| `R<TAB>path<TAB>body` | body for a file that cannot be read; used with `X` lines for the same path |
| `X<TAB>path<TAB>key<TAB>value` | replace one `key=value` line inside a real file |
| `M<TAB>iface<TAB>mac` | MAC address for a wireless interface (`wlan0`) |
| `G<TAB>field<TAB>value` | one GL/EGL string: `vendor`, `renderer`, `version`, `glsl`, `egl_vendor` or `egl_version` |

`F` and `R` bodies escape tab, newline and backslash as `\t`, `\n` and `\\`.
Nothing else is escaped, so a fake file can contain arbitrary text.

## Build

### Cross compiler (this machine)

There is no linux-aarch64 NDK toolchain here, so the shim is built with
`aarch64-linux-gnu-gcc` and linked against the device's bionic libraries:

```sh
sudo mkdir -p /opt/android-bionic/lib
sudo cp /proc/1/root/system/lib64/{libc,liblog,libdl}.so /opt/android-bionic/lib/
make -C app/src/main/cpp
```

`BIONIC_DIR` points at those stubs (default `/opt/android-bionic/lib`) and
`JAVA_HOME` at a JDK with `jni.h` (default `/usr/lib/jvm/java-21-openjdk`).
`make -C app/src/main/cpp` writes `app/src/main/cpp/libdoppelisk_shim.so` and copies it to
`app/src/main/jniLibs/arm64-v8a/`, which is where Gradle packages it from.

Two flags are load-bearing:

- `-mno-outline-atomics` — without it the compiler emits calls to
  `__aarch64_swp4_acq` and friends, which bionic does not export, and the
  library fails to load.
- `-nostdlib` — no glibc in the link. Every symbol resolves against bionic at
  runtime, including `__errno` and `__android_log_print`.

`make -C app/src/main/cpp inspect` prints the `NEEDED` entries and the exported `Java_*`
symbols, so a build can be checked without a phone.

### NDK / CMake

Contributors with the NDK can build the same source through
`CMakeLists.txt` in this directory. Gradle does not use it, so it has to be driven directly:

```sh
cmake -S app/src/main/cpp -B build/shim \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-27
cmake --build build/shim
```

Copy the resulting `libdoppelisk_shim.so` into `app/src/main/jniLibs/arm64-v8a/`
if Gradle should package it.

## Verification

The library was checked in a real app process on a Galaxy M21 (Android 16,
arm64): a throwaway APK loaded the shim, installed a config, then loaded a
victim library that only makes plain libc calls. That exercises the same GOT
patching path LSPosed uses, including the case where a library is mapped after
the shim installed. Every hooked call answered with the configured value:
properties (`get`, `find`, `read`, `read_callback`), hidden paths through all
the open/stat/readlink variants, `F`/`R`/`X` file views, `uname`, the GL/EGL
strings and the `/proc/self/{maps,mounts}` view.

Two hooks cannot be observed from an unprivileged app.
`ioctl(SIOCGIFHWADDR)` returns `EACCES` for a normal uid, and `getifaddrs` does
not expose `AF_PACKET` entries, so there is nothing for the wrapper to rewrite.
MAC spoofing is therefore only observable in a privileged process.

## License

[Apache License 2.0](../../../LICENSE). Copyright 2026 Abdullah Al Mamun.
