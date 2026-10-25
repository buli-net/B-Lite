package wallet.core;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.FullPrunedBlockChain;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.UTXO;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.script.Script;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.FullPrunedBlockStore;
import org.bitcoinj.wallet.Wallet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Connects the app's send path and sync diagnostics to the global verified UTXO set maintained by
 * FullPrunedBlockChain. Consensus code remains in bitcoinj; this class only performs a fail-closed
 * preflight on known confirmed inputs and a read-only cross-check of wallet-unspent outputs.
 */
public final class FullPrunedWalletAudit {
    private FullPrunedWalletAudit() { }

    /**
     * Refuse to commit/broadcast a transaction that tries to spend a confirmed input which is no
     * longer in the verified global UTXO set. Unconfirmed wallet parents are allowed because their
     * outputs cannot exist in the on-chain UTXO set yet; peers/mempool policy still decide relay.
     */
    public static void validateTransactionInputs(WalletAppKit kit, Wallet wallet, Transaction transaction)
            throws Exception {
        if (kit == null || wallet == null || transaction == null || !kit.isFullPrunedValidationEnabled()) {
            return;
        }
        if (!(kit.chain() instanceof FullPrunedBlockChain)) {
            throw new IllegalStateException(
                    "fullpruned_engine_mismatch: Full Pruned is enabled but active chain is not FullPrunedBlockChain");
        }
        FullPrunedBlockStore store = kit.fullPrunedStore();
        if (store == null) {
            throw new IllegalStateException("fullpruned_utxo_store_unavailable");
        }
        StoredBlock verifiedHead = store.getVerifiedChainHead();
        StoredBlock engineHead = kit.chain().getChainHead();
        if (verifiedHead == null || verifiedHead.getHeader() == null) {
            throw new IllegalStateException("fullpruned_verified_chain_head_unavailable");
        }
        if (engineHead == null || engineHead.getHeader() == null
                || engineHead.getHeight() != verifiedHead.getHeight()
                || !engineHead.getHeader().getHash().equals(verifiedHead.getHeader().getHash())) {
            throw new IllegalStateException("fullpruned_chain_utxo_head_mismatch");
        }
        // A stale wallet marker means the wallet graph may not reflect the latest reorg or block.
        // Refuse to commit/broadcast in that state instead of validating against only one view.
        Sha256Hash walletHeadHash = wallet.getLastBlockSeenHash();
        if (wallet.getLastBlockSeenHeight() != verifiedHead.getHeight()
                || walletHeadHash == null
                || !walletHeadHash.equals(verifiedHead.getHeader().getHash())) {
            throw new IllegalStateException("fullpruned_wallet_not_at_verified_tip");
        }
        final int verifiedHeight = verifiedHead.getHeight();

        for (TransactionInput input : transaction.getInputs()) {
            if (input == null) {
                throw new IllegalStateException("fullpruned_input_missing");
            }
            TransactionOutPoint outpoint = input.getOutpoint();
            if (outpoint == null || outpoint.getHash() == null) {
                throw new IllegalStateException("fullpruned_input_outpoint_missing");
            }
            Sha256Hash parentHash = outpoint.getHash();
            long outputIndex = outpoint.getIndex();

            Transaction parent = wallet.getTransaction(parentHash);
            TransactionConfidence.ConfidenceType confidenceType = parent == null
                    ? null : parent.getConfidence().getConfidenceType();
            // A wallet transaction marked dead/conflicted is never a spendable parent, even if a
            // stale/corrupted UTXO row happens to exist. Check this before the positive UTXO path.
            if (parent != null && (confidenceType == TransactionConfidence.ConfidenceType.DEAD
                    || confidenceType == TransactionConfidence.ConfidenceType.IN_CONFLICT)) {
                throw new IllegalStateException("fullpruned_parent_transaction_not_live:" + parentHash);
            }
            // Never spend a wallet-marked confirmed output beyond the current verified tip, even if
            // a stale UTXO row happens to exist after a prior-chain/recovery transition.
            if (parent != null && confidenceType == TransactionConfidence.ConfidenceType.BUILDING) {
                int parentHeight = parent.getConfidence().getAppearedAtChainHeight();
                if (parentHeight > verifiedHeight) {
                    throw new IllegalStateException("fullpruned_chain_behind_confirmed_input:" +
                            parentHash + ":" + outputIndex + "; verifiedHeight=" + verifiedHeight +
                            "; inputHeight=" + parentHeight);
                }
            }
            UTXO verifiedOutput = store.getTransactionOutput(parentHash, outputIndex);

            if (verifiedOutput != null) {
                if (verifiedOutput.getScript() == null) {
                    throw new IllegalStateException("fullpruned_input_script_missing:" +
                            parentHash + ":" + outputIndex);
                }
                // If bitcoinj has the parent transaction, make sure the UTXO row agrees with its
                // immutable value/script. This also detects store corruption or wrong-network data.
                if (parent != null) {
                    if (outputIndex < 0 || outputIndex >= parent.getOutputs().size()) {
                        throw new IllegalStateException("fullpruned_input_index_invalid:" +
                                parentHash + ":" + outputIndex);
                    }
                    TransactionOutput walletOutput = parent.getOutput((int) outputIndex);
                    if (!walletOutput.getValue().equals(verifiedOutput.getValue())) {
                        throw new IllegalStateException("fullpruned_input_value_mismatch:" +
                                parentHash + ":" + outputIndex);
                    }
                    Script storedScript = verifiedOutput.getScript();
                    byte[] walletScript = walletOutput.getScriptPubKey().getProgram();
                    if (storedScript == null || !Arrays.equals(walletScript, storedScript.getProgram())) {
                        throw new IllegalStateException("fullpruned_input_script_mismatch:" +
                                parentHash + ":" + outputIndex);
                    }
                }
                continue;
            }

            if (parent != null && confidenceType == TransactionConfidence.ConfidenceType.BUILDING) {
                throw new IllegalStateException("fullpruned_input_not_unspent:" +
                        parentHash + ":" + outputIndex +
                        " (confirmed input is absent from the verified global UTXO set)");
            }

            // UNKNOWN/PENDING parents are not represented in the chain UTXO set yet. Leave those
            // inputs to bitcoinj transaction construction and peer/mempool validation.
        }
    }

