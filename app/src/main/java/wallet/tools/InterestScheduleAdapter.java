package wallet.tools;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import wallet.main.R;

import java.util.ArrayList;
import java.util.List;

/** Binds projected interest schedule rows to the dedicated XML row layout. */
public final class InterestScheduleAdapter extends RecyclerView.Adapter<InterestScheduleAdapter.ScheduleViewHolder> {
    private final List<Row> rows = new ArrayList<>();

    public void setRows(List<Row> newRows) {
        rows.clear();
        if (newRows != null) rows.addAll(newRows);
        notifyDataSetChanged();
    }

    @Override
    public ScheduleViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View rowView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_calculator_schedule_row, parent, false);
        return new ScheduleViewHolder(rowView);
    }

    @Override
    public void onBindViewHolder(ScheduleViewHolder holder, int position) {
        Row row = rows.get(position);
        holder.period.setText(row.period);
        holder.added.setText(row.added);
        holder.interest.setText(row.interest);
        holder.balance.setText(row.balance);
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    public static final class Row {
        public final String period;
        public final String added;
        public final String interest;
        public final String balance;

        public Row(String period, String added, String interest, String balance) {
            this.period = period;
            this.added = added;
            this.interest = interest;
            this.balance = balance;
        }
    }

    static final class ScheduleViewHolder extends RecyclerView.ViewHolder {
        final TextView period;
        final TextView added;
        final TextView interest;
        final TextView balance;

        ScheduleViewHolder(View itemView) {
            super(itemView);
            period = itemView.findViewById(R.id.calculatorSchedulePeriod);
            added = itemView.findViewById(R.id.calculatorScheduleAdded);
            interest = itemView.findViewById(R.id.calculatorScheduleInterest);
            balance = itemView.findViewById(R.id.calculatorScheduleBalance);
        }
    }
}
