# B-Lite 9.1.1 sync fix

## Signet block 1 validation crash

The first Signet blocks may use a version value at or above the BIP65 threshold before bitcoinj has enough historical block versions to fill its moving `VersionTally` window. `VersionTally.getCountAtOrAbove()` intentionally returns `null` until that window is full. `NetworkParameters.getTransactionVerificationFlags()` previously compared that nullable `Integer` directly with an `int`, implicitly unboxing it and crashing with `Integer.intValue()` on a null reference.

The code now enables `CHECKLOCKTIMEVERIFY` only when the tally has a non-null count and that count exceeds the configured activation threshold. This does not skip script validation; it applies the activation rule only when sufficient version history exists. A regression test covers a Signet version-4 block with an empty tally.

Build/test status: source-level review completed. Gradle test execution could not start in this environment because `services.gradle.org` was unreachable, so Release build remains unverified until GitHub Actions runs.
