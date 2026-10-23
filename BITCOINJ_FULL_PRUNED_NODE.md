# Experimental bitcoinj Full-Pruned Engine

This feature is a research/engineering prototype built from the `FullPrunedBlockChain` and `FullPrunedBlockStore` interfaces in bitcoinj 0.17.1. It is kept separate from the existing `WalletAppKit` SPV wallet; it does not replace wallet balance, wallet history, private-key handling, transaction signing, or broadcast.

## Shared network implementation

Mainnet and Signet use the same Java implementation. The selected wallet network supplies bitcoinj network parameters and selects a separate database directory:

- `files/full-pruned-node/mainnet/full-pruned.sqlite`
- `files/full-pruned-node/signet/full-pruned.sqlite`

The SQLite database contains all block headers observed by this engine, the live UTXO set, and a recent undo-data window of 2,016 blocks. Undo records older than that window are deleted. A deeper reorganization than the retained undo window can require rebuilding the index. This is not a Bitcoin Core-style byte-target prune setting: the live UTXO database and headers remain and can grow, and there is no txindex or complete historical transaction index.

The node downloads full blocks from P2P peers and processes them through bitcoinj's `FullPrunedBlockChain`. It can consume substantial bandwidth, CPU, battery and storage during the initial synchronization. Only one network is run by this service at a time; starting the other network switches the engine while preserving separate on-disk data.

## Consensus limitations — do not use as a production consensus authority

This is not yet a consensus-complete or production-certified full node. Source inspection of bitcoinj 0.17.1 shows that the `FullPrunedBlockChain` verifier passes null for the transaction witness and previous-output value to `Script.correctlySpends`, so native SegWit-v0 P2WPKH spends can fail block verification. Its script interpreter also lacks Taproot/Tapscript consensus validation, and its Signet parameters do not implement BIP325 block challenge-signature validation. Therefore it must not be relied on to independently establish consensus validity on Mainnet or Signet, and the reported height is labeled experimental.

The on-screen warning must remain. Do not connect this engine as the authority for wallet balances or transaction safety. The existing wallet SPV path remains the wallet's operational data source.

## Android operation

The engine is launched only after the user starts it from Utilities. Its network choice is persisted so that a previously enabled engine can be restored after device/package restart. The foreground service uses the `specialUse` type for a persistent local P2P node; distribution through Google Play requires review of that declared use case. User-visible state and errors are stored per network.

The full-pruned engine now observes rejected blocks and verified-height progress. If the same block is rejected repeatedly, it rotates the download peer and then marks the node as stalled with the rejected height, block hash, and reason. If peers remain connected but no verified block progress occurs for roughly two minutes, it rotates the download peer; after two recovery attempts it stops the engine and reports a stalled state rather than continuing to claim that it is running. A later manual start retries the saved database. This is fault detection and bounded recovery, not a substitute for fixing any consensus-verification rejection reported by the screen.

The code has not been verified by a complete Android Release build in this environment because the Gradle 9.6.0 distribution and Android SDK are not available locally. A device/instrumentation validation is required before real-network use.


## Consensus compatibility fix (9.0.7)

Bitcoin Core distinguishes block-consensus script flags from standard transaction relay policy. `SCRIPT_VERIFY_LOW_S` is a standardness rule and is not part of `GetBlockScriptFlags()` / mandatory block script flags. bitcoinj 0.17.1's `Script.correctlySpends()` had hard-coded `requireCanonicalSValue=true` for its P2PKH and simplified P2WPKH branches, which can reject a historically consensus-valid block with a high-S ECDSA signature (`S-value is not canonical`). B-Lite now builds the supplied bitcoinj 0.17.1 core source as a local module and changes those two calls to enforce DER encoding without hard-coding the LOW_S policy. Signature verification is still performed; this does not bypass ECDSA validity checks.

This targeted correction does **not** make bitcoinj FullPrunedBlockChain equivalent to Bitcoin Core: witness validation is still incomplete in bitcoinj 0.17.1, Taproot/Tapscript and Signet BIP325 challenge validation are not fully implemented by this experimental node. Do not treat it as a production-safe consensus implementation until those gaps are implemented and tested against Bitcoin Core vectors.
