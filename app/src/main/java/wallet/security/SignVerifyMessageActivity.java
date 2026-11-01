package wallet.security;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.utils.MessageVerifyUtils;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.Wallet;

import java.util.LinkedHashMap;
import java.util.Map;

import wallet.core.WalletAddressType;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.network.NetworkConfig;
import wallet.ui.BaseActivity;

/** Bitcoin-compatible message signing and offline signature verification. */
public class SignVerifyMessageActivity extends BaseActivity {
    private EditText addressInput;
    private EditText messageInput;
    private EditText signatureInput;
    private TextView resultStatus;
    private Button signButton;
    private Button verifyButton;
    private boolean working;

    private static final class SigningKeyMatch {
        final ECKey key;
        final ScriptType scriptType;

        SigningKeyMatch(ECKey key, ScriptType scriptType) {
            this.key = key;
            this.scriptType = scriptType;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_sign_verify_message);

        Toolbar toolbar = findViewById(R.id.toolbar_sign_verify_message);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.sign_verify_message_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(view -> finish());

        addressInput = findViewById(R.id.signVerifyAddressInput);
        messageInput = findViewById(R.id.signVerifyMessageInput);
        signatureInput = findViewById(R.id.signVerifySignatureInput);
        resultStatus = findViewById(R.id.signVerifyResultStatus);
        signButton = findViewById(R.id.signVerifySignButton);
        verifyButton = findViewById(R.id.signVerifyVerifyButton);

        findViewById(R.id.signVerifyAddressPaste).setOnClickListener(
                view -> pasteFromClipboard(addressInput));
        findViewById(R.id.signVerifyMessagePaste).setOnClickListener(
                view -> pasteFromClipboard(messageInput));
        findViewById(R.id.signVerifyMessageShare).setOnClickListener(view -> shareText(
                messageInput.getText().toString(), R.string.sign_verify_share_message_title,
                R.string.sign_verify_message_required));
        findViewById(R.id.signVerifySignaturePaste).setOnClickListener(
                view -> pasteFromClipboard(signatureInput));
        findViewById(R.id.signVerifySignatureCopy).setOnClickListener(view -> copyText(
                signatureInput.getText().toString(), R.string.sign_verify_signature_label));
        findViewById(R.id.signVerifySignatureShare).setOnClickListener(view -> shareText(
                signatureInput.getText().toString(), R.string.sign_verify_share_signature_title,
                R.string.sign_verify_signature_required));
        signButton.setOnClickListener(view -> signMessage());
        verifyButton.setOnClickListener(view -> verifyMessage());

