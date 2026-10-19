package wallet.main;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import org.bitcoinj.base.Coin;

import java.util.ArrayList;
import java.util.List;

/** General wallet preferences, including shared Bitcoin amount display settings. */
public class GeneralActivity extends BaseActivity {
    private Spinner unitSpinner;
    private Spinner precisionSpinner;
    private TextView unitDescription;
    private TextView preview;
    private String[] unitLabels;
    private String[] unitValues;
    private boolean initializing = true;
    private boolean updatingPrecisionSpinner;
    private int programmaticPrecisionSelection = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_general);

        Toolbar toolbar = findViewById(R.id.toolbar_general);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.general_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        unitSpinner = findViewById(R.id.generalUnitSpinner);
        precisionSpinner = findViewById(R.id.generalPrecisionSpinner);
        unitDescription = findViewById(R.id.generalUnitDescription);
        preview = findViewById(R.id.generalMoneyPreview);
        unitLabels = new String[] {
                getString(R.string.general_unit_option_btc),
                getString(R.string.general_unit_option_mbtc),
                getString(R.string.general_unit_option_ubtc),
                getString(R.string.general_unit_option_sat)
        };
        unitValues = new String[] {
                MoneyFormatter.BTC, MoneyFormatter.MBTC, MoneyFormatter.UBTC, MoneyFormatter.SAT
        };

        ArrayAdapter<String> unitAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, unitLabels);
        unitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        unitSpinner.setAdapter(unitAdapter);
        unitSpinner.setSelection(indexOfUnit(MoneyFormatter.getUnit(this)));
        rebuildPrecisionSpinner(MoneyFormatter.getPrecision(this));
        renderDescriptions();

        unitSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (initializing) return;
                String selectedUnit = unitValues[position];
                if (!selectedUnit.equals(MoneyFormatter.getUnit(GeneralActivity.this))) {
                    MoneyFormatter.setUnit(GeneralActivity.this, selectedUnit);
                    updatingPrecisionSpinner = true;
                    rebuildPrecisionSpinner(MoneyFormatter.getPrecision(GeneralActivity.this));
                    updatingPrecisionSpinner = false;
                    renderDescriptions();
                    wallet.widget.BalanceWidgetProvider.requestRefresh(GeneralActivity.this);
                }
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        precisionSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (initializing || updatingPrecisionSpinner) return;
                if (programmaticPrecisionSelection >= 0) {
                    // Adapter replacement can emit an initial selection callback; don't treat it as user input.
                    programmaticPrecisionSelection = -1;
                    return;
                }
                MoneyFormatter.setPrecision(GeneralActivity.this, position);
                renderPreview();
                wallet.widget.BalanceWidgetProvider.requestRefresh(GeneralActivity.this);
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        initializing = false;
        renderDescriptions();
    }

    private int indexOfUnit(String unit) {
        for (int i = 0; i < unitValues.length; i++) {
            if (unitValues[i].equals(unit)) return i;
        }
        return 0;
    }

    private void rebuildPrecisionSpinner(int selectedPrecision) {
        String unit = MoneyFormatter.getUnit(this);
        int max = MoneyFormatter.maxPrecisionForUnit(unit);
        List<String> options = new ArrayList<>();
        for (int decimals = 0; decimals <= max; decimals++) {
            if (decimals == 0) {
                options.add(getString(R.string.general_precision_value_zero));
            } else if (decimals == 1) {
                options.add(getString(R.string.general_precision_value_singular));
            } else {
                options.add(getString(R.string.general_precision_value, decimals));
            }
        }
        int safeSelection = Math.max(0, Math.min(max, selectedPrecision));
        programmaticPrecisionSelection = safeSelection;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        precisionSpinner.setAdapter(adapter);
        precisionSpinner.setSelection(safeSelection);
    }

    private void renderDescriptions() {
        String unit = MoneyFormatter.getUnit(this);
        int description = MoneyFormatter.BTC.equals(unit) ? R.string.general_unit_btc_description
                : MoneyFormatter.MBTC.equals(unit) ? R.string.general_unit_mbtc_description
                : MoneyFormatter.UBTC.equals(unit) ? R.string.general_unit_ubtc_description
                : R.string.general_unit_sat_description;
        unitDescription.setText(description);
        renderPreview();
    }

    private void renderPreview() {
        // 123,456,789 sat = 1.23456789 BTC; display rounding is presentation-only.
        preview.setText(MoneyFormatter.format(this, Coin.valueOf(123456789L)));
    }
}
