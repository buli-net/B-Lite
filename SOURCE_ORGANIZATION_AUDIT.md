# Source organization audit

## Scope

Baseline: source ZIP `8.0.31`. The original revision reorganized Java source files by responsibility. A later backup follow-up adds selectable backup/restore for app data categories while preserving wallet, transaction, NFC, forwarding, signing, fee, and synchronization behavior. No Java source file was deleted, and no dependency/framework was added.

## Relocations

| Previous location | New location | Reason |
|---|---|---|
| `wallet/tools/NfcHardwareSecurity.java` | `wallet/nfc/NfcHardwareSecurity.java` | NFC tag security belongs with the NFC implementation. |
| `wallet/tools/NfcReaderActivity.java` | `wallet/nfc/NfcReaderActivity.java` | NFC reader screen. |
| `wallet/tools/NfcWriteActivity.java` | `wallet/nfc/NfcWriteActivity.java` | NFC writer screen. |
| `wallet/tools/NfcCopyActivity.java` | `wallet/nfc/NfcCopyActivity.java` | NFC copy screen. |
| `wallet/tools/NfcSecurityActivity.java` | `wallet/nfc/NfcSecurityActivity.java` | NFC card security screen. |
| `wallet/util/Nfc.java` | `wallet/nfc/Nfc.java` | NFC protocol/helper implementation belongs with the NFC feature, not general utilities. |
| `wallet/tools/CoinControl.java` | `wallet/send/CoinControl.java` | Coin selection is part of transaction spending. |
| `wallet/tools/CoinControlActivity.java` | `wallet/send/CoinControlActivity.java` | UI for spend coin selection. |
| `wallet/tools/PaperWalletActivity.java` | `wallet/paperwallet/PaperWalletActivity.java` | Paper-wallet feature screen. |
| `wallet/tools/PaperWalletPrintAdapter.java` | `wallet/paperwallet/PaperWalletPrintAdapter.java` | Printing support for the paper-wallet feature. |
| `wallet/tools/WalletImportWifActivity.java` | `wallet/importwallet/WalletImportWifActivity.java` | Private-key/WIF wallet import flow. |
| `wallet/tools/WatchOnlyWalletActivity.java` | `wallet/watchonly/WatchOnlyWalletActivity.java` | Watch-only wallet management. |
| `wallet/tools/Aes256.java` | `wallet/crypto/Aes256.java` | Cryptographic helper used by the key-encryption implementation. |
| `wallet/tools/Base58Check.java` | `wallet/crypto/Base58Check.java` | Base58Check encoding/decoding. |
| `wallet/tools/Bip38Encoder.java` | `wallet/crypto/Bip38Encoder.java` | BIP38 private-key encryption. |
| `wallet/tools/Hashing.java` | `wallet/crypto/Hashing.java` | Cryptographic hashing helper. |
| `wallet/tools/Scrypt.java` | `wallet/crypto/Scrypt.java` | Scrypt implementation for key encryption. |
| `wallet/tools/WifCodec.java` | `wallet/crypto/WifCodec.java` | WIF key decoding. |
| `wallet/main/BitcoinSyncBootReceiver.java` | `wallet/sync/BitcoinSyncBootReceiver.java` | Boot/restart receiver for background synchronization. |
| `wallet/main/BitcoinSyncService.java` | `wallet/sync/BitcoinSyncService.java` | Background synchronization service. |
| `wallet/main/SyncActivity.java` | `wallet/sync/SyncActivity.java` | Synchronization status/settings screen. |
| `wallet/main/NetworkActivity.java` | `wallet/network/NetworkActivity.java` | Network selection screen. |
| `wallet/main/NetworkConfig.java` | `wallet/network/NetworkConfig.java` | Shared network configuration and network-specific parameters. |
| `wallet/main/CurrencyUnitActivity.java` | `wallet/settings/CurrencyUnitActivity.java` | Currency display settings. |
| `wallet/main/MoneyFormatter.java` | `wallet/util/MoneyFormatter.java` | Shared monetary formatting utility. |
| `wallet/main/WalletAddressType.java` | `wallet/core/WalletAddressType.java` | Shared wallet address-type/domain logic. |
| `wallet/main/WalletSelection.java` | `wallet/core/WalletSelection.java` | Shared wallet-selection and spend-selection logic. |
| `wallet/main/BaseActivity.java` | `wallet/ui/BaseActivity.java` | Shared base UI activity. |
| `wallet/main/LocalizedErrors.java` | `wallet/ui/LocalizedErrors.java` | Shared mapping from internal errors to localized UI messages. |

