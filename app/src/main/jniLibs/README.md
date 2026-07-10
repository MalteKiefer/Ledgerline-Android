# Prebuilt libsodium (16 KB page-aligned)

`arm64-v8a/libsodium.so` is built from source to be **16 KB page-aligned**, which
Android 15/16 devices running in 16 KB page mode require. The upstream
`lazysodium-android` 5.1.0 ships only a **4 KB-aligned** build, which fails to
`dlopen` on those devices. Everything else the app links (JNA, CameraX, …) is
already 16 KB-aligned; only libsodium had to be rebuilt.

`packaging.jniLibs.pickFirsts += "**/libsodium.so"` makes this copy win over the
lazysodium-bundled one, and `ndk.abiFilters = ["arm64-v8a"]` keeps the app 64-bit
arm only (the physical target device and the Apple-Silicon emulator are both
arm64).

## How it was built

libsodium `stable` branch, NDK r26b. A normal shared-library cross build drops
libsodium's SIMD/crypto convenience-library objects (leaving undefined symbols
like `aes256gcm`/`aegis` that lazysodium binds eagerly at load), so we build a
**static** archive and force every object into the shared object with
`--whole-archive`:

```sh
NDK=$ANDROID_SDK/ndk/26.3.11579264
TC=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin
export CC=$TC/aarch64-linux-android24-clang AR=$TC/llvm-ar RANLIB=$TC/llvm-ranlib

./autogen.sh -s
./configure --host=aarch64-linux-android --disable-shared --enable-static \
    --disable-pie --disable-soname-versions \
    CFLAGS="-Os -fPIC -march=armv8-a+crypto -DNATIVE_LITTLE_ENDIAN=1"
make -j4

$CC -shared -fPIC -o libsodium.so \
    -Wl,--whole-archive src/libsodium/.libs/libsodium.a -Wl,--no-whole-archive \
    -Wl,-soname,libsodium.so \
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
```

Verify alignment with `readelf -l libsodium.so` (LOAD segments `Align 0x4000`).
Verified on-device: the full instrumented crypto + keystore suite passes on a
Pixel 9a running the 16 KB page image.
