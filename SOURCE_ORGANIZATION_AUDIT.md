# Source organization audit

## Scope

Baseline: source ZIP `8.0.31`. This revision reorganizes Java source files by responsibility. It does not intentionally change wallet, transaction, NFC, forwarding, signing, fee, backup, or synchronization behavior. No Java source file was deleted, and no dependency/framework was added.

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
