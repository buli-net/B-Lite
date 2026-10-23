/* Experimental full-pruned node integration. */
package wallet.node;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import org.bitcoinj.base.BitcoinNetwork;


import wallet.main.R;
import wallet.network.NetworkConfig;
import wallet.ui.BaseActivity;

/** UI for the optional experimental full-pruned bitcoinj engine. */
public final class FullPrunedNodeActivity extends BaseActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView networkView;
    private TextView statusView;
    private TextView detailsView;
    private TextView errorView;
    private Button startButton;
    private Button stopButton;
    private BitcoinNetwork displayedNetwork;

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 2500L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_full_pruned_node);
        Toolbar toolbar = findViewById(R.id.toolbarFullPrunedNode);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.full_pruned_node_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        networkView = findViewById(R.id.fullPrunedNodeNetwork);
        statusView = findViewById(R.id.fullPrunedNodeStatus);
        detailsView = findViewById(R.id.fullPrunedNodeDetails);
        errorView = findViewById(R.id.fullPrunedNodeError);
        startButton = findViewById(R.id.fullPrunedNodeStart);
        stopButton = findViewById(R.id.fullPrunedNodeStop);
        Button refreshButton = findViewById(R.id.fullPrunedNodeRefresh);

        startButton.setOnClickListener(v -> {
            displayedNetwork = NetworkConfig.get(this);
            FullPrunedNodeService.start(this, displayedNetwork);
            render();
        });
        stopButton.setOnClickListener(v -> FullPrunedNodeService.stop(this, displayedNetwork));
        refreshButton.setOnClickListener(v -> render());
        render();
    }

    private void render() {
        displayedNetwork = NetworkConfig.get(this);
        FullPrunedNodeStatus.Snapshot snapshot = FullPrunedNodeStatus.read(this, displayedNetwork);
        networkView.setText(getString(R.string.full_pruned_node_network,
                NetworkConfig.displayName(this, displayedNetwork)));

        int stateText;
        switch (snapshot.state) {
            case "starting": stateText = R.string.full_pruned_node_state_starting; break;
            case "running": stateText = R.string.full_pruned_node_state_running; break;
            case "stopping": stateText = R.string.full_pruned_node_state_stopping; break;
            case "error": stateText = R.string.full_pruned_node_state_error; break;
            case "stalled": stateText = R.string.full_pruned_node_state_stalled; break;
            default: stateText = R.string.full_pruned_node_state_stopped;
        }
        statusView.setText(stateText);

        String height = getString(R.string.full_pruned_node_height, snapshot.height);
        String peers = getString(R.string.full_pruned_node_peers, snapshot.peers);
        String remaining = getString(R.string.full_pruned_node_blocks_remaining, snapshot.blocksLeft);
        String storage = getString(R.string.full_pruned_node_storage,
                android.text.format.Formatter.formatShortFileSize(this, snapshot.databaseBytes));
        String lastUpdated = snapshot.updatedAt > 0
                ? getString(R.string.full_pruned_node_last_updated,
                    android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(snapshot.updatedAt)))
                : getString(R.string.full_pruned_node_not_updated);
        detailsView.setText(TextUtils.concat(height, "\n", peers, "\n", remaining, "\n", storage, "\n", lastUpdated));

        if (!TextUtils.isEmpty(snapshot.error)) {
            errorView.setText(getString(R.string.full_pruned_node_error_detail, snapshot.error));
            errorView.setVisibility(View.VISIBLE);
        } else {
            errorView.setVisibility(View.GONE);
        }
        boolean active = snapshot.enabled || "starting".equals(snapshot.state)
                || "running".equals(snapshot.state) || "stopping".equals(snapshot.state);
        startButton.setEnabled(!active);
        stopButton.setEnabled(active);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
