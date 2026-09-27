B-Lite

A lightweight, open-source Bitcoin wallet for Android, built with BitcoinJ.

Features

- Bitcoin Mainnet wallet
- Create and load wallet
- Wallet backup and restore
- Send Bitcoin
- Receive Bitcoin
- Transaction history
- Watch-only addresses
- QR code support
- Bitcoin network synchronization
- Light and dark theme support using the Android system theme

Network

B-Lite is designed for Bitcoin Mainnet.

This application does not use Bitcoin Testnet or Signet.

Technology

- Android
- Java
- BitcoinJ
- Gradle

Watch-Only

B-Lite supports watch-only Bitcoin addresses for monitoring balances and transactions.

Watch-only addresses do not contain private keys and cannot be used to spend Bitcoin from the application.

Build

Clone the repository:

git clone https://github.com/buli-net/B-Lite.git
cd B-Lite

Build the release APK:

./gradlew assembleRelease

The APK will be generated in:

app/build/outputs/apk/release/

Security

Wallet data is stored locally on the device.

Always keep a secure backup of your wallet before restoring, moving, or modifying wallet data.

Never share your wallet backup, private keys, seed phrase, or other wallet credentials.

Bitcoin transactions are irreversible. Always verify the recipient address and transaction amount before sending.

License

B-Lite is licensed under the Apache License 2.0.

See ""LICENSE"" (LICENSE) for the complete license text.

Disclaimer

B-Lite is open-source software provided for informational and personal use.

Use the application at your own risk and maintain secure backups of your wallet data.