## Selective backup/restore follow-up

The wallet file remains mandatory and continues to contain bitcoinj wallet state, transaction history, watch-only addresses, and imported private keys. The backup chooser can additionally include or omit these preference-backed categories independently:

- Forwarding configuration, history ledger, TXIDs, and recovery progress.
- Address-book contacts (names and public addresses).
- Imported-wallet registry metadata (labels, address types, and BIP38 source strings).
- Request metadata (public-key/address mapping and selected watch/imported address).
- Safe application settings (Bitcoin display unit/precision and the active network's sync mode).

New bundle backups put selected categories in `appdata.json`. Restore applies only categories present in that manifest. Legacy raw `.wallet` backups and earlier ZIP bundles containing `forwarding.json` remain supported. Blockchain/cache data is intentionally excluded because it can be rebuilt.

Files changed in this follow-up: `wallet/backup/BackupActivity.java`, `wallet/backup/ForwardingBackupBundle.java`, `wallet/main/MainActivityPresenter.java`, `res/values/strings.xml`, and `res/values-vi/strings.xml`.

## Package responsibilities after the audit

### `wallet`

`Constants.java`

### `wallet.about`

`AboutActivity.java`

### `wallet.backup`

`BackupActivity.java`, `BackupRecoveryActivity.java`, `RestoreActivity.java`

### `wallet.contacts`

`AddressBookActivity.java`, `AddressBookStore.java`

### `wallet.core`

`WalletAddressType.java`, `WalletSelection.java`

### `wallet.crypto`

`Aes256.java`, `Base58Check.java`, `Bip38Encoder.java`, `Hashing.java`, `Scrypt.java`, `WifCodec.java`

### `wallet.forwarding`

`ForwardingServiceActivity.java`, `ForwardingTransactionDetailActivity.java`

### `wallet.importwallet`

`WalletImportWifActivity.java`

### `wallet.main`

`MainActivity.java`, `MainActivityContract.java`, `MainActivityPresenter.java`

### `wallet.model`

`TransactionItem.java`

### `wallet.network`

`NetworkActivity.java`, `NetworkConfig.java`

### `wallet.nfc`

`Nfc.java`, `NfcCopyActivity.java`, `NfcHardwareSecurity.java`, `NfcReaderActivity.java`, `NfcSecurityActivity.java`, `NfcWriteActivity.java`, `PaymentRequestHceService.java`

### `wallet.paperwallet`

`PaperWalletActivity.java`, `PaperWalletPrintAdapter.java`

### `wallet.qr`

`QrCodeGenerator.java`, `ReceiveQrDialog.java`

### `wallet.request`

`RequestCoinsActivity.java`

### `wallet.security`

`SecurityActivity.java`, `WalletSecurity.java`

### `wallet.send`

`CoinControl.java`, `CoinControlActivity.java`, `NestedSegwitSpend.java`, `SendActivity.java`, `SendPresenter.java`, `SendTransactionPreview.java`, `WalletScopedSpend.java`

### `wallet.settings`

`CurrencyUnitActivity.java`

### `wallet.storage`

`ImportedWalletStore.java`, `WalletFileMigration.java`

### `wallet.sync`

`BitcoinSyncBootReceiver.java`, `BitcoinSyncService.java`, `SyncActivity.java`

### `wallet.transaction`

`TransactionAdapter.java`, `TransactionDetailActivity.java`, `TransactionMapper.java`

### `wallet.ui`

`BaseActivity.java`, `LocalizedErrors.java`, `TextViewUtils.java`

### `wallet.util`

`MoneyFormatter.java`

### `wallet.watchonly`

`WatchOnlyWalletActivity.java`

### `wallet.widget`

`BalanceWidgetProvider.java`

## Intentionally unchanged areas

- `wallet.main` contains only the main screen, its presenter, and its contract. Feature classes are not placed there merely because the main screen launches them.
- Backup/restore, contacts, transaction details, QR/payment request, send/spend, security, storage, widget, about, and transaction-model classes remain in their existing feature-aligned packages.
- Android resources remain in their standard `res/layout`, `res/menu`, `res/drawable`, `res/xml`, and `res/values*` directories. Android resource files are organized by resource type, not by Java package; their existing activity/feature naming is preserved.
- No file was removed merely because a heuristic did not find a reference. Dead-code removal needs stronger evidence than a text search, especially for Android components and reflection/resource entry points.

## Static checks performed

- 60 Java source files retained; 29 source files moved; 25 Java packages in the resulting tree.
- Java package declarations match their directory paths; no duplicate top-level fully-qualified Java class names found.
- Internal `wallet.*` imports resolve to source classes; no unresolved internal imports found by the static scan.
- All 27 wallet-owned component class names in `AndroidManifest.xml` resolve to Java source files.
- All 96 XML files under `app/src/main` parse successfully. Static checks found no missing app-owned resource references among the resource types checked.
- No references to the old package paths for the moved classes remain in source XML/Java.

## Build status

A Release Java compile could not be run in this environment: the Gradle wrapper had to download Gradle 9.6.0, but resolving `services.gradle.org` failed with `UnknownHostException`. This is an environment/network blocker, not evidence that the build passes or fails. Run GitHub Actions before treating this revision as build-verified.

## Full source audit — latest v8.0.40 backup/restore baseline

This section records the subsequent cleanup pass based on the ZIP that includes the `Sha256Hash` package correction. It supersedes earlier file-count notes for this revision where counts differ.

### Cleanup and localization

- Audited all 61 Java source files and all resources under `app/src/main`; no source Java files were deleted. Removed only private/public helper methods with no source call sites after checking their intended use, and retained the wallet-sweep implementation after wiring the existing “sweep current wallet balance” button to it.
- The wallet-sweep button now saves and validates its current configuration, checks that forwarding is enabled and the active wallet is ready/unlocked, then asks for explicit confirmation before requesting the sweep. It displays localized success/failure messages.
- Removed five stale forwarding UI strings from both English and Vietnamese resources: the former fee-preview title/empty/value strings and the former status title/disabled-by-user strings. Retained all strings required by the whole-wallet sweep action.
- Added localized messages for malformed/unsupported backup archives, oversized app metadata, storage-directory errors, and app-data restore failures. Known backup exception messages are mapped in `LocalizedErrors` so they do not appear verbatim as English implementation messages.
- Corrected backup and restore success copy so it does not claim forwarding history was included/restored when that optional category was not selected.
- Updated workflow/artifact labels and removed personal account identifiers from workflow author metadata; replaced the project-specific name in code comments/thread labels with generic wallet wording.

### Static checks

- Parsed all XML files under `app/src/main` successfully.
- English and Vietnamese resource keys are in parity; no duplicate named entries were found in either string resource file.
- The resource reference scan found no unreferenced strings, drawables, layouts, menus, XML resources, or styles. The `light_system_bars` boolean appears unreferenced in the quick scan but is referenced through styles and was retained.
- The Java literal scan found no directly hardcoded user-facing prose in the UI method patterns. The remaining English literals identified by the broad scan are implementation/error identifiers, archive field names, date formats, library exception text that is mapped where backup UI exposes it, or NFC vCard wire-format fields; these must not be translated into localized resources because doing so would alter data/protocol behavior.
- No debug logging calls (`Log.*`, `System.out`, `System.err`, `printStackTrace`, or Timber) were found in app Java sources.
- No APK/AAB/DEX/class files, build/cache folders, logs, temporary, backup, or editor artifact files were included in the ZIP.
- No personal account name/email or project-specific branding token was found in the audited source/workflow text after cleanup.

### Build status

The ZIP and source/resource checks are static checks only. A Release Java compile could not be confirmed in this environment because Gradle attempted to download its configured distribution and the request failed with a network/DNS error. Run the repository's GitHub Actions build before treating this revision as build-verified.