        TextWatcher inputWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearResult();
                updateActionButtons();
            }
            @Override public void afterTextChanged(Editable editable) { }
        };
        addressInput.addTextChangedListener(inputWatcher);
        messageInput.addTextChangedListener(inputWatcher);
        signatureInput.addTextChangedListener(inputWatcher);
        updateActionButtons();
    }

    private void updateActionButtons() {
        if (signButton == null || verifyButton == null) return;
        boolean hasAddress = !addressInput.getText().toString().trim().isEmpty();
        boolean hasMessage = messageInput.getText().length() > 0;
        boolean hasSignature = !signatureInput.getText().toString().trim().isEmpty();
        signButton.setEnabled(!working && hasAddress && hasMessage);
        verifyButton.setEnabled(!working && hasAddress && hasMessage && hasSignature);
        findViewById(R.id.signVerifyMessagePaste).setEnabled(!working);
        findViewById(R.id.signVerifyMessageShare).setEnabled(!working && hasMessage);
        findViewById(R.id.signVerifySignaturePaste).setEnabled(!working);
        findViewById(R.id.signVerifySignatureCopy).setEnabled(!working && hasSignature);
        findViewById(R.id.signVerifySignatureShare).setEnabled(!working && hasSignature);
        findViewById(R.id.signVerifyAddressPaste).setEnabled(!working);
    }

    private void setWorking(boolean value) {
        working = value;
        updateActionButtons();
    }

    private void clearResult() {
        if (resultStatus != null) {
            resultStatus.setText("");
            resultStatus.setVisibility(View.GONE);
        }
    }

    private void showResult(int messageResId) {
        resultStatus.setText(messageResId);
        resultStatus.setVisibility(View.VISIBLE);
    }

    private void signMessage() {
        if (working) return;
        String suppliedAddress = addressInput.getText().toString().trim();
        // Preserve the message exactly: leading/trailing whitespace and newlines are signed data.
        String message = messageInput.getText().toString();
        if (suppliedAddress.isEmpty()) {
            showMessage(R.string.sign_verify_address_required);
            return;
        }
        if (message.isEmpty()) {
            showMessage(R.string.sign_verify_message_required);
            return;
        }

        Wallet wallet = MainActivityPresenter.getActiveWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }

        NetworkParameters parameters = wallet.getParams();
        Address address;
        try {
            address = Address.fromString(parameters, suppliedAddress);
        } catch (Exception invalidAddress) {
            showMessage(R.string.sign_verify_invalid_address);
            return;
        }

        ScriptType addressType = address.getOutputScriptType();
        ScriptType signingType = signingTypeFor(addressType);
        if (signingType == null) {
            showMessage(R.string.sign_verify_unsupported_address_type);
            return;
        }

        SigningKeyMatch match = findSigningKey(wallet, address.toString(), parameters, signingType);
        if (match == null) {
            showMessage(R.string.sign_verify_address_not_owned);
            return;
        }

        if (WalletSecurity.isEncrypted(wallet)) {
            if (WalletSecurity.isSessionValid(wallet)) {
                signWithKey(match, message, WalletSecurity.getSessionKey());
            } else {
                requestPasswordAndSign(wallet, match, message);
            }
            return;
        }

        if (match.key.isEncrypted()) {
            showMessage(R.string.sign_verify_key_locked);
            return;
        }
        signWithKey(match, message, null);
    }

    private void requestPasswordAndSign(Wallet wallet, SigningKeyMatch match, String message) {
        View passwordView = getLayoutInflater().inflate(R.layout.dialog_password, null);
        EditText passwordInput = passwordView.findViewById(R.id.dialogPasswordInput);
        passwordInput.setText("");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.sign_verify_unlock_title)
                .setMessage(R.string.sign_verify_unlock_message)
                .setView(passwordView)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.sign_verify_sign_button, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    String password = passwordInput.getText().toString();
                    if (password.isEmpty()) {
                        passwordInput.setError(getString(R.string.password_required));
                        return;
                    }
                    dialog.dismiss();
                    setWorking(true);
                    new Thread(() -> {
                        AesKey authorizationKey = null;
                        String signature = null;
                        boolean wrongPassword = false;
                        try {
                            authorizationKey = WalletSecurity.verifyPassword(wallet, password);
                            if (authorizationKey == null) {
                                wrongPassword = true;
                            } else {
                                signature = match.key.signMessage(
                                        message, authorizationKey, match.scriptType);
                            }
                        } catch (Exception failure) {
                            // Do not expose key material or cryptographic internals to the UI.
                        }
                        final String signed = signature;
                        final boolean passwordRejected = wrongPassword;
                        runOnUiThread(() -> finishSigning(signed, passwordRejected));
                    }, "message-signing").start();
                }));
        dialog.show();
    }

    private void signWithKey(SigningKeyMatch match, String message, AesKey authorizationKey) {
        setWorking(true);
        new Thread(() -> {
            String signature = null;
            try {
                signature = match.key.signMessage(message, authorizationKey, match.scriptType);
            } catch (Exception failure) {
                // Do not expose key material or cryptographic internals to the UI.
            }
            final String signed = signature;
            runOnUiThread(() -> finishSigning(signed, false));
        }, "message-signing").start();
    }

    private void finishSigning(String signature, boolean wrongPassword) {
        setWorking(false);
        if (isFinishing() || isDestroyed()) return;
        if (wrongPassword) {
            showMessage(R.string.wrong_password);
        } else if (signature == null || signature.isEmpty()) {
            showMessage(R.string.sign_verify_sign_failed);
        } else {
            signatureInput.setText(signature);
            signatureInput.setSelection(signatureInput.length());
            showResult(R.string.sign_verify_signature_ready);
        }
    }

    private void verifyMessage() {
        if (working) return;
        String suppliedAddress = addressInput.getText().toString().trim();
        String message = messageInput.getText().toString();
        String signature = signatureInput.getText().toString().trim();
        if (suppliedAddress.isEmpty()) {
            showMessage(R.string.sign_verify_address_required);
            return;
        }
        if (message.isEmpty()) {
            showMessage(R.string.sign_verify_message_required);
            return;
        }
        if (signature.isEmpty()) {
            showMessage(R.string.sign_verify_signature_required);
            return;
        }

        Address address;
        try {
            address = Address.fromString(NetworkConfig.parameters(this), suppliedAddress);
        } catch (Exception invalidAddress) {
            showMessage(R.string.sign_verify_invalid_address);
            return;
        }
        if (signingTypeFor(address.getOutputScriptType()) == null) {
            showMessage(R.string.sign_verify_unsupported_address_type);
            return;
        }

        try {
            MessageVerifyUtils.verifyMessage(address, message, signature);
            showResult(R.string.sign_verify_result_valid);
        } catch (Exception invalidSignature) {
            showResult(R.string.sign_verify_result_invalid);
        }
    }

    private static ScriptType signingTypeFor(ScriptType addressType) {
        if (addressType == ScriptType.P2PKH) return ScriptType.P2PKH;
        if (addressType == ScriptType.P2SH) return ScriptType.P2SH;
        if (addressType == ScriptType.P2WPKH) return ScriptType.P2WPKH;
        return null;
    }

    private SigningKeyMatch findSigningKey(
            Wallet wallet, String normalizedAddress, NetworkParameters parameters, ScriptType signingType) {
        Map<String, ECKey> candidates = new LinkedHashMap<>();
        try {
            for (ECKey key : wallet.getImportedKeys()) addCandidate(candidates, key);
        } catch (Exception ignored) { }
        try {
            for (ECKey key : wallet.getIssuedReceiveKeys()) addCandidate(candidates, key);
        } catch (Exception ignored) { }
        try {
            for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
                for (DeterministicKey key : chain.getLeafKeys()) addCandidate(candidates, key);
            }
        } catch (Exception ignored) { }

        final String addressType = signingType == ScriptType.P2PKH
                ? WalletAddressType.P2PKH
                : signingType == ScriptType.P2SH
                    ? WalletAddressType.P2SH_P2WPKH
                    : WalletAddressType.P2WPKH;
        for (ECKey key : candidates.values()) {
            if (key == null || key.isWatching() || (!key.hasPrivKey() && !key.isEncrypted())) continue;
            try {
                String candidateAddress = WalletAddressType.addressForKey(
                        parameters, key, addressType).toString();
                if (normalizedAddress.equals(candidateAddress)) {
                    return new SigningKeyMatch(key, signingType);
                }
            } catch (Exception ignored) {
                // For example, uncompressed keys cannot represent a SegWit address.
            }
        }
        return null;
    }

    private static void addCandidate(Map<String, ECKey> candidates, ECKey key) {
        if (key == null || key.isWatching() || (!key.hasPrivKey() && !key.isEncrypted())) return;
        String identifier = android.util.Base64.encodeToString(key.getPubKey(), android.util.Base64.NO_WRAP);
        candidates.putIfAbsent(identifier, key);
    }

    private void pasteFromClipboard(EditText target) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()
                || clipboard.getPrimaryClip() == null
                || clipboard.getPrimaryClip().getItemCount() == 0) {
            showMessage(R.string.sign_verify_clipboard_empty);
            return;
        }
        CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text == null || text.length() == 0) {
            showMessage(R.string.sign_verify_clipboard_empty);
            return;
        }
        target.setText(text);
        target.setSelection(target.length());
    }

    private void copyText(String text, int fieldLabelResId) {
        if (text == null || text.isEmpty()) {
            showMessage(R.string.sign_verify_signature_required);
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(fieldLabelResId), text));
            showMessage(R.string.paper_wallet_copied);
        }
    }

    private void shareText(String text, int chooserTitleResId, int emptyMessageResId) {
        if (text == null || text.isEmpty()) {
            showMessage(emptyMessageResId);
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(share, getString(chooserTitleResId)));
    }

    private void showMessage(int messageResId) {
        Toast.makeText(this, messageResId, Toast.LENGTH_LONG).show();
    }
}
