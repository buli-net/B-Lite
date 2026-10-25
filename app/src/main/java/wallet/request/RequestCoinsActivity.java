package wallet.request;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.graphics.Bitmap;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.app.ShareCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.Wallet;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import wallet.util.MoneyFormatter;
import wallet.ui.BaseActivity;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.core.WalletAddressType;
import wallet.core.WalletSelection;
import wallet.nfc.PaymentRequestHceService;
import wallet.qr.QrCodeGenerator;
import wallet.send.SendActivity;
import wallet.nfc.Nfc;
import wallet.offline.AcceptBluetoothService;
import wallet.offline.BluetoothSupport;
import wallet.settings.BluetoothMerchantSettingsActivity;

/** Creates Bitcoin requests and maintains QR, NFC and local-app handoff state. */
public final class RequestCoinsActivity extends BaseActivity {
    private static final String[] ADDRESS_TYPES = {
            WalletAddressType.P2PKH,
            WalletAddressType.P2WPKH,
            WalletAddressType.P2SH_P2WPKH
    };

    private final ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger requestVersion = new AtomicInteger();

    private EditText amountInput;
    private EditText labelInput;
    private Spinner addressTypeInput;
    private TextView addressTypeDescription;
    private TextView requestInstructions;
    private ImageView qrImage;
    private NfcAdapter nfcAdapter;
    private BluetoothAdapter bluetoothAdapter;
    private CheckBox acceptBluetoothPayment;
    private TextView bluetoothStatus;
    private boolean changingBluetoothCheckbox;
    private boolean bluetoothServiceStarted;
    private String activeRequestBluetoothMac;
    private boolean nfcReceiverRegistered;
    private boolean bluetoothReceiverStateRegistered;

