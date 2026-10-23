# Bitcoin Core Android native binary slot

The native engine has not been built or included in this source ZIP. The node service looks for an executable Bitcoin Core `bitcoind` build packaged as `libbitcoind.so` in this ABI directory. Do not rename an arbitrary desktop/Linux binary to this filename: it must be compiled for Android `arm64-v8a`, use Android's dynamic linker and API level compatible with this app, and be validated on a real device.

When the Android-targeted executable is available, place it here as `libbitcoind.so` so Gradle packages it into the app's native library directory. The app runs it as a read-only APK-packaged executable. The corresponding source/build process and checksums should be documented before shipping a release.
