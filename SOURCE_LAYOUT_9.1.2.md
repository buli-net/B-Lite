# Unified source layout — 9.1.2

All application and bitcoinj production code is compiled by the single Android `:app` module.

- Wallet/UI/business code: `app/src/main/java/wallet/`
- bitcoinj 0.17.1 core code: `app/src/main/java/org/bitcoinj/`
- Protocol Buffer definitions: `app/src/main/proto/`
- bitcoinj runtime resources packaged as Android assets: `app/src/main/assets/`
- Preserved bitcoinj unit tests and fixtures: `app/src/test/`
- Protobuf generation (plugin 0.10.0) and all compile/runtime/test dependencies: `app/build.gradle`
- Settings include only `:app`; there is no `bitcoinj-core` subproject or `implementation project(':bitcoinj-core')` indirection.

`wallet.main.BLiteApplication` initializes the resource loader before app activities/services use bitcoinj. Resources are first looked up on the normal JVM classpath (for unit tests), then in the APK assets (for Android runtime).

This change consolidates source placement and build ownership only. It does not claim that the experimental Full-Pruned node has become consensus-complete or that wallet SPV behavior has already been replaced.

## Relocation audit

- 277 original bitcoinj production Java files preserved under the app source tree; three resource lookups were adapted to support Android assets, and the Android resource loader/application initializer were added.
- 110 original bitcoinj test Java files preserved under `app/src/test/java`.
- Both `.proto` definitions and all five original bitcoinj runtime resource files preserved.
- Original core test fixtures retained under `app/src/test/resources`.
- Source package/path scan passed with no mismatches or duplicate top-level types.
- All 97 Android XML files parsed successfully.
- The former `bitcoinj-core` directory, Gradle project inclusion and project dependency are removed.

A complete Release build was not run in this environment because the Gradle distribution and Android SDK are not installed locally. Run GitHub Actions to validate the Gradle 9.6 / Android Gradle Plugin build before treating the ZIP as build-verified.
