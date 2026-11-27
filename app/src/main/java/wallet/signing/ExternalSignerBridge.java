package wallet.signing;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.signers.CustomTransactionSigner;
import org.bitcoinj.wallet.Wallet;

import java.util.List;

/**
 * Extension point for an external ECDSA signer (for example, a USB, Bluetooth,
 * companion-app or remote signer integration). This class deliberately does
 * not claim support for any particular hardware device or transport.
 */
public final class ExternalSignerBridge {
    public interface Provider {
        boolean isReady();

        CustomTransactionSigner.SignatureAndKey sign(
                Sha256Hash sighash, List<ChildNumber> derivationPath);
    }

    private static volatile Provider provider;

    private ExternalSignerBridge() { }

    /** Install a connected provider and attach bitcoinj's custom signer to a wallet. */
    public static synchronized void register(Wallet wallet, Provider externalProvider) {
        if (wallet == null || externalProvider == null || !externalProvider.isReady()) {
            throw new IllegalArgumentException("A ready external signing provider is required");
        }
        provider = externalProvider;
        for (org.bitcoinj.signers.TransactionSigner signer : wallet.getTransactionSigners()) {
            if (signer instanceof Adapter) return;
        }
        wallet.addTransactionSigner(new Adapter());
    }

    /** Clear a disconnected provider. Re-register after reconnecting the device/service. */
    public static synchronized void unregister() {
        provider = null;
    }

    public static boolean isReady() {
        Provider current = provider;
        return current != null && current.isReady();
    }

    /** No-argument constructor is required by bitcoinj wallet serialization. */
    public static final class Adapter extends CustomTransactionSigner {
        public Adapter() { }

        @Override
        public boolean isReady() {
            return ExternalSignerBridge.isReady();
        }

        @Override
        protected SignatureAndKey getSignature(Sha256Hash sighash,
                                               List<ChildNumber> derivationPath) {
            Provider current = provider;
            if (current == null || !current.isReady()) {
                throw new IllegalStateException("External signer is not connected");
            }
            SignatureAndKey result = current.sign(sighash, derivationPath);
            // An external signer must return the DER ECDSA signature and the
            // corresponding public key; private key material must not be required.
            if (result == null || result.sig == null || result.pubKey == null
                    || result.pubKey.getPubKey() == null) {
                throw new IllegalStateException("External signer returned an invalid signature result");
            }
            return result;
        }
    }
}
