package wallet.settings;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;

import org.bitcoinj.base.Coin;

import java.util.ArrayList;
import java.util.List;
import wallet.ui.BaseActivity;
import wallet.util.MoneyFormatter;
import wallet.main.R;

/** Currency preferences for Bitcoin amount display and decimal precision. */
public class CurrencyUnitActivity extends BaseActivity {
    private Spinner currencyUnitSpinner;
    private Spinner decimalPrecisionSpinner;
    private TextView currencyUnitDescription;
    private TextView currencyAmountPreview;
    private String[] currencyUnitLabels;
    private String[] currencyUnitValues;
    private boolean initializing = true;
    private boolean updatingPrecisionSpinner;
    private int programmaticPrecisionSelection = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_currency_unit);

        Toolbar toolbar = findViewById(R.id.toolbar_currency_unit);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.currency_unit_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        currencyUnitSpinner = findViewById(R.id.currencyUnitSpinner);
        decimalPrecisionSpinner = findViewById(R.id.decimalPrecisionSpinner);
        currencyUnitDescription = findViewById(R.id.currencyUnitDescription);
        currencyAmountPreview = findViewById(R.id.currencyAmountPreview);
        currencyUnitLabels = new String[] {
                getString(R.string.currency_unit_unit_option_btc),
                getString(R.string.currency_unit_unit_option_mbtc),
                getString(R.string.currency_unit_unit_option_ubtc),
                getString(R.string.currency_unit_unit_option_sat)
        };
        currencyUnitValues = new String[] {
                MoneyFormatter.BTC, MoneyFormatter.MBTC, MoneyFormatter.UBTC, MoneyFormatter.SAT
        };

        ArrayAdapter<String> unitAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, currencyUnitLabels);
        unitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        currencyUnitSpinner.setAdapter(unitAdapter);
        currencyUnitSpinner.setSelection(indexOfCurrencyUnit(MoneyFormatter.getUnit(this)));
        rebuildDecimalPrecisionSpinner(MoneyFormatter.getPrecision(this));
        renderCurrencyDescriptions();

        currencyUnitSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (initializing) return;
                String selectedCurrencyUnit = currencyUnitValues[position];
                if (!selectedCurrencyUnit.equals(MoneyFormatter.getUnit(CurrencyUnitActivity.this))) {
                    MoneyFormatter.setUnit(CurrencyUnitActivity.this, selectedCurrencyUnit);
                    updatingPrecisionSpinner = true;
                    rebuildDecimalPrecisionSpinner(MoneyFormatter.getPrecision(CurrencyUnitActivity.this));
                    updatingPrecisionSpinner = false;
                    renderCurrencyDescriptions();
                    wallet.widget.BalanceWidgetProvider.requestRefresh(CurrencyUnitActivity.this);
                }
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        decimalPrecisionSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (initializing || updatingPrecisionSpinner) return;
                if (programmaticPrecisionSelection >= 0) {
                    // Adapter replacement can emit an initial selection callback; don't treat it as user input.
                    programmaticPrecisionSelection = -1;
                    return;
                }
                MoneyFormatter.setPrecision(CurrencyUnitActivity.this, position);
                renderCurrencyPreview();
                wallet.widget.BalanceWidgetProvider.requestRefresh(CurrencyUnitActivity.this);
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        initializing = false;
        renderCurrencyDescriptions();
    }

    private int indexOfCurrencyUnit(String unit) {
        for (int i = 0; i < currencyUnitValues.length; i++) {
            if (currencyUnitValues[i].equals(unit)) return i;
        }
        return 0;
    }

    private void rebuildDecimalPrecisionSpinner(int selectedPrecision) {
        String unit = MoneyFormatter.getUnit(this);
        int max = MoneyFormatter.maxPrecisionForUnit(unit);
        List<String> options = new ArrayList<>();
        for (int decimals = 0; decimals <= max; decimals++) {
            if (decimals == 0) {
                options.add(getString(R.string.currency_unit_precision_value_zero));
            } else if (decimals == 1) {
                options.add(getString(R.string.currency_unit_precision_value_singular));
            } else {
                options.add(getString(R.string.currency_unit_precision_value, decimals));
            }
        }
        int safeSelection = Math.max(0, Math.min(max, selectedPrecision));
        programmaticPrecisionSelection = safeSelection;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        decimalPrecisionSpinner.setAdapter(adapter);
        decimalPrecisionSpinner.setSelection(safeSelection);
    }

    private void renderCurrencyDescriptions() {
        String unit = MoneyFormatter.getUnit(this);
        int description = MoneyFormatter.BTC.equals(unit) ? R.string.currency_unit_unit_btc_description
                : MoneyFormatter.MBTC.equals(unit) ? R.string.currency_unit_unit_mbtc_description
                : MoneyFormatter.UBTC.equals(unit) ? R.string.currency_unit_unit_ubtc_description
                : R.string.currency_unit_unit_sat_description;
        currencyUnitDescription.setText(description);
        renderCurrencyPreview();
    }

    private void renderCurrencyPreview() {
        // 123,456,789 sat = 1.23456789 BTC; display rounding is presentation-only.
        currencyAmountPreview.setText(MoneyFormatter.format(this, Coin.valueOf(123456789L)));
    }
}