    private final ActivityResultLauncher<String> bluetoothPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) continueStartBluetoothListening();
                else disableBluetoothListening(R.string.bluetooth_payment_permission_denied);
            });

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (bluetoothAdapter != null && result.getResultCode() == RESULT_OK) {
                    continueStartBluetoothListening();
                } else {
                    disableBluetoothListening(R.string.bluetooth_payment_enable_bluetooth);
                }
            });

    private final BroadcastReceiver nfcStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (NfcAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(intent.getAction())) {
                updateNfcInstruction();
                publishCurrentRequest();
            }
        }
    };

    private final BroadcastReceiver bluetoothReceiverState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BluetoothSupport.ACTION_RECEIVER_STOPPED.equals(intent.getAction())) {
                changingBluetoothCheckbox = true;
                if (acceptBluetoothPayment != null) acceptBluetoothPayment.setChecked(false);
                changingBluetoothCheckbox = false;
                bluetoothServiceStarted = false;
                activeRequestBluetoothMac = null;
                updateBluetoothStatus();
                if (currentAddress != null && !isFinishing()) updateRequestForCurrentAddress();
            }
        }
    };

    private volatile Address currentAddress;
    private volatile String currentUri;
    private volatile byte[] currentPaymentRequest;
    private String selectedAddressType = WalletAddressType.P2WPKH;
    private boolean suppressAddressTypeCallback;

    @Override
    protected void onCreate(final Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_request_coins);
        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.request_coins_title);
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        amountInput = findViewById(R.id.requestAmount);
        labelInput = findViewById(R.id.requestLabel);
        addressTypeInput = findViewById(R.id.requestAddressType);
        addressTypeDescription = findViewById(R.id.requestAddressTypeDescription);
        requestInstructions = findViewById(R.id.requestNfcStatus);
        qrImage = findViewById(R.id.requestQr);

        final Button copyButton = findViewById(R.id.requestCopyButton);
        final Button shareButton = findViewById(R.id.requestShareButton);
        final Button localAppButton = findViewById(R.id.requestLocalAppButton);
        acceptBluetoothPayment = findViewById(R.id.requestAcceptBluetoothPayment);
        bluetoothStatus = findViewById(R.id.requestBluetoothStatus);
        final Button bluetoothSettingsButton = findViewById(R.id.requestBluetoothSettingsButton);
        changingBluetoothCheckbox = true;
        acceptBluetoothPayment.setChecked(BluetoothSupport.isAcceptingPayments(this));
        changingBluetoothCheckbox = false;
        acceptBluetoothPayment.setOnCheckedChangeListener((button, checked) -> {
            if (changingBluetoothCheckbox) return;
            BluetoothSupport.setAcceptingPayments(this, checked);
            if (checked) maybeStartBluetoothListening(); else stopBluetoothListening();
        });
        bluetoothSettingsButton.setOnClickListener(view ->
                startActivity(new Intent(this, BluetoothMerchantSettingsActivity.class)));
        copyButton.setOnClickListener(view -> handleCopy());
        shareButton.setOnClickListener(view -> handleShare());
        localAppButton.setOnClickListener(view -> handleLocalApp());
        setRequestButtonsEnabled(false);

        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{
                        getString(R.string.request_address_type_legacy),
                        getString(R.string.request_address_type_native_segwit),
                        getString(R.string.request_address_type_nested_segwit)
                });
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        addressTypeInput.setAdapter(adapter);

        selectedAddressType = defaultAddressTypeFromMainWallet();
        suppressAddressTypeCallback = true;
        addressTypeInput.setSelection(selectionForType(selectedAddressType));
        suppressAddressTypeCallback = false;
        updateAddressTypeDescription();

        addressTypeInput.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (suppressAddressTypeCallback) return;
                final String selected = ADDRESS_TYPES[position];
                if (!selected.equals(selectedAddressType)) {
                    selectedAddressType = selected;
                    updateAddressTypeDescription();
                    generateFreshAddress();
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        final TextWatcher requestFieldsWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateRequestForCurrentAddress();
            }
            @Override public void afterTextChanged(Editable s) { }
        };
        amountInput.addTextChangedListener(requestFieldsWatcher);
        labelInput.addTextChangedListener(requestFieldsWatcher);

        updateNfcInstruction();
        generateFreshAddress();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!nfcReceiverRegistered) {
            ContextCompat.registerReceiver(this, nfcStateReceiver,
                    new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            nfcReceiverRegistered = true;
        }
        if (!bluetoothReceiverStateRegistered) {
            ContextCompat.registerReceiver(this, bluetoothReceiverState,
                    new IntentFilter(BluetoothSupport.ACTION_RECEIVER_STOPPED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            bluetoothReceiverStateRegistered = true;
        }
        updateNfcInstruction();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNfcInstruction();
        if (acceptBluetoothPayment != null && acceptBluetoothPayment.isChecked()) {
            maybeStartBluetoothListening();
        }
        updateBluetoothStatus();
        if (currentAddress != null) updateRequestForCurrentAddress();
        else publishCurrentRequest();
    }

    @Override
    protected void onStop() {
        if (nfcReceiverRegistered) {
            unregisterReceiver(nfcStateReceiver);
            nfcReceiverRegistered = false;
        }
        if (bluetoothReceiverStateRegistered) {
            unregisterReceiver(bluetoothReceiverState);
            bluetoothReceiverStateRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onPause() {
        PaymentRequestHceService.clearPayload();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // NFC HCE is tied to the foreground Request screen. Bluetooth receiving is
        // a foreground service and remains active until the user explicitly disables it.
        PaymentRequestHceService.clearPayload();
        requestExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void maybeStartBluetoothListening() {
        if (bluetoothAdapter == null) {
            disableBluetoothListening(R.string.bluetooth_payment_bluetooth_unavailable);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothConnectPermission()) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT);
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            enableBluetoothLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        continueStartBluetoothListening();
    }

    private void continueStartBluetoothListening() {
        if (isFinishing() || acceptBluetoothPayment == null || !acceptBluetoothPayment.isChecked()) return;
        if (!hasBluetoothConnectPermission()) {
            disableBluetoothListening(R.string.bluetooth_payment_permission_denied);
            return;
        }
        final String address = BluetoothSupport.getLocalAddress(this);
        if (address == null) {
            disableBluetoothListening(R.string.bluetooth_payment_address_missing);
            return;
        }
        try {
            BluetoothSupport.setAcceptingPayments(this, true);
            ContextCompat.startForegroundService(this, new Intent(this, AcceptBluetoothService.class));
            bluetoothServiceStarted = true;
            activeRequestBluetoothMac = address;
            updateBluetoothStatus();
            updateRequestForCurrentAddress();
        } catch (RuntimeException exception) {
            disableBluetoothListening(R.string.request_generation_failed);
        }
    }

    private void stopBluetoothListening() {
        BluetoothSupport.setAcceptingPayments(this, false);
        try { stopService(new Intent(this, AcceptBluetoothService.class)); } catch (RuntimeException ignored) { }
        bluetoothServiceStarted = false;
        activeRequestBluetoothMac = null;
        updateBluetoothStatus();
        if (currentAddress != null && !isFinishing() && !isDestroyed()) updateRequestForCurrentAddress();
    }

    private void disableBluetoothListening(int messageResId) {
        changingBluetoothCheckbox = true;
        if (acceptBluetoothPayment != null) acceptBluetoothPayment.setChecked(false);
        changingBluetoothCheckbox = false;
        BluetoothSupport.setAcceptingPayments(this, false);
        stopBluetoothListening();
        Toast.makeText(this, messageResId, Toast.LENGTH_LONG).show();
    }

    private void updateBluetoothStatus() {
        if (bluetoothStatus == null) return;
        if (activeRequestBluetoothMac != null) {
            bluetoothStatus.setText(getString(R.string.request_bluetooth_status_listening,
                    BluetoothSupport.formatMac(activeRequestBluetoothMac)));
        } else {
            String configured = BluetoothSupport.formatMac(BluetoothSupport.getLocalAddress(this));
            bluetoothStatus.setText(configured == null
                    ? getString(R.string.request_bluetooth_status_not_configured)
                    : getString(R.string.request_bluetooth_status_ready, configured));
        }
    }

    private void setRequestButtonsEnabled(final boolean enabled) {
        final Button copy = findViewById(R.id.requestCopyButton);
        final Button share = findViewById(R.id.requestShareButton);
        final Button localApp = findViewById(R.id.requestLocalAppButton);
        if (copy != null) copy.setEnabled(enabled);
        if (share != null) share.setEnabled(enabled);
        if (localApp != null) localApp.setEnabled(enabled);
    }

    /**
     * Select the request format from the active wallet's actual receive script.
     * Do not infer script type from address prefixes: testnet/signet prefixes overlap,
     * and a P2SH address does not by itself prove that it is nested SegWit.
     */
    private String defaultAddressTypeFromMainWallet() {
        final Wallet wallet = MainActivityPresenter.getActiveWallet();
        if (wallet == null) return WalletAddressType.P2PKH;
        try {
            final org.bitcoinj.script.Script outputScript =
                    org.bitcoinj.script.ScriptBuilder.createOutputScript(wallet.currentReceiveAddress());
            if (org.bitcoinj.script.ScriptPattern.isP2WPKH(outputScript)) {
                return WalletAddressType.P2WPKH;
            }
            if (org.bitcoinj.script.ScriptPattern.isP2PKH(outputScript)) {
                return WalletAddressType.P2PKH;
            }
            // The active wallet's current receive output is the authoritative type.
            // In this app, a P2SH receive output is the Nested SegWit wallet format.
            // Do not silently fall back to P2PKH: freshReceiveAddress(P2PKH) can fail
            // when the active key chain is configured for a different script type.
            if (org.bitcoinj.script.ScriptPattern.isP2SH(outputScript)) {
                return WalletAddressType.P2SH_P2WPKH;
            }
            return WalletAddressType.P2PKH;
        } catch (RuntimeException ignored) {
            return WalletAddressType.P2PKH;
        }
    }

    private int selectionForType(final String type) {
        for (int i = 0; i < ADDRESS_TYPES.length; i++) {
            if (ADDRESS_TYPES[i].equals(type)) return i;
        }
        return 0;
    }

    private void updateAddressTypeDescription() {
        if (addressTypeDescription == null) return;
        if (WalletAddressType.P2PKH.equals(selectedAddressType)) {
            addressTypeDescription.setText(R.string.request_address_type_description_legacy);
        } else if (WalletAddressType.P2SH_P2WPKH.equals(selectedAddressType)) {
            addressTypeDescription.setText(R.string.request_address_type_description_nested_segwit);
        } else {
            addressTypeDescription.setText(R.string.request_address_type_description_native_segwit);
        }
    }

    private void generateFreshAddress() {
        final int version = requestVersion.incrementAndGet();
        currentAddress = null;
        currentUri = null;
        currentPaymentRequest = null;
        setRequestButtonsEnabled(false);
        PaymentRequestHceService.clearPayload();
        qrImage.setImageDrawable(null);

        final String type = selectedAddressType;
        requestExecutor.execute(() -> {
            try {
                if (version != requestVersion.get()) return;
                final Wallet wallet = MainActivityPresenter.getActiveWallet();
                if (wallet == null) throw new IllegalStateException(getString(R.string.wallet_not_ready));
                org.bitcoinj.core.Context.propagate(
                        org.bitcoinj.core.Context.getOrCreate(wallet.getParams()));

                if (version != requestVersion.get()) return;
                final String normalizedType = WalletAddressType.normalize(type);
                final Address address;
                if (WalletAddressType.P2PKH.equals(normalizedType)) {
                    // Follow the reference wallet: ask bitcoinj for a receive address of the
                    // requested script type so the output is owned by the wallet keychain and
                    // is naturally included in balance, transactions and spendable coins.
                    address = wallet.freshReceiveAddress(org.bitcoinj.base.ScriptType.P2PKH);
                    WalletSelection.removeRequestNativeWatchedScripts(wallet);
                } else if (WalletAddressType.P2WPKH.equals(normalizedType)) {
                    address = wallet.freshReceiveAddress(org.bitcoinj.base.ScriptType.P2WPKH);
                    WalletSelection.removeRequestNativeWatchedScripts(wallet);
                } else {
                    // bitcoinj does not provide a P2SH-P2WPKH receive keychain API here.
                    // Keep the existing main-wallet HD key mapping for this third request type.
                    final ECKey key = wallet.freshKey(
                            org.bitcoinj.wallet.KeyChain.KeyPurpose.RECEIVE_FUNDS);
                    address = WalletAddressType.addressForKey(wallet.getParams(), key, normalizedType);
                    final Script script = WalletAddressType.scriptForKey(
                            wallet.getParams(), key, normalizedType);
                    wallet.addWatchedScripts(Collections.singletonList(script));
                    // Register the exact generated Request address before reconciliation;
                    // reconciliation removes inferred P2SH lookahead scripts that were
                    // never explicitly created as Request addresses.
                    WalletSelection.registerRequestNestedAddress(
                            getApplicationContext(), address.toString(), key.getPubKey());
                    WalletSelection.ensureRequestNestedScripts(getApplicationContext(), wallet);
                }
                // Persist the newly issued HD key for every requested address type, not only
                // SegWit. Mainnet and Signet intentionally share this same path; network
                // encoding comes exclusively from wallet.getParams().
                final MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                if (presenter != null) presenter.saveWalletNow();

                runOnUiThread(() -> {
                    if (isFinishing() || version != requestVersion.get()) return;
                    currentAddress = address;
                    updateRequestForCurrentAddress();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (isFinishing() || version != requestVersion.get()) return;
                    Toast.makeText(this, R.string.request_generation_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateRequestForCurrentAddress() {
        final Address address = currentAddress;
        if (address == null) return;

        final Coin amount = parseAmountWithoutShowingToast();
        final String labelText = labelInput.getText().toString().trim();
        final String typedLabel = labelText.isEmpty() ? null : labelText;
        final String ownName = BluetoothSupport.getOwnName(this);
        final String label = ownName != null && typedLabel != null && !ownName.equals(typedLabel)
                ? ownName + " — " + typedLabel
                : typedLabel != null ? typedLabel : ownName;
        final String bluetoothMac = activeRequestBluetoothMac;
        final String uri;
        final int version = requestVersion.incrementAndGet();
        try {
            // QR bitmap encoding alone runs off the UI thread.
            final Wallet activeWallet = MainActivityPresenter.getActiveWallet();
            final org.bitcoinj.core.NetworkParameters parameters = activeWallet != null
                    ? activeWallet.getParams() : MainActivityPresenter.getActiveParameters();
            if (parameters == null) throw new IllegalStateException(getString(R.string.wallet_not_ready));
            String generatedUri = org.bitcoinj.uri.BitcoinURI.convertToBitcoinURI(address, amount, label, null);
            if (bluetoothMac != null) {
                generatedUri = Uri.parse(generatedUri).buildUpon()
                        .appendQueryParameter(BluetoothSupport.MAC_URI_PARAM, bluetoothMac).build().toString();
            }
            uri = generatedUri;
            // BIP70 embeds the active network and Bluetooth payment endpoint. Keep the
            // legacy NFC payload unchanged unless Bluetooth reception was explicitly enabled.
            final byte[] paymentRequest;
            if ((amount != null && amount.isPositive()) || bluetoothMac != null) {
                String memo = label;
                paymentRequest = PaymentProtocol.createPaymentRequest(
                        parameters, amount, address, memo, bluetoothMac == null ? null : "bt:" + bluetoothMac, null)
                        .build().toByteArray();
            } else {
                paymentRequest = null;
            }

            currentUri = uri;
            currentPaymentRequest = paymentRequest;
            setRequestButtonsEnabled(true);
            publishCurrentRequest();
            updateNfcInstruction();

            requestExecutor.execute(() -> {
                try {
                    if (version != requestVersion.get()) return;
                    final Bitmap qr = QrCodeGenerator.generate(uri, 700);
                    runOnUiThread(() -> {
                        if (isFinishing() || version != requestVersion.get() || address != currentAddress) return;
                        qrImage.setImageBitmap(qr);
                    });
                } catch (RuntimeException exception) {
                    runOnUiThread(() -> {
                        if (isFinishing() || version != requestVersion.get()) return;
                        qrImage.setImageDrawable(null);
                    });
                }
            });
        } catch (Exception exception) {
            currentUri = null;
            currentPaymentRequest = null;
            setRequestButtonsEnabled(false);
            qrImage.setImageDrawable(null);
        }
    }

    private Coin parseAmountWithoutShowingToast() {
        final String text = amountInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return null;
        try {
            final Coin value = MoneyFormatter.parse(this, text);
            return value.isPositive() ? value : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void publishCurrentRequest() {
        final byte[] paymentRequest = currentPaymentRequest;
        // Keep the HCE payload in sync with the visible request even on Android versions
        // where legacy Android Beam/NDEF push is unavailable.
        if (Nfc.hasModernHceSupport(this) && paymentRequest != null) {
            PaymentRequestHceService.setPayload(paymentRequest);
        } else {
            PaymentRequestHceService.clearPayload();
        }

        if (nfcAdapter == null || !nfcAdapter.isEnabled() || paymentRequest == null) return;
        final NdefMessage message = new NdefMessage(new android.nfc.NdefRecord[]{
                Nfc.createMime(PaymentProtocol.MIMETYPE_PAYMENTREQUEST, paymentRequest)
        });
        // Best-effort compatibility path for platforms that still expose NDEF push.
        Nfc.setNdefPushMessage(nfcAdapter, message, this);
    }

    private void updateNfcInstruction() {
        if (requestInstructions == null) return;
        final boolean enabled = nfcAdapter != null && nfcAdapter.isEnabled();
        final String state = getString(enabled ? R.string.request_nfc_enabled : R.string.request_nfc_disabled);
        String instruction = state + "\n" + getString(R.string.request_instruction_qr);
        if (enabled) instruction += "\n" + getString(R.string.request_instruction_nfc);
        requestInstructions.setText(instruction);
    }

    private void handleCopy() {
        final String uri = currentUri;
        if (TextUtils.isEmpty(uri)) return;
        final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newRawUri(getString(R.string.request_clipboard_label), Uri.parse(uri)));
        Toast.makeText(this, R.string.request_uri_copied, Toast.LENGTH_SHORT).show();
    }

    private void handleShare() {
        final String request = currentUri;
        if (TextUtils.isEmpty(request)) return;

        // Match the reference wallet: share the BIP21 URI as plain text directly.
        // Do not create or attach a .txt file; Android's Sharesheet chooses the
        // target app/transport, including Bluetooth, and receives the URI as text.
        try {
            ShareCompat.IntentBuilder.from(this)
                    .setType("text/plain")
                    .setText(request)
                    .setChooserTitle(R.string.request_share_title)
                    .startChooser();
        } catch (ActivityNotFoundException | IllegalArgumentException exception) {
            Toast.makeText(this, R.string.request_share_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void handleLocalApp() {
        final String uri = currentUri;
        if (TextUtils.isEmpty(uri)) return;
        final ComponentName component = new ComponentName(this, SendActivity.class);
        final PackageManager packageManager = getPackageManager();
        final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        try {
            // Match the reference wallet: temporarily exclude this app's send handler,
            // so ACTION_VIEW resolves to a different installed Bitcoin app.
            packageManager.setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            startActivity(intent);
        } catch (final ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.request_no_local_app, Toast.LENGTH_LONG).show();
        } finally {
            packageManager.setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        }
        finish();
    }
}
