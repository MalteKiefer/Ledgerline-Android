# Prebuilt libsodium (16 KB page-aligned)

`libsodium.so` here is built from source to be **16 KB page-aligned** (Android 15+
requirement) — the upstream `lazysodium-android` 5.1.0 ships a 4 KB-aligned build.

Built with NDK r27c from the libsodium 1.0.20-stable release tarball:

```sh
export ANDROID_NDK_HOME=$ANDROID_SDK/ndk/27.2.12479018
export NDK_PLATFORM=android-24
export LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
./dist-build/android-armv8-a.sh   # -> arm64-v8a/libsodium.so
./dist-build/android-x86_64.sh    # -> x86_64/libsodium.so
```

`packaging.jniLibs.pickFirsts += "**/libsodium.so"` makes these win over the
lazysodium-bundled copy; `ndk.abiFilters` restricts to arm64-v8a + x86_64.
