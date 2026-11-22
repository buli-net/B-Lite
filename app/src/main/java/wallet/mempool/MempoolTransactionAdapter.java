package wallet.mempool;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.bitcoinj.base.Coin;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import wallet.main.R;
import wallet.util.MoneyFormatter;

/** Displays recently observed unconfirmed transactions. */
public final class MempoolTransactionAdapter
        extends RecyclerView.Adapter<MempoolTransactionAdapter.Holder> {

    public interface OnEntryClickListener {
        void onEntryClick(MempoolMonitor.Entry entry);
    }

    private final List<MempoolMonitor.Entry> entries = new ArrayList<>();
    private final OnEntryClickListener clickListener;

    public MempoolTransactionAdapter(OnEntryClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void setEntries(List<MempoolMonitor.Entry> values) {
        entries.clear();
        if (values != null) {
            entries.addAll(values);
        }
        notifyDataSetChanged();
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mempool_transaction, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
        MempoolMonitor.Entry entry = entries.get(position);
        android.content.Context context = holder.itemView.getContext();
        holder.txId.setText(entry.txId);
        holder.result.setText(resultLabel(context, entry.riskStatus));
        holder.walletRelated.setVisibility(entry.walletRelated ? View.VISIBLE : View.GONE);
        holder.meta.setText(context.getString(R.string.mempool_row_meta,
                DateFormat.getDateTimeInstance().format(new Date(entry.observedAtMillis)),
                entry.inputCount, entry.outputCount, entry.sizeBytes));
        holder.outputSum.setText(context.getString(R.string.mempool_row_output_sum,
                MoneyFormatter.format(context, Coin.valueOf(entry.outputValueSatoshis))));
        holder.observations.setText(context.getString(R.string.mempool_row_observations,
                entry.observationCount));
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onEntryClick(entry);
            }
        });
    }

    private static int resultLabel(android.content.Context context,
                                   MempoolMonitor.RiskStatus status) {
        if (status == MempoolMonitor.RiskStatus.NON_FINAL) {
            return R.string.mempool_result_non_final;
        }
        if (status == MempoolMonitor.RiskStatus.NON_STANDARD) {
            return R.string.mempool_result_non_standard;
        }
        if (status == MempoolMonitor.RiskStatus.ERROR) {
            return R.string.mempool_result_error;
        }
        return R.string.mempool_result_ok;
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView txId;
        final TextView result;
        final TextView walletRelated;
        final TextView meta;
        final TextView outputSum;
        final TextView observations;

        Holder(View itemView) {
            super(itemView);
            txId = itemView.findViewById(R.id.mempool_row_txid);
            result = itemView.findViewById(R.id.mempool_row_result);
            walletRelated = itemView.findViewById(R.id.mempool_row_wallet_related);
            meta = itemView.findViewById(R.id.mempool_row_meta);
            outputSum = itemView.findViewById(R.id.mempool_row_output_sum);
            observations = itemView.findViewById(R.id.mempool_row_observations);
        }
    }
}
