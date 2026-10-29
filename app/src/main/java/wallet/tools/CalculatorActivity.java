package wallet.tools;

import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import wallet.main.R;
import wallet.ui.BaseActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Offline standard calculator and simple/compound interest projection. */
public class CalculatorActivity extends BaseActivity {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String PREFS = "calculator_preferences";
    private static final String PREF_CURRENCY = "interest_currency";
    private static final String PREF_CURRENCY_MANUAL = "interest_currency_manual";
    // Distinguishes an explicit user choice from legacy scenario loads that also set the old manual flag.
    private static final String PREF_CURRENCY_SELECTION_SOURCE = "interest_currency_selection_source";
    private static final String CURRENCY_SELECTION_SOURCE_USER = "user";
    private static final String PREF_SCENARIOS = "interest_scenarios";
    private static final String[] PREFERRED_CURRENCY_CODES = {
            "USD", "VND", "EUR", "GBP", "JPY", "CNY", "KRW", "THB", "IDR", "INR",
            "CAD", "AUD", "CHF", "SGD", "MYR", "PHP", "BRL", "MXN", "ARS", "CLP",
            "COP", "RUB", "TRY", "ZAR", "SEK", "NOK", "DKK", "NZD", "HKD", "TWD",
            "PLN", "CZK", "HUF", "RON", "UAH", "ILS", "SAR", "AED", "EGP", "PKR"
    };

    private final List<String> currencyCodes = new ArrayList<>();
    private Locale locale;
    private SharedPreferences preferences;
    private View standardPanel;
    private View interestPanel;
    private Button standardTab;
    private Button interestTab;
    private TextView expressionView;
    private TextView calculatorResultView;
    private String calculatorExpression = "";
    private boolean justEvaluated;

