# Bitcoin Core Pruned Node integration status

## Scope of this first integration layer

- Keeps bitcoinj 0.17.1 as B-Lite's existing wallet engine. It continues to own keys, signing, wallet balance and the existing send/receive/request/coin-control flows.
- Adds a separate Bitcoin Core node status screen and foreground process owner.
- Adds a local JSON-RPC client that talks only to `127.0.0.1`; it does not use third-party APIs.
- Uses separate data directories for Mainnet and Signet.
- Requests Bitcoin Core to run without its wallet (`-disablewallet`), without `txindex`, and with an initial 2048 MiB block/undo pruning target. This target does not represent total node storage.
- Reads blockchain sync progress, validated block height, peer count, local mempool count, pruning metadata, block/undo file size, best block hash and available disk space.

## Native engine status

The app source does not yet contain an Android-compiled Bitcoin Core binary. The supplied `bitcoinj-0.17.1.zip` contains bitcoinj source, not Bitcoin Core source. Therefore this ZIP prepares the Android lifecycle/RPC integration but **does not yet run a full node**. The UI explicitly reports the missing native binary, and Start is disabled until a compatible executable is packaged.

Expected package path for an Android `arm64-v8a` build:

`app/src/main/jniLibs/arm64-v8a/libbitcoind.so`

This must be the actual Bitcoin Core executable cross-compiled for Android, not a desktop/Linux binary renamed to `.so`. The Gradle `packaging.jniLibs.useLegacyPackaging` setting is enabled so the executable is extracted to `nativeLibraryDir`; the process launcher also adds that directory to `LD_LIBRARY_PATH` for bundled native dependencies. Validate its ELF machine type, all non-system dependencies, execution under the app's target SDK, `-version`, clean start/stop, local RPC cookie creation, Mainnet and Signet selection on a real Android device. Add other ABI folders only after building and testing each one.

## Runtime safety decisions

- RPC binds to loopback only and authenticates with a node-generated cookie. Android cleartext networking is allowed only for `127.0.0.1`; cleartext to other domains remains disallowed.
- The existing wallet continues to use bitcoinj and is not given a second source of spendable balance. No code yet merges Core data into wallet transaction history.
- Core's RPC wallet is disabled, so node integration does not create another wallet or a competing key store.
- Node runtime data is kept separately under app-private `files/bitcoin-core/{mainnet,signet}` directories; these paths are separate from bitcoinj wallet directories.
- The Android foreground service is started only by an explicit user action and attempts an orderly RPC shutdown when Stop is selected.

## Required next phases

1. Build and package a supported Bitcoin Core release for Android `arm64-v8a`; verify licensing and upstream signatures and retain build provenance.
2. Run device-level tests for process execution on supported Android versions, storage requirements, service lifecycle, sync recovery, RPC auth, Mainnet/Signet data isolation and graceful stop.
3. Add a persistent transaction-history/index database and block/mempool scanner for wallet-owned scripts. Do not infer complete address history from Core's UTXO set alone.
4. Reconcile the scanner with bitcoinj transactions and handle reorgs, mempool evictions and gaps caused by periods when the app/node was offline.
5. Only after parity tests should selected read-only UI fields switch to Core-derived status; preserve bitcoinj as the transaction signing/broadcast wallet until separately reviewed.

## Documentation references

- Bitcoin Core Android feasibility reference: https://github.com/lndroid/BitcoinCoreAndroid
- Bitcoin Core RPC interface/security: https://github.com/bitcoin/bitcoin/blob/master/doc/JSON-RPC-interface.md
- Bitcoin Core chain status RPC: https://developer.bitcoin.org/reference/rpc/getblockchaininfo.html
- Bitcoin Core data directory and pruned block files: https://github.com/bitcoin/bitcoin/blob/master/doc/files.md