    /** Read-only diagnostics for the active chain and wallet's confirmed unspent output set. */
    public static String describe(WalletAppKit kit, Wallet wallet) {
        if (kit == null || wallet == null) return "Full Pruned: chưa sẵn sàng";
        try {
            if (!kit.isFullPrunedValidationEnabled()) {
                return "Full Pruned: KHÔNG BẬT (chế độ hiện tại không xác minh toàn bộ block/UTXO)";
            }
            if (!(kit.chain() instanceof FullPrunedBlockChain)) {
                return "CẢNH BÁO: cấu hình Full Pruned đang bật nhưng engine không phải FullPrunedBlockChain";
            }

            FullPrunedBlockStore store = kit.fullPrunedStore();
            if (store == null) return "CẢNH BÁO: không truy cập được Full Pruned UTXO store";
            if (wallet.getUTXOProvider() != store) {
                return "CẢNH BÁO: Full Pruned chain đang chạy nhưng ví chưa nối UTXO provider đã xác minh";
            }
            StoredBlock verifiedHead = store.getVerifiedChainHead();
            StoredBlock activeHead = kit.chain().getChainHead();
            if (verifiedHead == null || verifiedHead.getHeader() == null
                    || activeHead == null || activeHead.getHeader() == null) {
                return "Full Pruned: đang khởi tạo verified chain/UTXO";
            }

            int tipHeight = verifiedHead.getHeight();
            String verifiedHash = verifiedHead.getHeader().getHashAsString();
            boolean headsAgree = activeHead.getHeight() == tipHeight
                    && activeHead.getHeader().getHash().equals(verifiedHead.getHeader().getHash());

            StringBuilder result = new StringBuilder();
            result.append("Full Pruned: ĐANG CHẠY (engine bitcoinj; CHƯA đủ để khẳng định xác minh đồng thuận như Bitcoin Core)");
            result.append("\nĐỉnh UTXO đã xác minh: ").append(tipHeight)
                    .append(" (").append(shortHash(verifiedHash)).append(")");
            result.append("\nĐỉnh engine: ").append(activeHead.getHeight())
                    .append(" (").append(shortHash(activeHead.getHeader().getHashAsString())).append(")");
            if (!headsAgree) {
                result.append("\nCẢNH BÁO: đỉnh chain và đỉnh UTXO đã xác minh không khớp; kiểm tra chi tiêu sẽ từ chối input confirmed.");
            }

            int walletHeight = wallet.getLastBlockSeenHeight();
            Sha256Hash walletHash = wallet.getLastBlockSeenHash();
            boolean walletAtTip = walletHeight == tipHeight
                    && walletHash != null && walletHash.equals(verifiedHead.getHeader().getHash());
            if (!walletAtTip) {
                result.append("\nĐối chiếu UTXO ví: hoãn — ví chưa theo kịp chain (wallet=")
                        .append(walletHeight).append(", verified=").append(tipHeight).append(").");
                return result.toString();
            }

            List<TransactionOutput> outputs = new ArrayList<>();
            outputs.addAll(wallet.getUnspents());
            outputs.addAll(wallet.getWatchedOutputs(false));
            Set<String> seen = new HashSet<>();
            int checked = 0;
            int matched = 0;
            int mismatch = 0;
            for (TransactionOutput output : outputs) {
                if (output == null || output.getParentTransaction() == null) continue;
                Transaction parent = output.getParentTransaction();
                TransactionConfidence confidence = parent.getConfidence();
                if (confidence == null
                        || confidence.getConfidenceType() != TransactionConfidence.ConfidenceType.BUILDING) {
                    continue;
                }
                if (confidence.getAppearedAtChainHeight() > tipHeight) continue;
                String key = parent.getTxId() + ":" + output.getIndex();
                if (!seen.add(key)) continue;

                checked++;
                UTXO chainOutput = store.getTransactionOutput(parent.getTxId(), output.getIndex());
                if (chainOutput == null || !sameOutput(output, chainOutput)) {
                    mismatch++;
                } else {
                    matched++;
                }
            }
            result.append("\nĐối chiếu UTXO ví đã xác nhận: ").append(matched).append("/")
                    .append(checked).append(" khớp; lệch ").append(mismatch).append(".");
            if (mismatch > 0) {
                result.append("\nCẢNH BÁO: ví còn output mà UTXO toàn mạng không xác nhận là unspent. Hãy kiểm tra trước khi chi tiêu.");
            } else if (checked > 0) {
                result.append("\nTrạng thái UTXO cục bộ của ví khớp với tập UTXO đã xác minh.");
            } else {
                result.append("\nChưa có output confirmed phù hợp để đối chiếu.");
            }
            result.append("\nSpendable balance/coin selection: dùng FullPruned UTXO provider.\nPreflight gửi: kiểm tra input confirmed và yêu cầu chain, UTXO cùng wallet tip khớp nhau.");
            result.append("\nChỉ mục chi tiết input: ")
                    .append(kit.isFullPrunedHistoryIndexReady() ? "đã hoàn tất" : "CHƯA HOÀN TẤT — lịch sử input/fee có thể thiếu");
            result.append("\nGIỚI HẠN ĐỒNG THUẬN: engine đang có xác minh P2PKH/P2PK/P2SH và một phần P2WPKH (native + nested), nhưng CHƯA đủ để coi là full-node validation. P2WSH/Taproot/Tapscript bị từ chối thay vì bỏ qua chữ ký; các quy tắc kích hoạt theo chiều cao/thời gian và relative locktime (BIP68/BIP112 CSV), cùng toàn bộ cờ xác minh consensus, vẫn cần được hoàn thiện và kiểm thử. Không dùng trạng thái này để khẳng định đã xác minh ngang Bitcoin Core hay đã đồng bộ xác minh đầy đủ Mainnet/Signet.");
            return result.toString();
        } catch (Exception e) {
            return "Full Pruned: không đọc được trạng thái xác minh/UTXO (" +
                    e.getClass().getSimpleName() + ": " + safeMessage(e) + ")";
        }
    }

    private static boolean sameOutput(TransactionOutput walletOutput, UTXO chainOutput) throws Exception {
        if (!walletOutput.getValue().equals(chainOutput.getValue()) || chainOutput.getScript() == null) {
            return false;
        }
        return Arrays.equals(walletOutput.getScriptPubKey().getProgram(), chainOutput.getScript().getProgram());
    }

    private static String shortHash(String value) {
        if (value == null || value.isEmpty()) return "—";
        return value.length() <= 16 ? value : value.substring(0, 16) + "…";
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "no details";
        return message.replace('\n', ' ').replace('\r', ' ');
    }
}