    private EditText principalInput;
    private EditText rateInput;
    private EditText contributionInput;
    private Spinner currencySpinner;
    private Spinner frequencySpinner;
    private Spinner tableModeSpinner;
    private TextView startDateValue;
    private TextView endDateValue;
    private Button simpleModeButton;
    private Button compoundModeButton;
    private TextView finalAmountValue;
    private TextView earnedInterestValue;
    private TextView investedAmountValue;
    private TextView durationValue;
    private TextView tableTitle;
    private TextView scheduleLimitView;
    private RecyclerView scheduleRecyclerView;
    private RecyclerView scenariosRecyclerView;
    private InterestScheduleAdapter scheduleAdapter;
    private ScenarioAdapter scenarioAdapter;
    private InterestChartView chartView;
    private LocalDate startDate;
    private LocalDate endDate;
    private boolean compoundMode = true;
    private int lastCurrencySelection = -1;
    private String selectedCurrencyCode = "USD";
    private final Set<String> comparisonIds = new HashSet<>();
    private final List<JSONObject> scenarios = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        locale = getResources().getConfiguration().getLocales().get(0);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildCurrencyCodes();
        startDate = LocalDate.now();
        endDate = startDate.plusYears(5);
        selectedCurrencyCode = defaultCurrencyCode(locale);
        boolean currencyWasMarkedManual = preferences.getBoolean(PREF_CURRENCY_MANUAL, false);
        String currencySelectionSource = preferences.getString(PREF_CURRENCY_SELECTION_SOURCE, "");
        if (currencyWasMarkedManual && !CURRENCY_SELECTION_SOURCE_USER.equals(currencySelectionSource)) {
            // Older versions also marked a scenario's currency as a permanent manual preference.
            // The old data cannot distinguish that case from a real user choice, so reset it once
            // and let the current app locale supply the default. Future explicit choices are tagged.
            preferences.edit()
                    .putBoolean(PREF_CURRENCY_MANUAL, false)
                    .remove(PREF_CURRENCY)
                    .remove(PREF_CURRENCY_SELECTION_SOURCE)
                    .apply();
            currencyWasMarkedManual = false;
        }
        if (currencyWasMarkedManual) {
            String saved = preferences.getString(PREF_CURRENCY, selectedCurrencyCode);
            if (isSupportedCurrency(saved)) selectedCurrencyCode = saved;
        }
        loadScenarios();
        initializeViews();
        restoreUiState(state);
        updateDateLabels();
        updateInterestView();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("calculator_expression", calculatorExpression);
        outState.putString("calculator_result", calculatorResultView == null ? "0" : calculatorResultView.getText().toString());
        View scrollView = findViewById(R.id.calculatorScrollView);
        outState.putInt("calculator_scroll_y", scrollView == null ? 0 : scrollView.getScrollY());
        outState.putBoolean("calculator_just_evaluated", justEvaluated);
        outState.putBoolean("calculator_interest_panel", interestPanel != null
                && interestPanel.getVisibility() == View.VISIBLE);
        outState.putString("calculator_principal", principalInput == null ? "10000" : principalInput.getText().toString());
        outState.putString("calculator_rate", rateInput == null ? "8" : rateInput.getText().toString());
        outState.putString("calculator_contribution", contributionInput == null ? "0" : contributionInput.getText().toString());
        outState.putString("calculator_start_date", startDate == null ? null : startDate.toString());
        outState.putString("calculator_end_date", endDate == null ? null : endDate.toString());
        outState.putBoolean("calculator_compound_mode", compoundMode);
        outState.putString("calculator_locale_tag", locale == null ? "" : locale.toLanguageTag());
        outState.putString("calculator_currency_code", selectedCurrencyCode);
        outState.putInt("calculator_frequency_position", frequencySpinner == null ? 2 : frequencySpinner.getSelectedItemPosition());
        outState.putInt("calculator_table_mode_position", tableModeSpinner == null ? 0 : tableModeSpinner.getSelectedItemPosition());
        outState.putStringArrayList("calculator_comparison_ids", new ArrayList<>(comparisonIds));
    }

    private void restoreUiState(Bundle state) {
        if (state == null) {
            showPanel(false);
            return;
        }
        calculatorExpression = state.getString("calculator_expression", "");
        justEvaluated = state.getBoolean("calculator_just_evaluated", false);
        expressionView.setText(calculatorExpression);
        calculatorResultView.setText(state.getString("calculator_result", getString(R.string.calculator_zero)));
        View scrollView = findViewById(R.id.calculatorScrollView);
        if (scrollView != null) {
            int savedScrollY = Math.max(0, state.getInt("calculator_scroll_y", 0));
            scrollView.post(() -> scrollView.scrollTo(0, savedScrollY));
        }
        try {
            startDate = LocalDate.parse(state.getString("calculator_start_date", LocalDate.now().toString()));
            endDate = LocalDate.parse(state.getString("calculator_end_date", startDate.plusYears(5).toString()));
        } catch (RuntimeException ignored) {
            startDate = LocalDate.now();
            endDate = startDate.plusYears(5);
        }
        principalInput.setText(state.getString("calculator_principal", "10000"));
        rateInput.setText(state.getString("calculator_rate", "8"));
        contributionInput.setText(state.getString("calculator_contribution", "0"));
        compoundMode = state.getBoolean("calculator_compound_mode", true);
        String savedLocaleTag = state.getString("calculator_locale_tag", "");
        boolean localeUnchanged = locale != null && locale.toLanguageTag().equals(savedLocaleTag);
        if (localeUnchanged) {
            String restoredCurrencyCode = state.getString("calculator_currency_code", selectedCurrencyCode);
            if (isSupportedCurrency(restoredCurrencyCode)) {
                selectedCurrencyCode = restoredCurrencyCode;
            }
        }
        setCurrencySelectionSilently(selectedCurrencyCode);
        setSpinnerSelection(frequencySpinner, state.getInt("calculator_frequency_position", 2));
        setSpinnerSelection(tableModeSpinner, state.getInt("calculator_table_mode_position", 0));
        ArrayList<String> savedComparisons = state.getStringArrayList("calculator_comparison_ids");
        if (savedComparisons != null) {
            comparisonIds.clear();
            comparisonIds.addAll(savedComparisons);
        }
        styleMethodButtons();
        showPanel(state.getBoolean("calculator_interest_panel", false));
        if (scenarioAdapter != null) scenarioAdapter.notifyDataSetChanged();
    }

    private void setSpinnerSelection(Spinner spinner, int position) {
        if (spinner == null || spinner.getAdapter() == null || spinner.getAdapter().getCount() == 0) return;
        int safePosition = Math.max(0, Math.min(position, spinner.getAdapter().getCount() - 1));
        spinner.setSelection(safePosition, false);
    }

    private void setCurrencySelectionSilently(String currencyCode) {
        if (currencySpinner == null || currencySpinner.getAdapter() == null
                || currencySpinner.getAdapter().getCount() == 0) return;
        int position = currencyIndex(currencyCode);
        if (position < 0 || position >= currencyCodes.size()) {
            position = currencyIndex(defaultCurrencyCode(locale));
        }
        if (position < 0 || position >= currencyCodes.size()) position = 0;
        selectedCurrencyCode = currencyCodes.get(position);
        // Set the comparison value before changing the Spinner so its programmatic callback
        // cannot be mistaken for a user's explicit currency selection.
        lastCurrencySelection = position;
        currencySpinner.setSelection(position, false);
    }

    private void initializeViews() {
        setContentView(R.layout.activity_calculator);

        Toolbar toolbar = findViewById(R.id.toolbar_calculator);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.calculator_title);
        }
        toolbar.setNavigationOnClickListener(view -> finish());

        standardPanel = findViewById(R.id.calculatorStandardPanel);
        interestPanel = findViewById(R.id.calculatorInterestPanel);
        standardTab = findViewById(R.id.calculatorStandardTab);
        interestTab = findViewById(R.id.calculatorInterestTab);
        expressionView = findViewById(R.id.calculatorExpression);
        calculatorResultView = findViewById(R.id.calculatorResult);

        standardTab.setOnClickListener(view -> showPanel(false));
        interestTab.setOnClickListener(view -> showPanel(true));
        bindStandardPanel();
        bindInterestPanel();
        showPanel(false);
    }

    private void bindStandardPanel() {
        int[] keyIds = {
                R.id.calculatorKeyClear,
                R.id.calculatorKeyOpenParenthesis,
                R.id.calculatorKeyCloseParenthesis,
                R.id.calculatorKeyDivide,
                R.id.calculatorKey7,
                R.id.calculatorKey8,
                R.id.calculatorKey9,
                R.id.calculatorKeyMultiply,
                R.id.calculatorKey4,
                R.id.calculatorKey5,
                R.id.calculatorKey6,
                R.id.calculatorKeySubtract,
                R.id.calculatorKey1,
                R.id.calculatorKey2,
                R.id.calculatorKey3,
                R.id.calculatorKeyAdd,
                R.id.calculatorKeySign,
                R.id.calculatorKey0,
                R.id.calculatorKeyDecimal,
                R.id.calculatorKeyPercent,
                R.id.calculatorKeyBackspace,
                R.id.calculatorKeyEquals
        };
        for (int keyId : keyIds) {
            Button keyButton = findViewById(keyId);
            keyButton.setOnClickListener(view -> {
                Object tag = view.getTag();
                if (tag instanceof String) handleCalculatorKey((String) tag);
            });
        }
        Button decimalButton = findViewById(R.id.calculatorKeyDecimal);
        char decimalSeparator = DecimalFormatSymbols.getInstance(locale).getDecimalSeparator();
        decimalButton.setText(String.valueOf(decimalSeparator));
        decimalButton.setContentDescription(getString(R.string.calculator_key_decimal_description));
    }

    private void bindInterestPanel() {
        principalInput = findViewById(R.id.calculatorPrincipalInput);
        rateInput = findViewById(R.id.calculatorRateInput);
        contributionInput = findViewById(R.id.calculatorContributionInput);
        currencySpinner = findViewById(R.id.calculatorCurrencySpinner);
        frequencySpinner = findViewById(R.id.calculatorFrequencySpinner);
        tableModeSpinner = findViewById(R.id.calculatorTableModeSpinner);
        startDateValue = findViewById(R.id.calculatorStartDateValue);
        endDateValue = findViewById(R.id.calculatorEndDateValue);
        simpleModeButton = findViewById(R.id.calculatorSimpleModeButton);
        compoundModeButton = findViewById(R.id.calculatorCompoundModeButton);
        finalAmountValue = findViewById(R.id.calculatorFinalAmountValue);
        earnedInterestValue = findViewById(R.id.calculatorEarnedInterestValue);
        investedAmountValue = findViewById(R.id.calculatorInvestedAmountValue);
        durationValue = findViewById(R.id.calculatorDurationValue);
        tableTitle = findViewById(R.id.calculatorScheduleTitle);
        scheduleLimitView = findViewById(R.id.calculatorScheduleLimit);
        chartView = findViewById(R.id.calculatorInterestChart);
        scheduleRecyclerView = findViewById(R.id.calculatorScheduleRecycler);
        scenariosRecyclerView = findViewById(R.id.calculatorScenarioRecycler);

        ArrayAdapter<String> currencyAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, currencyLabels());
        currencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        currencySpinner.setAdapter(currencyAdapter);
        lastCurrencySelection = Math.max(0, currencyIndex(selectedCurrencyCode));
        currencySpinner.setSelection(lastCurrencySelection, false);
        currencySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < currencyCodes.size() && position != lastCurrencySelection) {
                    lastCurrencySelection = position;
                    selectedCurrencyCode = currencyCodes.get(position);
                    preferences.edit()
                            .putString(PREF_CURRENCY, selectedCurrencyCode)
                            .putBoolean(PREF_CURRENCY_MANUAL, true)
                            .putString(PREF_CURRENCY_SELECTION_SOURCE, CURRENCY_SELECTION_SOURCE_USER)
                            .apply();
                    updateInterestView();
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        ArrayAdapter<String> frequencyAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.calculator_frequency_yearly),
                        getString(R.string.calculator_frequency_quarterly),
                        getString(R.string.calculator_frequency_monthly),
                        getString(R.string.calculator_frequency_daily)});
        frequencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        frequencySpinner.setAdapter(frequencyAdapter);
        frequencySpinner.setSelection(2, false);
        frequencySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateInterestView();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        ArrayAdapter<String> tableAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.calculator_table_monthly),
                        getString(R.string.calculator_table_yearly)});
        tableAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        tableModeSpinner.setAdapter(tableAdapter);
        tableModeSpinner.setSelection(0, false);
        tableModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateTable();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        findViewById(R.id.calculatorChooseStartDateButton).setOnClickListener(view -> chooseDate(true));
        findViewById(R.id.calculatorChooseEndDateButton).setOnClickListener(view -> chooseDate(false));
        simpleModeButton.setOnClickListener(view -> {
            compoundMode = false;
            styleMethodButtons();
            updateInterestView();
        });
        compoundModeButton.setOnClickListener(view -> {
            compoundMode = true;
            styleMethodButtons();
            updateInterestView();
        });
        findViewById(R.id.calculatorCopyResultButton).setOnClickListener(view -> copyInterestResult());
        findViewById(R.id.calculatorSaveScenarioButton).setOnClickListener(view -> promptSaveScenario());

        scheduleAdapter = new InterestScheduleAdapter();
        scheduleRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        scheduleRecyclerView.setAdapter(scheduleAdapter);
        scheduleRecyclerView.setNestedScrollingEnabled(true);

        scenarioAdapter = new ScenarioAdapter();
        scenariosRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        scenariosRecyclerView.setAdapter(scenarioAdapter);
        scenariosRecyclerView.setNestedScrollingEnabled(true);
        renderScenarios();

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                updateInterestView();
            }
            @Override public void afterTextChanged(Editable value) { }
        };
        principalInput.addTextChangedListener(watcher);
        rateInput.addTextChangedListener(watcher);
        contributionInput.addTextChangedListener(watcher);
        styleMethodButtons();
    }

    private void showPanel(boolean interest) {
        standardPanel.setVisibility(interest ? View.GONE : View.VISIBLE);
        interestPanel.setVisibility(interest ? View.VISIBLE : View.GONE);
        styleTab(standardTab, !interest);
        styleTab(interestTab, interest);
    }

    private void styleTab(Button button, boolean selected) {
        // XML state lists resolve the selected appearance from the active app theme.
        button.setSelected(selected);
    }

    private void handleCalculatorKey(String key) {
        if ("clear".equals(key)) {
            calculatorExpression = "";
            justEvaluated = false;
            expressionView.setText("");
            calculatorResultView.setText(R.string.calculator_zero);
            return;
        }
        if ("backspace".equals(key)) {
            if (justEvaluated) calculatorExpression = "";
            else if (!calculatorExpression.isEmpty()) {
                calculatorExpression = calculatorExpression.substring(0, calculatorExpression.length() - 1);
            }
            justEvaluated = false;
            updateCalculatorExpressionDisplay();
            expressionView.setText("");
            return;
        }
        if ("equals".equals(key)) {
            try {
                String before = calculatorExpression;
                BigDecimal result = new ExpressionParser(calculatorExpression).parse();
                calculatorExpression = result.stripTrailingZeros().toPlainString();
                expressionView.setText(getString(R.string.calculator_expression_equals, displayExpression(before)));
                calculatorResultView.setText(formatCalculatorNumber(result));
                justEvaluated = true;
            } catch (RuntimeException ex) {
                calculatorResultView.setText(R.string.calculator_invalid_expression);
                justEvaluated = false;
            }
            return;
        }
        if ("sign".equals(key)) {
            toggleLastNumberSign();
            justEvaluated = false;
            updateCalculatorExpressionDisplay();
            expressionView.setText("");
            return;
        }

        boolean numberKey = key.startsWith("digit_") || "decimal".equals(key);
        if (justEvaluated && numberKey) {
            calculatorExpression = "";
            expressionView.setText("");
        }
        justEvaluated = false;
        String value;
        switch (key) {
            case "digit_0": value = "0"; break;
            case "digit_1": value = "1"; break;
            case "digit_2": value = "2"; break;
            case "digit_3": value = "3"; break;
            case "digit_4": value = "4"; break;
            case "digit_5": value = "5"; break;
            case "digit_6": value = "6"; break;
            case "digit_7": value = "7"; break;
            case "digit_8": value = "8"; break;
            case "digit_9": value = "9"; break;
            case "open_parenthesis": value = "("; break;
            case "close_parenthesis": value = ")"; break;
            case "divide": value = "/"; break;
            case "multiply": value = "*"; break;
            case "subtract": value = "-"; break;
            case "add": value = "+"; break;
            case "decimal": value = "."; break;
            case "percent": value = "%"; break;
            default: return;
        }
        if (isOperatorKey(value) && calculatorExpression.isEmpty() && !"-".equals(value)) return;
        calculatorExpression += value;
        calculatorResultView.setText(displayExpression(calculatorExpression));
        expressionView.setText("");
    }

    private void updateCalculatorExpressionDisplay() {
        if (calculatorExpression.isEmpty()) calculatorResultView.setText(R.string.calculator_zero);
        else calculatorResultView.setText(displayExpression(calculatorExpression));
    }

    private String displayExpression(String value) {
        char decimalSeparator = DecimalFormatSymbols.getInstance(locale).getDecimalSeparator();
        return value.replace('*', '×').replace('/', '÷').replace('-', '−').replace('.', decimalSeparator);
    }

    private void toggleLastNumberSign() {
        if (calculatorExpression.isEmpty()) {
            calculatorExpression = "-";
            return;
        }
        int currentLength = calculatorExpression.length();
        if (calculatorExpression.endsWith("-") && (currentLength == 1
                || (currentLength >= 2 && "+-*/(".indexOf(calculatorExpression.charAt(currentLength - 2)) >= 0))) {
            calculatorExpression = calculatorExpression.substring(0, currentLength - 1);
            return;
        }
        int end = calculatorExpression.length();
        int start = end;
        while (start > 0) {
            char c = calculatorExpression.charAt(start - 1);
            if ((c >= '0' && c <= '9') || c == '.' || c == ',') start--;
            else break;
        }
        if (start == end) {
            calculatorExpression += "-";
        } else if (start > 0 && calculatorExpression.charAt(start - 1) == '-') {
            boolean unary = start - 1 == 0 || (start >= 2 && "+-*/(".indexOf(calculatorExpression.charAt(start - 2)) >= 0);
            if (unary) calculatorExpression = calculatorExpression.substring(0, start - 1) + calculatorExpression.substring(start);
            else calculatorExpression = calculatorExpression.substring(0, start) + "-" + calculatorExpression.substring(start);
        } else {
            calculatorExpression = calculatorExpression.substring(0, start) + "-" + calculatorExpression.substring(start);
        }
    }

    private boolean isOperatorKey(String key) {
        return key.equals("+") || key.equals("-") || key.equals("*") || key.equals("/");
    }

    private String formatCalculatorNumber(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMaximumFractionDigits(12);
        format.setMinimumFractionDigits(0);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }

    private void styleMethodButtons() {
        if (simpleModeButton == null || compoundModeButton == null) return;
        styleMethodButton(simpleModeButton, !compoundMode);
        styleMethodButton(compoundModeButton, compoundMode);
    }

    private void styleMethodButton(Button button, boolean selected) {
        // Selection tint and text contrast are defined in XML using theme attributes.
        button.setSelected(selected);
    }

    private void chooseDate(boolean chooseStart) {
        LocalDate selected = chooseStart ? startDate : endDate;
        DatePickerDialog picker = new DatePickerDialog(this, (DatePicker view, int year, int month, int day) -> {
            LocalDate chosen = LocalDate.of(year, month + 1, day);
            if (chooseStart) {
                startDate = chosen;
                if (endDate.isBefore(startDate)) endDate = startDate.plusYears(1);
            } else {
                endDate = chosen.isBefore(startDate) ? startDate : chosen;
            }
            updateDateLabels();
            updateInterestView();
        }, selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth());
        picker.setTitle(chooseStart ? R.string.calculator_start_date : R.string.calculator_maturity_date);
        picker.show();
    }

    private void updateDateLabels() {
        DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
        if (startDateValue != null) startDateValue.setText(startDate.format(formatter));
        if (endDateValue != null) endDateValue.setText(endDate.format(formatter));
    }

    private void updateInterestView() {
        if (finalAmountValue == null || principalInput == null) return;
        CalculatorConfig config;
        try {
            config = readConfig();
        } catch (RuntimeException ex) {
            finalAmountValue.setText(R.string.calculator_placeholder);
            earnedInterestValue.setText(R.string.calculator_placeholder);
            investedAmountValue.setText(R.string.calculator_placeholder);
            durationValue.setText(R.string.calculator_placeholder);
            if (chartView != null) chartView.setSeries(Collections.emptyList());
            if (scheduleAdapter != null) scheduleAdapter.setRows(Collections.emptyList());
            if (scheduleLimitView != null) scheduleLimitView.setVisibility(View.GONE);
            return;
        }
        long days = Math.max(0, ChronoUnit.DAYS.between(startDate, endDate));
        AmountResult result = calculate(config, days);
        Currency currency = currencyFor(selectedCurrencyCode);
        finalAmountValue.setText(formatMoney(result.total, currency));
        earnedInterestValue.setText(formatMoney(result.total.subtract(result.invested), currency));
        investedAmountValue.setText(formatMoney(result.invested, currency));
        durationValue.setText(getResources().getQuantityString(R.plurals.calculator_day_count,
                (int) Math.min(days, Integer.MAX_VALUE), days));
        updateChart(config, days);
        updateTable();
    }

    private CalculatorConfig readConfig() {
        BigDecimal principal = parseDecimal(principalInput.getText().toString());
        BigDecimal rate = parseDecimal(rateInput.getText().toString());
        BigDecimal contribution = parseDecimal(contributionInput.getText().toString());
        if (principal.signum() < 0 || rate.signum() < 0 || contribution.signum() < 0)
            throw new IllegalArgumentException("negative input");
        if (rate.compareTo(new BigDecimal("1000")) > 0) throw new IllegalArgumentException("rate too high");
        return new CalculatorConfig(principal, rate, contribution, frequencyValue(), compoundMode,
                selectedCurrencyCode, startDate, endDate);
    }

    private BigDecimal parseDecimal(String value) {
        String text = value == null ? "" : value.trim()
                .replace(" ", "").replace("\u00a0", "").replace("\u202f", "");
        if (text.isEmpty()) return BigDecimal.ZERO;
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
        char decimalSeparator = symbols.getDecimalSeparator();
        char groupingSeparator = symbols.getGroupingSeparator();

        // Numeric keyboards may still enter a period even in locales that use a comma.
        // Honor the locale separator when it is present; otherwise accept a single period
        // as a decimal mark unless it looks like a three-digit thousands group.
        if (decimalSeparator != '.' && text.indexOf(decimalSeparator) >= 0) {
            int decimalIndex = text.lastIndexOf(decimalSeparator);
            String integerPart = text.substring(0, decimalIndex)
                    .replace(String.valueOf(groupingSeparator), "").replace(".", "");
            String fractionalPart = text.substring(decimalIndex + 1)
                    .replace(String.valueOf(groupingSeparator), "").replace(".", "");
            text = integerPart + "." + fractionalPart;
        } else if (decimalSeparator != '.' && groupingSeparator == '.') {
            int firstDot = text.indexOf('.');
            int lastDot = text.lastIndexOf('.');
            if (firstDot >= 0 && firstDot == lastDot && text.length() - lastDot - 1 != 3) {
                // Keep this period as the decimal point.
            } else {
                text = text.replace(".", "");
            }
        } else {
            if (groupingSeparator != decimalSeparator) text = text.replace(String.valueOf(groupingSeparator), "");
            if (decimalSeparator != '.') text = text.replace(decimalSeparator, '.');
        }
        return new BigDecimal(text, MC);
    }

    private int frequencyValue() {
        if (frequencySpinner == null) return 12;
        switch (frequencySpinner.getSelectedItemPosition()) {
            case 0: return 1;
            case 1: return 4;
            case 3: return 365;
            default: return 12;
        }
    }

    private AmountResult calculate(CalculatorConfig config, long days) {
        long safeDays = Math.max(0, days);
        LocalDate asOf = config.start.plusDays(safeDays);
        BigDecimal time = BigDecimal.valueOf(safeDays).divide(BigDecimal.valueOf(365), MC);
        long completePeriods = completedPeriods(config.start, asOf, config.frequency);
        BigDecimal fractionalPeriod = fractionalPeriod(config.start, asOf, config.frequency, completePeriods);
        BigDecimal contributionTotal = config.contribution.multiply(BigDecimal.valueOf(completePeriods), MC);
        BigDecimal invested = config.principal.add(contributionTotal, MC);
        BigDecimal annualRate = config.rate.divide(BigDecimal.valueOf(100), MC);

        if (!config.compound) {
            BigDecimal baseFactor = BigDecimal.ONE.add(annualRate.multiply(time, MC), MC);
            BigDecimal total = config.principal.multiply(baseFactor, MC);
            if (completePeriods > 0 && config.contribution.signum() != 0) {
                long sumContributionDays = 0;
                if (config.frequency == 365) {
                    sumContributionDays = completePeriods * (completePeriods + 1) / 2;
                } else {
                    for (long period = 1; period <= completePeriods; period++) {
                        long contributionDay = ChronoUnit.DAYS.between(config.start,
                                periodBoundary(config.start, config.frequency, period));
                        sumContributionDays += contributionDay;
                    }
                }
                BigDecimal totalContributionBase = config.contribution.multiply(BigDecimal.valueOf(completePeriods), MC);
                BigDecimal remainingInterestDays = BigDecimal.valueOf(completePeriods).multiply(BigDecimal.valueOf(safeDays), MC)
                        .subtract(BigDecimal.valueOf(sumContributionDays), MC);
                BigDecimal contributionInterest = config.contribution.multiply(annualRate, MC)
                        .multiply(remainingInterestDays, MC).divide(BigDecimal.valueOf(365), MC);
                total = total.add(totalContributionBase, MC).add(contributionInterest, MC);
            }
            return new AmountResult(total, invested);
        }

        BigDecimal periodRate = annualRate.divide(BigDecimal.valueOf(config.frequency), MC);
        BigDecimal growth = BigDecimal.ONE.add(periodRate, MC);
        BigDecimal wholeGrowth = growth.pow((int) Math.min(completePeriods, Integer.MAX_VALUE), MC);
        double fractionalFactorDouble = Math.pow(growth.doubleValue(), fractionalPeriod.doubleValue());
        if (!Double.isFinite(fractionalFactorDouble)) fractionalFactorDouble = Double.MAX_VALUE;
        BigDecimal fractionalGrowth = BigDecimal.valueOf(fractionalFactorDouble);
        BigDecimal factor = wholeGrowth.multiply(fractionalGrowth, MC);
        BigDecimal total = config.principal.multiply(factor, MC);
        if (completePeriods > 0 && config.contribution.signum() != 0) {
            BigDecimal contributionGrowth = growth.compareTo(BigDecimal.ONE) == 0
                    ? BigDecimal.valueOf(completePeriods)
                    : wholeGrowth.subtract(BigDecimal.ONE, MC).divide(growth.subtract(BigDecimal.ONE, MC), MC);
            total = total.add(config.contribution.multiply(contributionGrowth, MC)
                    .multiply(fractionalGrowth, MC), MC);
        }
        return new AmountResult(total, invested);
    }

    private LocalDate periodBoundary(LocalDate start, int frequency, long periodIndex) {
        if (frequency == 365) return start.plusDays(periodIndex);
        if (frequency == 12) return start.plusMonths(periodIndex);
        if (frequency == 4) return start.plusMonths(periodIndex * 3L);
        return start.plusYears(periodIndex);
    }

    private long completedPeriods(LocalDate start, LocalDate asOf, int frequency) {
        long days = Math.max(0, ChronoUnit.DAYS.between(start, asOf));
        long count = frequency == 365 ? days : (long) Math.floor((double) days * frequency / 365.0d);
        while (count > 0 && periodBoundary(start, frequency, count).isAfter(asOf)) count--;
        while (!periodBoundary(start, frequency, count + 1).isAfter(asOf)) count++;
        return count;
    }

    private BigDecimal fractionalPeriod(LocalDate start, LocalDate asOf, int frequency, long completed) {
        if (frequency == 365) return BigDecimal.ZERO;
        LocalDate lastBoundary = periodBoundary(start, frequency, completed);
        LocalDate nextBoundary = periodBoundary(start, frequency, completed + 1);
        long elapsed = Math.max(0, ChronoUnit.DAYS.between(lastBoundary, asOf));
        long interval = ChronoUnit.DAYS.between(lastBoundary, nextBoundary);
        if (interval <= 0 || elapsed <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(elapsed).divide(BigDecimal.valueOf(interval), MC);
    }

    private void updateChart(CalculatorConfig config, long days) {
        if (chartView == null) return;
        List<InterestChartView.Series> values = new ArrayList<>();
        List<Double> total = new ArrayList<>();
        List<Double> invested = new ArrayList<>();
        int pointCount = 41;
        for (int i = 0; i < pointCount; i++) {
            long pointDays = Math.round((double) days * i / (pointCount - 1));
            AmountResult result = calculate(config, pointDays);
            total.add(chartNumber(result.total));
            invested.add(chartNumber(result.invested));
        }
        values.add(new InterestChartView.Series(getString(R.string.calculator_chart_total), total, false));
        values.add(new InterestChartView.Series(getString(R.string.calculator_chart_invested), invested, true));
        for (JSONObject scenario : scenarios) {
            String id = scenario.optString("id", "");
            if (!comparisonIds.contains(id)) continue;
            try {
                CalculatorConfig other = configFromJson(scenario);
                if (!other.currencyCode.equals(config.currencyCode)) continue;
                long otherDays = Math.max(0, ChronoUnit.DAYS.between(other.start, other.end));
                List<Double> curve = new ArrayList<>();
                for (int i = 0; i < pointCount; i++) {
                    long elapsedOnActiveChart = Math.round((double) days * i / (pointCount - 1));
                    long elapsedForScenario = Math.min(elapsedOnActiveChart, otherDays);
                    curve.add(chartNumber(calculate(other, elapsedForScenario).total));
                }
                values.add(new InterestChartView.Series(scenario.optString("name", getString(R.string.calculator_saved_scenario)), curve, false));
            } catch (RuntimeException ignored) { }
        }
        chartView.setSeries(values);
    }

    private double chartNumber(BigDecimal value) {
        double number = value.doubleValue();
        if (Double.isNaN(number) || number < 0) return 0;
        if (Double.isInfinite(number) || number > 1.0e15) return 1.0e15;
        return number;
    }

    private void updateTable() {
        if (scheduleRecyclerView == null || scheduleAdapter == null || principalInput == null
                || tableTitle == null || tableModeSpinner == null) return;

        boolean monthly = tableModeSpinner.getSelectedItemPosition() == 0;
        tableTitle.setText(monthly
                ? R.string.calculator_table_monthly_caption
                : R.string.calculator_table_yearly_caption);

        CalculatorConfig config;
        try {
            config = readConfig();
        } catch (RuntimeException ex) {
            scheduleAdapter.setRows(Collections.emptyList());
            scheduleLimitView.setVisibility(View.GONE);
            return;
        }

        Currency currency = currencyFor(config.currencyCode);
        List<InterestScheduleAdapter.Row> rows = new ArrayList<>();
        int safety = 0;
        LocalDate date = config.start;
        BigDecimal previousBalance = config.principal;
        long previousPeriods = 0;
        while (safety < 1200) {
            safety++;
            LocalDate candidate = monthly ? config.start.plusMonths(safety) : config.start.plusYears(safety);
            if (!candidate.isBefore(config.end)) candidate = config.end;
            date = candidate;

            long elapsed = Math.max(0, ChronoUnit.DAYS.between(config.start, date));
            AmountResult result = calculate(config, elapsed);
            long periods = completedPeriods(config.start, date, config.frequency);
            long addedPeriods = Math.max(0, periods - previousPeriods);
            BigDecimal added = config.contribution.multiply(BigDecimal.valueOf(addedPeriods), MC);
            BigDecimal interest = result.total.subtract(previousBalance, MC).subtract(added, MC);
            rows.add(new InterestScheduleAdapter.Row(
                    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)),
                    formatMoney(added, currency),
                    formatMoney(interest, currency),
                    formatMoney(result.total, currency)));
            previousBalance = result.total;
            previousPeriods = periods;
            if (date.equals(config.end)) break;
        }
        boolean truncated = !date.equals(config.end) && rows.size() >= 1200;
        scheduleLimitView.setVisibility(truncated ? View.VISIBLE : View.GONE);
        scheduleAdapter.setRows(rows);
    }

    private void copyInterestResult() {
        CalculatorConfig config;
        try { config = readConfig(); }
        catch (RuntimeException ex) {
            Toast.makeText(this, R.string.calculator_invalid_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        AmountResult result = calculate(config, Math.max(0, ChronoUnit.DAYS.between(startDate, endDate)));
        Currency currency = currencyFor(selectedCurrencyCode);
        String summary = getString(R.string.calculator_copy_summary,
                getString(compoundMode ? R.string.calculator_compound_interest : R.string.calculator_simple_interest),
                selectedCurrencyCode, formatMoney(config.principal, currency), config.rate.toPlainString(),
                startDate.toString(), endDate.toString(), formatMoney(result.invested, currency),
                formatMoney(result.total.subtract(result.invested), currency), formatMoney(result.total, currency));
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.calculator_copy_label), summary));
        Toast.makeText(this, R.string.calculator_result_copied, Toast.LENGTH_SHORT).show();
    }

    private void promptSaveScenario() {
        try {
            readConfig();
        } catch (RuntimeException ex) {
            Toast.makeText(this, R.string.calculator_invalid_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        View dialogContent = getLayoutInflater().inflate(R.layout.dialog_calculator_save_scenario, null, false);
        EditText nameInput = dialogContent.findViewById(R.id.calculatorScenarioNameInput);
        new AlertDialog.Builder(this)
                .setTitle(R.string.calculator_save_scenario_title)
                .setView(dialogContent)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.calculator_save, (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) name = getString(R.string.calculator_saved_scenario);
                    saveCurrentScenario(name);
                })
                .show();
    }

    private void saveCurrentScenario(String name) {
        try {
            CalculatorConfig config = readConfig();
            JSONObject item = new JSONObject();
            item.put("id", System.currentTimeMillis() + "-" + scenarios.size());
            item.put("name", name);
            item.put("principal", config.principal.toPlainString());
            item.put("rate", config.rate.toPlainString());
            item.put("contribution", config.contribution.toPlainString());
            item.put("frequency", config.frequency);
            item.put("compound", config.compound);
            item.put("currency", config.currencyCode);
            item.put("start", config.start.toString());
            item.put("end", config.end.toString());
            item.put("tableMode", tableModeSpinner.getSelectedItemPosition());
            scenarios.add(0, item);
            persistScenarios();
            renderScenarios();
            updateChart(config, Math.max(0, ChronoUnit.DAYS.between(config.start, config.end)));
            Toast.makeText(this, R.string.calculator_scenario_saved, Toast.LENGTH_SHORT).show();
        } catch (JSONException | RuntimeException ex) {
            Toast.makeText(this, R.string.calculator_scenario_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void renderScenarios() {
        if (scenariosRecyclerView == null || scenarioAdapter == null) return;
        boolean hasScenarios = !scenarios.isEmpty();
        TextView emptyView = findViewById(R.id.calculatorNoScenarios);
        emptyView.setVisibility(hasScenarios ? View.GONE : View.VISIBLE);
        scenariosRecyclerView.setVisibility(hasScenarios ? View.VISIBLE : View.GONE);
        scenarioAdapter.setItems(new ArrayList<>(scenarios));
    }

    private String getScenarioSummary(JSONObject scenario) {
        try {
            CalculatorConfig config = configFromJson(scenario);
            long days = Math.max(0, ChronoUnit.DAYS.between(config.start, config.end));
            AmountResult result = calculate(config, days);
            return getString(R.string.calculator_scenario_summary, config.currencyCode,
                    formatMoney(result.total, currencyFor(config.currencyCode)), config.rate.toPlainString(), String.valueOf(days));
        } catch (RuntimeException ex) {
            return getString(R.string.calculator_scenario_invalid);
        }
    }

    private void applyScenario(JSONObject scenario) {
        try {
            CalculatorConfig config = configFromJson(scenario);
            principalInput.setText(formatInputNumber(config.principal));
            rateInput.setText(formatInputNumber(config.rate));
            contributionInput.setText(formatInputNumber(config.contribution));
            startDate = config.start;
            endDate = config.end;
            compoundMode = config.compound;
            selectedCurrencyCode = config.currencyCode;
            setCurrencySelectionSilently(selectedCurrencyCode);
            frequencySpinner.setSelection(config.frequency == 1 ? 0 : config.frequency == 4 ? 1 : config.frequency == 365 ? 3 : 2);
            tableModeSpinner.setSelection(scenario.optInt("tableMode", 0));
            updateDateLabels();
            styleMethodButtons();
            updateInterestView();
        } catch (RuntimeException ex) {
            Toast.makeText(this, R.string.calculator_scenario_invalid, Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteScenario(String id) {
        for (int i = scenarios.size() - 1; i >= 0; i--) {
            if (id.equals(scenarios.get(i).optString("id", ""))) scenarios.remove(i);
        }
        comparisonIds.remove(id);
        persistScenarios();
        renderScenarios();
        try {
            CalculatorConfig active = readConfig();
            updateChart(active, Math.max(0, ChronoUnit.DAYS.between(startDate, endDate)));
        } catch (RuntimeException ignored) { }
    }

    private void loadScenarios() {
        scenarios.clear();
        try {
            JSONArray array = new JSONArray(preferences.getString(PREF_SCENARIOS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null && item.has("id")) scenarios.add(item);
            }
        } catch (JSONException ignored) {
            preferences.edit().remove(PREF_SCENARIOS).apply();
        }
    }

    private void persistScenarios() {
        JSONArray array = new JSONArray();
        for (JSONObject scenario : scenarios) array.put(scenario);
        preferences.edit().putString(PREF_SCENARIOS, array.toString()).apply();
    }

    private CalculatorConfig configFromJson(JSONObject item) {
        BigDecimal principal = new BigDecimal(item.optString("principal", "0"), MC);
        BigDecimal rate = new BigDecimal(item.optString("rate", "0"), MC);
        BigDecimal contribution = new BigDecimal(item.optString("contribution", "0"), MC);
        int frequency = item.optInt("frequency", 12);
        if (frequency != 1 && frequency != 4 && frequency != 12 && frequency != 365) frequency = 12;
        String currency = item.optString("currency", "USD");
        if (!isSupportedCurrency(currency)) currency = "USD";
        LocalDate start = LocalDate.parse(item.optString("start", LocalDate.now().toString()));
        LocalDate end = LocalDate.parse(item.optString("end", start.plusYears(1).toString()));
        return new CalculatorConfig(principal, rate, contribution, frequency,
                item.optBoolean("compound", true), currency, start, end);
    }

    private void buildCurrencyCodes() {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String code : PREFERRED_CURRENCY_CODES) {
            try {
                Currency currency = Currency.getInstance(code);
                if (currency.getDefaultFractionDigits() >= 0) ordered.add(code);
            } catch (RuntimeException ignored) { }
        }
        List<String> remaining = new ArrayList<>();
        for (Currency currency : Currency.getAvailableCurrencies()) {
            String code = currency.getCurrencyCode();
            if (currency.getDefaultFractionDigits() >= 0 && !ordered.contains(code)) remaining.add(code);
        }
        Collections.sort(remaining);
        ordered.addAll(remaining);
        currencyCodes.clear();
        currencyCodes.addAll(ordered);
    }

    private String[] currencyLabels() {
        String[] labels = new String[currencyCodes.size()];
        for (int i = 0; i < currencyCodes.size(); i++) {
            String code = currencyCodes.get(i);
            Currency currency = currencyFor(code);
            String name;
            try { name = currency.getDisplayName(locale); } catch (RuntimeException ex) { name = code; }
            labels[i] = getString(R.string.calculator_currency_label, code, name, currency.getSymbol(locale));
        }
        return labels;
    }

    private int currencyIndex(String code) {
        if (code == null) return -1;
        for (int i = 0; i < currencyCodes.size(); i++) {
            if (currencyCodes.get(i).equals(code)) return i;
        }
        return -1;
    }

    private boolean isSupportedCurrency(String code) {
        if (code == null) return false;
        return currencyCodes.contains(code);
    }

    private Currency currencyFor(String code) {
        try { return Currency.getInstance(code); }
        catch (RuntimeException ex) { return Currency.getInstance("USD"); }
    }

    private String defaultCurrencyCode(Locale currentLocale) {
        String country = currentLocale.getCountry() == null ? "" : currentLocale.getCountry().toUpperCase(Locale.ROOT);
        String language = currentLocale.getLanguage().toLowerCase(Locale.ROOT);
        if ("vi".equals(language)) return "VND";
        if ("en".equals(language)) return "USD";
        if (!country.isEmpty()) {
            try {
                Currency byCountry = Currency.getInstance(new Locale("", country));
                if (isSupportedCurrency(byCountry.getCurrencyCode())) return byCountry.getCurrencyCode();
            } catch (RuntimeException ignored) { }
        }
        switch (language) {
            case "vi": return "VND";
            case "en": return "USD";
            case "ja": return "JPY";
            case "zh": return "CNY";
            case "ko": return "KRW";
            case "fr": case "de": case "it": case "nl": case "fi": case "el":
            case "hr": case "sk": case "sl": case "et": case "lv": case "lt":
            case "mt": case "ca": case "ga": return "EUR";
            case "bg": return "EUR";
            case "th": return "THB";
            case "id": return "IDR";
            case "hi": case "ta": case "te": case "mr": case "gu": case "pa": return "INR";
            case "ms": return "MYR";
            case "bn": return "BDT";
            case "ne": return "NPR";
            case "si": return "LKR";
            case "fa": return "IRR";
            case "ur": return "PKR";
            case "af": return "ZAR";
            case "is": return "ISK";
            case "be": return "BYN";
            case "kk": return "KZT";
            case "uz": return "UZS";
            case "ka": return "GEL";
            case "hy": return "AMD";
            case "az": return "AZN";
            case "km": return "KHR";
            case "lo": return "LAK";
            case "my": return "MMK";
            case "ha": case "yo": case "ig": return "NGN";
            case "sw": return "KES";
            case "am": return "ETB";
            case "sq": return "ALL";
            case "mk": return "MKD";
            case "sr": return "RSD";
            case "bs": return "BAM";
            case "fil": return "PHP";
            case "pt": case "es": return "EUR";
            case "ru": return "RUB";
            case "tr": return "TRY";
            case "sv": return "SEK";
            case "no": case "nb": case "nn": return "NOK";
            case "da": return "DKK";
            case "pl": return "PLN";
            case "cs": return "CZK";
            case "hu": return "HUF";
            case "ro": return "RON";
            case "uk": return "UAH";
            case "he": return "ILS";
            case "ar": return "SAR";
            default: return "USD";
        }
    }

    private String formatInputNumber(BigDecimal value) {
        char decimalSeparator = DecimalFormatSymbols.getInstance(locale).getDecimalSeparator();
        return value.stripTrailingZeros().toPlainString().replace('.', decimalSeparator);
    }

    private String formatMoney(BigDecimal amount, Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) digits = 2;
        digits = Math.min(digits, 4);
        BigDecimal rounded = amount.setScale(digits, RoundingMode.HALF_UP);
        NumberFormat format = NumberFormat.getCurrencyInstance(locale);
        format.setCurrency(currency);
        format.setRoundingMode(RoundingMode.HALF_UP);
        format.setMinimumFractionDigits(digits);
        format.setMaximumFractionDigits(digits);
        return format.format(rounded);
    }

    private final class ScenarioAdapter extends RecyclerView.Adapter<ScenarioAdapter.ScenarioViewHolder> {
        private final List<JSONObject> items = new ArrayList<>();

        void setItems(List<JSONObject> newItems) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            notifyDataSetChanged();
        }

        @Override
        public ScenarioViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View itemView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_calculator_scenario, parent, false);
            return new ScenarioViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(ScenarioViewHolder holder, int position) {
            JSONObject scenario = items.get(position);
            String id = scenario.optString("id", "");
            String name = scenario.optString("name", getString(R.string.calculator_saved_scenario));
            holder.compare.setOnCheckedChangeListener(null);
            holder.compare.setText(getString(R.string.calculator_compare_scenario, name));
            holder.compare.setChecked(comparisonIds.contains(id));
            holder.summary.setText(getScenarioSummary(scenario));
            holder.compare.setOnCheckedChangeListener((buttonView, checked) -> {
                if (checked) comparisonIds.add(id); else comparisonIds.remove(id);
                try {
                    CalculatorConfig active = readConfig();
                    updateChart(active, Math.max(0, ChronoUnit.DAYS.between(active.start, active.end)));
                } catch (RuntimeException ignored) { }
            });
            holder.load.setOnClickListener(view -> applyScenario(scenario));
            holder.delete.setOnClickListener(view -> new AlertDialog.Builder(CalculatorActivity.this)
                    .setTitle(R.string.calculator_delete_scenario_title)
                    .setMessage(getString(R.string.calculator_delete_scenario_message, name))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.calculator_delete, (dialog, which) -> deleteScenario(id))
                    .show());
        }

        @Override
        public int getItemCount() { return items.size(); }

        final class ScenarioViewHolder extends RecyclerView.ViewHolder {
            final CheckBox compare;
            final TextView summary;
            final Button load;
            final Button delete;

            ScenarioViewHolder(View itemView) {
                super(itemView);
                compare = itemView.findViewById(R.id.calculatorScenarioCompareCheckbox);
                summary = itemView.findViewById(R.id.calculatorScenarioSummary);
                load = itemView.findViewById(R.id.calculatorScenarioLoadButton);
                delete = itemView.findViewById(R.id.calculatorScenarioDeleteButton);
            }
        }
    }

    private static final class CalculatorConfig {
        final BigDecimal principal, rate, contribution;
        final int frequency;
        final boolean compound;
        final String currencyCode;
        final LocalDate start, end;
        CalculatorConfig(BigDecimal principal, BigDecimal rate, BigDecimal contribution, int frequency,
                         boolean compound, String currencyCode, LocalDate start, LocalDate end) {
            this.principal = principal;
            this.rate = rate;
            this.contribution = contribution;
            this.frequency = frequency;
            this.compound = compound;
            this.currencyCode = currencyCode;
            this.start = start;
            this.end = end;
        }
    }

    private static final class AmountResult {
        final BigDecimal total, invested;
        AmountResult(BigDecimal total, BigDecimal invested) { this.total = total; this.invested = invested; }
    }

    private static final class ExpressionParser {
        private final String input;
        private int position;
        ExpressionParser(String value) {
            input = value == null ? "" : value.replace('×', '*').replace('÷', '/').replace('−', '-').replace(',', '.');
        }
        BigDecimal parse() {
            BigDecimal result = expression();
            skipSpaces();
            if (position != input.length()) throw new IllegalArgumentException("unexpected input");
            return result;
        }
        private BigDecimal expression() {
            BigDecimal value = term();
            while (true) {
                skipSpaces();
                if (consume('+')) value = value.add(term(), MC);
                else if (consume('-')) value = value.subtract(term(), MC);
                else return value;
            }
        }
        private BigDecimal term() {
            BigDecimal value = unary();
            while (true) {
                skipSpaces();
                if (consume('*')) value = value.multiply(unary(), MC);
                else if (consume('/')) value = value.divide(unary(), MC);
                else return value;
            }
        }
        private BigDecimal unary() {
            skipSpaces();
            if (consume('+')) return unary();
            if (consume('-')) return unary().negate(MC);
            BigDecimal value = primary();
            skipSpaces();
            while (consume('%')) value = value.divide(BigDecimal.valueOf(100), MC);
            return value;
        }
        private BigDecimal primary() {
            skipSpaces();
            if (consume('(')) {
                BigDecimal value = expression();
                skipSpaces();
                if (!consume(')')) throw new IllegalArgumentException("missing parenthesis");
                return value;
            }
            int start = position;
            boolean decimal = false;
            while (position < input.length()) {
                char c = input.charAt(position);
                if (c >= '0' && c <= '9') position++;
                else if (c == '.' && !decimal) { decimal = true; position++; }
                else break;
            }
            if (position == start) throw new IllegalArgumentException("number expected");
            return new BigDecimal(input.substring(start, position), MC);
        }
        private boolean consume(char c) {
            if (position < input.length() && input.charAt(position) == c) { position++; return true; }
            return false;
        }
        private void skipSpaces() { while (position < input.length() && Character.isWhitespace(input.charAt(position))) position++; }
    }
}
