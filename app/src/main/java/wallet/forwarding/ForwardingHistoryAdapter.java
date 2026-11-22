package wallet.forwarding;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import wallet.main.R;

/** RecyclerView adapter for the forwarding activity history. */
final class ForwardingHistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    interface RowBinder {
        void bind(View itemView, JSONObject entry);
    }

    interface OnEntryClickListener {
        void onEntryClick(String incomingTxId);
    }

    private static final int TYPE_ENTRY = 0;
    private static final int TYPE_EMPTY = 1;

    private final LayoutInflater inflater;
    private final RowBinder rowBinder;
    private final OnEntryClickListener clickListener;
    private List<JSONObject> entries = Collections.emptyList();
    private int emptyMessageRes = R.string.forwarding_activity_history_empty;

    ForwardingHistoryAdapter(Context context, RowBinder rowBinder,
                             OnEntryClickListener clickListener) {
        this.inflater = LayoutInflater.from(context);
        this.rowBinder = rowBinder;
        this.clickListener = clickListener;
        setHasStableIds(false);
    }

    void replaceEntries(List<JSONObject> newEntries, int emptyMessageRes) {
        entries = newEntries == null
                ? Collections.emptyList() : new ArrayList<>(newEntries);
        this.emptyMessageRes = emptyMessageRes;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return entries.isEmpty() ? TYPE_EMPTY : TYPE_ENTRY;
    }

    @Override
    public int getItemCount() {
        // Preserve the previous in-scroll empty-state message as a single list row.
        return entries.isEmpty() ? 1 : entries.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_EMPTY) {
            View view = inflater.inflate(R.layout.item_forwarding_history_empty, parent, false);
            return new EmptyViewHolder(view);
        }
        View view = inflater.inflate(R.layout.item_forwarding_history, parent, false);
        return new EntryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof EmptyViewHolder) {
            ((EmptyViewHolder) holder).message.setText(emptyMessageRes);
            holder.itemView.setOnClickListener(null);
            return;
        }

        JSONObject entry = entries.get(position);
        rowBinder.bind(holder.itemView, entry);
        String incomingTxId = entry.optString("incoming", "");
        if (incomingTxId.isEmpty()) {
            holder.itemView.setOnClickListener(null);
        } else {
            holder.itemView.setOnClickListener(
                    view -> clickListener.onEntryClick(incomingTxId));
        }
    }

    private static final class EntryViewHolder extends RecyclerView.ViewHolder {
        EntryViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    private static final class EmptyViewHolder extends RecyclerView.ViewHolder {
        final TextView message;

        EmptyViewHolder(@NonNull View itemView) {
            super(itemView);
            message = (TextView) itemView;
        }
    }
}
