# Direct Bluetooth request handoff

- Adds a dedicated `Send directly to B-Lite via Bluetooth` action on Request Coins.
- Sends the current BIP21 `bitcoin:` URI over a private RFCOMM UUID to a paired device; it does not create or transfer an HTML file.
- The existing `AcceptBluetoothService` listens on that UUID while the Bluetooth payment receiver is enabled on the receiving device. The service validates a bounded payload and bitcoin scheme before handing it to SendActivity.
- The normal SendActivity network validation and user confirmation remain responsible for handling the request; no transaction is sent automatically.

Limitations requiring device testing: Android can restrict activity launches from a background service; for reliable receive, keep B-Lite foreground on the receiving phone. Both devices must be paired, Bluetooth enabled, and the receiving phone must enable the existing Bluetooth receiver. Full Android build and two-device test were not available in this environment.
