package wallet.tools;

import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.appcompat.app.AlertDialog;

import wallet.main.R;
import wallet.ui.BaseActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Offline standard calculator and simple/compound interest projection. */
public class CalculatorActivity extends BaseActivity {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String PREFS = "calculator_preferences";
    private static final String PREF_CURRENCY = "interest_currency";
    private static final String PREF_CURRENCY_MANUAL = "interest_currency_manual";
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
    private LinearLayout standardPanel;
    private LinearLayout interestPanel;
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
    private LinearLayout tableContent;
    private LinearLayout scenariosContent;
    private InterestChartView chartView;
    private LocalDate startDate;
    private LocalDate endDate;
    private boolean compoundMode = true;
    private int lastCurrencySelection = -1;
    private String selectedCurrencyCode = "USD";
    private final Set<String> comparisonIds = new HashSet<>();
    private final List<JSONObject> scenarios = new ArrayList<>();
    private boolean rebuildingScenarioList;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        locale = getResources().getConfiguration().getLocales().get(0);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildCurrencyCodes();
        startDate = LocalDate.now();
        endDate = startDate.plusYears(5);
        selectedCurrencyCode = defaultCurrencyCode(locale);
        if (preferences.getBoolean(PREF_CURRENCY_MANUAL, false)) {
            String saved = preferences.getString(PREF_CURRENCY, selectedCurrencyCode);
            if (isSupportedCurrency(saved)) selectedCurrencyCode = saved;
        }
        loadScenarios();
        buildInterface();
        updateDateLabels();
        updateInterestView();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(resolveColor(android.R.attr.colorBackground));

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.calculator_title);
        toolbar.setBackgroundColor(resolveColor(androidx.appcompat.R.attr.colorPrimary));
        toolbar.setTitleTextColor(resolveColor(android.R.attr.textColorPrimaryInverse));
        toolbar.setNavigationIcon(R.drawable.ic_browser_back_24dp);
        if (toolbar.getNavigationIcon() != null) {
            DrawableCompat.setTint(toolbar.getNavigationIcon().mutate(),
                    resolveColor(android.R.attr.textColorPrimaryInverse));
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(12), dp(8), dp(12), dp(4));
        standardTab = new Button(this);
        standardTab.setText(R.string.calculator_standard);
        standardTab.setAllCaps(false);
        interestTab = new Button(this);
        interestTab.setText(R.string.calculator_interest);
        interestTab.setAllCaps(false);
        tabs.addView(standardTab, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams interestTabParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        interestTabParams.setMarginStart(dp(8));
        tabs.addView(interestTab, interestTabParams);
        root.addView(tabs);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(18));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        standardPanel = buildStandardPanel();
        interestPanel = buildInterestPanel();
        content.addView(standardPanel);
        content.addView(interestPanel);
        standardTab.setOnClickListener(v -> showPanel(false));
        interestTab.setOnClickListener(v -> showPanel(true));
        setContentView(root);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setTitle(R.string.calculator_title);
        }
        showPanel(false);
    }

    private void showPanel(boolean interest) {
        standardPanel.setVisibility(interest ? View.GONE : View.VISIBLE);
        interestPanel.setVisibility(interest ? View.VISIBLE : View.GONE);
        styleTab(standardTab, !interest);
        styleTab(interestTab, interest);
    }

    private void styleTab(Button button, boolean selected) {
        int tint = resolveColor(selected ? androidx.appcompat.R.attr.colorAccent : android.R.attr.colorBackground);
        button.setBackgroundTintList(ColorStateList.valueOf(tint));
        button.setTextColor(resolveColor(selected ? android.R.attr.textColorPrimaryInverse : android.R.attr.textColorPrimary));
        button.setElevation(selected ? dp(2) : 0);
    }

    private LinearLayout buildStandardPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout displayCard = card();
        expressionView = text("", 14, android.R.attr.textColorSecondary, false);
        expressionView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        expressionView.setMinHeight(dp(32));
        calculatorResultView = text("0", 34, android.R.attr.textColorPrimary, true);
        calculatorResultView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        calculatorResultView.setMinHeight(dp(58));
        displayCard.addView(expressionView);
        displayCard.addView(calculatorResultView);
        panel.addView(displayCard, marginParams(-1, -2, 0, 0, 0, 12));

        String[][] keys = {
                {"AC", "(", ")", "÷"}, {"7", "8", "9", "×"},
                {"4", "5", "6", "−"}, {"1", "2", "3", "+"},
                {"±", "0", ".", "%"}, {"⌫", "", "", "="}
        };
        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        for (String[] keyRow : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : keyRow) {
                Button button = new Button(this);
                button.setText(key);
                button.setAllCaps(false);
                button.setTextSize(21);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(58), 1);
                params.setMargins(dp(3), dp(3), dp(3), dp(3));
                if (key.isEmpty()) button.setVisibility(View.INVISIBLE);
                else button.setOnClickListener(v -> handleCalculatorKey(key));
                row.addView(button, params);
            }
            keypad.addView(row);
        }
        panel.addView(keypad);
        TextView hint = text(R.string.calculator_standard_hint, 13, android.R.attr.textColorSecondary, false);
        hint.setPadding(dp(4), dp(8), dp(4), dp(2));
        panel.addView(hint);
        return panel;
    }

    private void handleCalculatorKey(String key) {
        if ("AC".equals(key)) {
            calculatorExpression = "";
            justEvaluated = false;
            expressionView.setText("");
            calculatorResultView.setText("0");
            return;
        }
        if ("⌫".equals(key)) {
            if (justEvaluated) calculatorExpression = "";
            else if (!calculatorExpression.isEmpty()) calculatorExpression = calculatorExpression.substring(0, calculatorExpression.length() - 1);
            justEvaluated = false;
            calculatorResultView.setText(calculatorExpression.isEmpty() ? "0" : displayExpression(calculatorExpression));
            expressionView.setText("");
            return;
        }
        if ("=".equals(key)) {
            try {
                String before = calculatorExpression;
                BigDecimal result = new ExpressionParser(calculatorExpression).parse();
                calculatorExpression = result.stripTrailingZeros().toPlainString();
                expressionView.setText(displayExpression(before) + " =");
                calculatorResultView.setText(formatCalculatorNumber(result));
                justEvaluated = true;
            } catch (RuntimeException ex) {
                calculatorResultView.setText(R.string.calculator_invalid_expression);
                justEvaluated = false;
            }
            return;
        }
        if ("±".equals(key)) {
            toggleLastNumberSign();
            justEvaluated = false;
            calculatorResultView.setText(calculatorExpression.isEmpty() ? "0" : displayExpression(calculatorExpression));
            expressionView.setText("");
            return;
        }
        if (justEvaluated && isNumberKey(key)) {
            calculatorExpression = "";
            expressionView.setText("");
        }
        justEvaluated = false;
        if (isOperatorKey(key) && calculatorExpression.isEmpty() && !"−".equals(key)) return;
        String value = key.equals("×") ? "*" : key.equals("÷") ? "/" : key.equals("−") ? "-" : key;
        calculatorExpression += value;
        calculatorResultView.setText(displayExpression(calculatorExpression));
        expressionView.setText("");
    }

    private String displayExpression(String value) {
        return value.replace('*', '×').replace('/', '÷').replace('-', '−');
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

    private boolean isNumberKey(String key) {
        return key.length() == 1 && ((key.charAt(0) >= '0' && key.charAt(0) <= '9') || key.equals("."));
    }

    private boolean isOperatorKey(String key) {
        return key.equals("+") || key.equals("−") || key.equals("×") || key.equals("÷");
    }

    private String formatCalculatorNumber(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMaximumFractionDigits(12);
        format.setMinimumFractionDigits(0);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }

    private LinearLayout buildInterestPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        LinearLayout inputCard = card();
        addCardTitle(inputCard, R.string.calculator_inputs_title);
        addLabel(inputCard, R.string.calculator_currency);
        currencySpinner = new Spinner(this);
        ArrayAdapter<String> currencyAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, currencyLabels());
        currencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        currencySpinner.setAdapter(currencyAdapter);
        lastCurrencySelection = currencyIndex(selectedCurrencyCode);
        currencySpinner.setSelection(lastCurrencySelection);
        inputCard.addView(currencySpinner, matchWrapParams());
        currencySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < currencyCodes.size() && position != lastCurrencySelection) {
                    lastCurrencySelection = position;
                    selectedCurrencyCode = currencyCodes.get(position);
                    preferences.edit().putString(PREF_CURRENCY, selectedCurrencyCode)
                            .putBoolean(PREF_CURRENCY_MANUAL, true).apply();
                    updateInterestView();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        inputCard.addView(text(R.string.calculator_currency_hint, 12, android.R.attr.textColorSecondary, false),
                marginParams(-1, -2, 0, 2, 0, 6));

        addLabel(inputCard, R.string.calculator_principal);
        principalInput = input("10000", android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL,
                R.string.calculator_principal_hint);
        inputCard.addView(principalInput, marginParams(-1, -2, 0, 2, 0, 6));
        addLabel(inputCard, R.string.calculator_annual_rate);
        rateInput = input("8", android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL,
                R.string.calculator_rate_hint);
        inputCard.addView(rateInput, marginParams(-1, -2, 0, 2, 0, 6));
        addLabel(inputCard, R.string.calculator_periodic_contribution);
        contributionInput = input("0", android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL,
                R.string.calculator_contribution_hint);
        inputCard.addView(contributionInput, marginParams(-1, -2, 0, 2, 0, 6));

        addLabel(inputCard, R.string.calculator_interest_method);
        LinearLayout methodRow = new LinearLayout(this);
        methodRow.setOrientation(LinearLayout.HORIZONTAL);
        simpleModeButton = new Button(this);
        simpleModeButton.setText(R.string.calculator_simple_interest);
        simpleModeButton.setAllCaps(false);
        compoundModeButton = new Button(this);
        compoundModeButton.setText(R.string.calculator_compound_interest);
        compoundModeButton.setAllCaps(false);
        methodRow.addView(simpleModeButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams compoundParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        compoundParams.setMarginStart(dp(8));
        methodRow.addView(compoundModeButton, compoundParams);
        inputCard.addView(methodRow, marginParams(-1, -2, 0, 2, 0, 6));
        simpleModeButton.setOnClickListener(v -> { compoundMode = false; styleMethodButtons(); updateInterestView(); });
        compoundModeButton.setOnClickListener(v -> { compoundMode = true; styleMethodButtons(); updateInterestView(); });

        addLabel(inputCard, R.string.calculator_compounding_frequency);
        frequencySpinner = new Spinner(this);
        ArrayAdapter<String> frequencyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.calculator_frequency_yearly), getString(R.string.calculator_frequency_quarterly),
                        getString(R.string.calculator_frequency_monthly), getString(R.string.calculator_frequency_daily)});
        frequencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        frequencySpinner.setAdapter(frequencyAdapter);
        frequencySpinner.setSelection(2);
        inputCard.addView(frequencySpinner, marginParams(-1, -2, 0, 2, 0, 6));
        frequencySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { updateInterestView(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        addLabel(inputCard, R.string.calculator_start_date);
        LinearLayout startRow = new LinearLayout(this);
        startRow.setGravity(Gravity.CENTER_VERTICAL);
        startDateValue = text("", 15, android.R.attr.textColorPrimary, false);
        Button startButton = new Button(this);
        startButton.setText(R.string.calculator_choose_date);
        startButton.setAllCaps(false);
        startRow.addView(startDateValue, new LinearLayout.LayoutParams(0, -2, 1));
        startRow.addView(startButton, wrapButtonParams());
        inputCard.addView(startRow, matchWrapParams());
        startButton.setOnClickListener(v -> chooseDate(true));

        addLabel(inputCard, R.string.calculator_maturity_date);
        LinearLayout endRow = new LinearLayout(this);
        endRow.setGravity(Gravity.CENTER_VERTICAL);
        endDateValue = text("", 15, android.R.attr.textColorPrimary, false);
        Button endButton = new Button(this);
        endButton.setText(R.string.calculator_choose_date);
        endButton.setAllCaps(false);
        endRow.addView(endDateValue, new LinearLayout.LayoutParams(0, -2, 1));
        endRow.addView(endButton, wrapButtonParams());
        inputCard.addView(endRow, matchWrapParams());
        endButton.setOnClickListener(v -> chooseDate(false));

        addLabel(inputCard, R.string.calculator_table_interval);
        tableModeSpinner = new Spinner(this);
        ArrayAdapter<String> tableAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{getString(R.string.calculator_table_monthly), getString(R.string.calculator_table_yearly)});
        tableAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        tableModeSpinner.setAdapter(tableAdapter);
        inputCard.addView(tableModeSpinner, matchWrapParams());
        tableModeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { updateTable(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        panel.addView(inputCard, marginParams(-1, -2, 0, 0, 0, 12));

        LinearLayout resultCard = card();
        addCardTitle(resultCard, R.string.calculator_result_title);
        finalAmountValue = text("—", 28, android.R.attr.textColorPrimary, true);
        resultCard.addView(finalAmountValue, marginParams(-1, -2, 0, 4, 0, 8));
        addResultRow(resultCard, R.string.calculator_interest_earned, v -> earnedInterestValue = v);
        addResultRow(resultCard, R.string.calculator_total_invested, v -> investedAmountValue = v);
        addResultRow(resultCard, R.string.calculator_duration, v -> durationValue = v);
        TextView disclaimer = text(R.string.calculator_estimate_disclaimer, 12, android.R.attr.textColorSecondary, false);
        disclaimer.setPadding(0, dp(8), 0, 0);
        resultCard.addView(disclaimer);
        LinearLayout actions = new LinearLayout(this);
        Button copy = new Button(this);
        copy.setText(R.string.calculator_copy_result);
        copy.setAllCaps(false);
        Button save = new Button(this);
        save.setText(R.string.calculator_save_scenario);
        save.setAllCaps(false);
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        saveParams.setMarginStart(dp(8));
        actions.addView(save, saveParams);
        resultCard.addView(actions, marginParams(-1, -2, 0, 8, 0, 0));
        copy.setOnClickListener(v -> copyInterestResult());
        save.setOnClickListener(v -> promptSaveScenario());
        panel.addView(resultCard, marginParams(-1, -2, 0, 0, 0, 12));

        LinearLayout chartCard = card();
        addCardTitle(chartCard, R.string.calculator_growth_chart);
        chartCard.addView(text(R.string.calculator_growth_legend, 12, android.R.attr.textColorSecondary, false),
                marginParams(-1, -2, 0, 0, 0, 4));
        chartView = new InterestChartView(this);
        chartCard.addView(chartView, new LinearLayout.LayoutParams(-1, dp(210)));
        panel.addView(chartCard, marginParams(-1, -2, 0, 0, 0, 12));

        LinearLayout tableCard = card();
        addCardTitle(tableCard, R.string.calculator_schedule_title);
        tableTitle = text("", 12, android.R.attr.textColorSecondary, false);
        tableCard.addView(tableTitle, marginParams(-1, -2, 0, 0, 0, 6));
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setFillViewport(false);
        tableContent = new LinearLayout(this);
        tableContent.setOrientation(LinearLayout.VERTICAL);
        horizontal.addView(tableContent, new HorizontalScrollView.LayoutParams(-2, -2));
        tableCard.addView(horizontal, matchWrapParams());
        panel.addView(tableCard, marginParams(-1, -2, 0, 0, 0, 12));

        LinearLayout savedCard = card();
        addCardTitle(savedCard, R.string.calculator_saved_scenarios);
        savedCard.addView(text(R.string.calculator_scenarios_hint, 12, android.R.attr.textColorSecondary, false),
                marginParams(-1, -2, 0, 0, 0, 8));
        scenariosContent = new LinearLayout(this);
        scenariosContent.setOrientation(LinearLayout.VERTICAL);
        savedCard.addView(scenariosContent, matchWrapParams());
        panel.addView(savedCard, marginParams(-1, -2, 0, 0, 0, 0));

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateInterestView(); }
            @Override public void afterTextChanged(Editable s) { }
        };
        principalInput.addTextChangedListener(watcher);
        rateInput.addTextChangedListener(watcher);
        contributionInput.addTextChangedListener(watcher);
        styleMethodButtons();
        renderScenarios();
        return panel;
    }

    private interface ResultTextConsumer { void accept(TextView view); }

    private void addResultRow(LinearLayout parent, int labelId, ResultTextConsumer consumer) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(labelId, 14, android.R.attr.textColorSecondary, false);
        TextView value = text("—", 16, android.R.attr.textColorPrimary, true);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(value, wrapParams());
        parent.addView(row, marginParams(-1, -2, 0, 4, 0, 4));
        consumer.accept(value);
    }

    private void styleMethodButtons() {
        if (simpleModeButton == null || compoundModeButton == null) return;
        styleMethodButton(simpleModeButton, !compoundMode);
        styleMethodButton(compoundModeButton, compoundMode);
    }

    private void styleMethodButton(Button button, boolean selected) {
        int tint = resolveColor(selected ? androidx.appcompat.R.attr.colorAccent : android.R.attr.colorBackground);
        button.setBackgroundTintList(ColorStateList.valueOf(tint));
        button.setTextColor(resolveColor(selected ? android.R.attr.textColorPrimaryInverse : android.R.attr.textColorPrimary));
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
            finalAmountValue.setText("—");
            earnedInterestValue.setText("—");
            investedAmountValue.setText("—");
            durationValue.setText("—");
            if (chartView != null) chartView.setSeries(new ArrayList<>());
            if (tableContent != null) tableContent.removeAllViews();
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
        List<ChartSeries> values = new ArrayList<>();
        List<Double> total = new ArrayList<>();
        List<Double> invested = new ArrayList<>();
        int pointCount = 41;
        for (int i = 0; i < pointCount; i++) {
            long pointDays = Math.round((double) days * i / (pointCount - 1));
            AmountResult result = calculate(config, pointDays);
            total.add(chartNumber(result.total));
            invested.add(chartNumber(result.invested));
        }
        values.add(new ChartSeries(getString(R.string.calculator_chart_total), total, false));
        values.add(new ChartSeries(getString(R.string.calculator_chart_invested), invested, true));
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
                values.add(new ChartSeries(scenario.optString("name", getString(R.string.calculator_saved_scenario)), curve, false));
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
        if (tableContent == null || principalInput == null || tableTitle == null || tableModeSpinner == null) return;
        tableContent.removeAllViews();
        CalculatorConfig config;
        try { config = readConfig(); } catch (RuntimeException ex) { return; }
        Currency currency = currencyFor(selectedCurrencyCode);
        boolean monthly = tableModeSpinner.getSelectedItemPosition() == 0;
        tableTitle.setText(monthly ? R.string.calculator_table_monthly_caption : R.string.calculator_table_yearly_caption);
        LinearLayout header = tableRow(true);
        addCell(header, getString(R.string.calculator_column_period), 132, true);
        addCell(header, getString(R.string.calculator_column_contributed), 124, true);
        addCell(header, getString(R.string.calculator_column_interest), 124, true);
        addCell(header, getString(R.string.calculator_column_balance), 145, true);
        tableContent.addView(header);

        List<LocalDate> dates = new ArrayList<>();
        int safety = 0;
        while (safety < 1200) {
            safety++;
            LocalDate candidate = monthly ? startDate.plusMonths(safety) : startDate.plusYears(safety);
            if (!candidate.isBefore(endDate)) candidate = endDate;
            dates.add(candidate);
            if (candidate.equals(endDate)) break;
        }
        if (dates.isEmpty()) dates.add(endDate);
        BigDecimal previousBalance = config.principal;
        long previousPeriods = 0;
        for (LocalDate date : dates) {
            long elapsed = Math.max(0, ChronoUnit.DAYS.between(startDate, date));
            AmountResult result = calculate(config, elapsed);
            long periods = completedPeriods(config.start, date, config.frequency);
            long addedPeriods = Math.max(0, periods - previousPeriods);
            BigDecimal added = config.contribution.multiply(BigDecimal.valueOf(addedPeriods), MC);
            BigDecimal interest = result.total.subtract(previousBalance, MC).subtract(added, MC);
            LinearLayout row = tableRow(false);
            addCell(row, date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)), 132, false);
            addCell(row, formatMoney(added, currency), 124, false);
            addCell(row, formatMoney(interest, currency), 124, false);
            addCell(row, formatMoney(result.total, currency), 145, true);
            tableContent.addView(row);
            previousBalance = result.total;
            previousPeriods = periods;
        }
        if (dates.size() >= 1200) tableContent.addView(text(R.string.calculator_table_limit, 12,
                android.R.attr.textColorSecondary, false));
    }

    private LinearLayout tableRow(boolean header) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(7), dp(4), dp(7));
        if (header) row.setBackgroundResource(R.drawable.bg_text_input_area);
        return row;
    }

    private void addCell(LinearLayout row, String value, int widthDp, boolean bold) {
        TextView cell = text(value, 12, bold ? android.R.attr.textColorPrimary : android.R.attr.textColorSecondary, bold);
        cell.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(4), dp(2), dp(8), dp(2));
        row.addView(cell, new LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
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
        try { readConfig(); }
        catch (RuntimeException ex) {
            Toast.makeText(this, R.string.calculator_invalid_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(R.string.calculator_scenario_name_hint);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(20), dp(8), dp(20), 0);
        wrapper.addView(name, matchWrapParams());
        new AlertDialog.Builder(this).setTitle(R.string.calculator_save_scenario_title).setView(wrapper)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.calculator_save, (dialog, which) -> {
                    String title = name.getText().toString().trim();
                    if (title.isEmpty()) title = getString(R.string.calculator_saved_scenario);
                    saveCurrentScenario(title);
                }).show();
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
        if (scenariosContent == null || rebuildingScenarioList) return;
        rebuildingScenarioList = true;
        scenariosContent.removeAllViews();
        if (scenarios.isEmpty()) {
            scenariosContent.addView(text(R.string.calculator_no_scenarios, 14, android.R.attr.textColorSecondary, false));
            rebuildingScenarioList = false;
            return;
        }
        for (JSONObject scenario : new ArrayList<>(scenarios)) {
            String id = scenario.optString("id", "");
            String name = scenario.optString("name", getString(R.string.calculator_saved_scenario));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));
            CheckBox compare = new CheckBox(this);
            compare.setText(getString(R.string.calculator_compare_scenario, name));
            compare.setTextColor(resolveColor(android.R.attr.textColorPrimary));
            compare.setChecked(comparisonIds.contains(id));
            row.addView(compare, matchWrapParams());
            row.addView(text(getScenarioSummary(scenario), 12, android.R.attr.textColorSecondary, false),
                    marginParams(-1, -2, 0, 0, 0, 4));
            LinearLayout buttons = new LinearLayout(this);
            Button load = new Button(this);
            load.setText(R.string.calculator_load_scenario);
            load.setAllCaps(false);
            Button delete = new Button(this);
            delete.setText(R.string.calculator_delete_scenario);
            delete.setAllCaps(false);
            buttons.addView(load, new LinearLayout.LayoutParams(0, dp(42), 1));
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(42), 1);
            deleteParams.setMarginStart(dp(8));
            buttons.addView(delete, deleteParams);
            row.addView(buttons, matchWrapParams());
            scenariosContent.addView(row, matchWrapParams());
            View separator = new View(this);
            separator.setBackgroundColor(resolveColor(androidx.appcompat.R.attr.colorControlNormal));
            scenariosContent.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
            compare.setOnCheckedChangeListener((buttonView, checked) -> {
                if (checked) comparisonIds.add(id); else comparisonIds.remove(id);
                try {
                    CalculatorConfig active = readConfig();
                    updateChart(active, Math.max(0, ChronoUnit.DAYS.between(startDate, endDate)));
                } catch (RuntimeException ignored) { }
            });
            load.setOnClickListener(v -> applyScenario(scenario));
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle(R.string.calculator_delete_scenario_title)
                    .setMessage(getString(R.string.calculator_delete_scenario_message, name))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.calculator_delete, (dialog, which) -> deleteScenario(id)).show());
        }
        rebuildingScenarioList = false;
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
            principalInput.setText(config.principal.stripTrailingZeros().toPlainString());
            rateInput.setText(config.rate.stripTrailingZeros().toPlainString());
            contributionInput.setText(config.contribution.stripTrailingZeros().toPlainString());
            startDate = config.start;
            endDate = config.end;
            compoundMode = config.compound;
            selectedCurrencyCode = config.currencyCode;
            lastCurrencySelection = currencyIndex(selectedCurrencyCode);
            preferences.edit().putString(PREF_CURRENCY, selectedCurrencyCode)
                    .putBoolean(PREF_CURRENCY_MANUAL, true).apply();
            currencySpinner.setSelection(lastCurrencySelection);
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
            labels[i] = code + " — " + name + " (" + currency.getSymbol(locale) + ")";
        }
        return labels;
    }

    private int currencyIndex(String code) {
        for (int i = 0; i < currencyCodes.size(); i++) if (currencyCodes.get(i).equals(code)) return i;
        return 0;
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

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(14), dp(16), dp(14));
        view.setBackgroundResource(R.drawable.bg_card);
        return view;
    }

    private void addCardTitle(LinearLayout parent, int label) {
        parent.addView(text(label, 17, android.R.attr.textColorPrimary, true), marginParams(-1, -2, 0, 0, 0, 8));
    }

    private void addLabel(LinearLayout parent, int label) {
        parent.addView(text(label, 14, android.R.attr.textColorPrimary, true), marginParams(-1, -2, 0, 8, 0, 2));
    }

    private EditText input(String initial, int type, int hint) {
        EditText edit = new EditText(this);
        edit.setText(initial);
        edit.setHint(hint);
        edit.setInputType(type);
        edit.setSingleLine(true);
        edit.setTextColor(resolveColor(android.R.attr.textColorPrimary));
        edit.setHintTextColor(resolveColor(android.R.attr.textColorHint));
        edit.setBackgroundResource(R.drawable.bg_text_input_area);
        edit.setPadding(dp(12), dp(9), dp(12), dp(9));
        return edit;
    }

    private TextView text(int stringId, int size, int colorAttr, boolean bold) {
        TextView view = new TextView(this);
        view.setText(stringId);
        styleText(view, size, colorAttr, bold);
        return view;
    }

    private TextView text(String value, int size, int colorAttr, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        styleText(view, size, colorAttr, bold);
        return view;
    }

    private void styleText(TextView view, int size, int colorAttr, boolean bold) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(resolveColor(colorAttr));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
    }

    private int resolveColor(int attr) {
        android.content.res.TypedArray array = obtainStyledAttributes(new int[]{attr});
        int color = array.getColor(0, 0xff777777);
        array.recycle();
        return color;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout.LayoutParams matchWrapParams() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams wrapParams() { return new LinearLayout.LayoutParams(-2, -2); }
    private LinearLayout.LayoutParams wrapButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(44));
        params.setMarginStart(dp(4));
        return params;
    }
    private LinearLayout.LayoutParams marginParams(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
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

    private static final class ChartSeries {
        final String name;
        final List<Double> values;
        final boolean invested;
        ChartSeries(String name, List<Double> values, boolean invested) {
            this.name = name; this.values = values; this.invested = invested;
        }
    }

    private final class InterestChartView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<ChartSeries> series = new ArrayList<>();
        private final int colorPrimary = resolveColor(android.R.attr.textColorPrimary);
        private final int colorSecondary = resolveColor(android.R.attr.textColorSecondary);
        private final int colorAccent = resolveColor(androidx.appcompat.R.attr.colorAccent);
        private final int colorGrid = resolveColor(androidx.appcompat.R.attr.colorControlNormal);
        InterestChartView(Context context) { super(context); }
        void setSeries(List<ChartSeries> value) { series = value == null ? new ArrayList<>() : value; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(10), right = getWidth() - dp(10), top = dp(12), bottom = getHeight() - dp(20);
            if (right <= left || bottom <= top || series.isEmpty()) return;
            double max = 1;
            for (ChartSeries line : series) for (Double value : line.values)
                if (value != null && Double.isFinite(value)) max = Math.max(max, value);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(colorGrid);
            paint.setAlpha(90);
            for (int i = 0; i <= 4; i++) {
                float y = top + (bottom - top) * i / 4f;
                canvas.drawLine(left, y, right, y, paint);
            }
            paint.setAlpha(255);
            for (int s = 0; s < series.size(); s++) {
                ChartSeries line = series.get(s);
                if (line.values.isEmpty()) continue;
                paint.setColor(line.invested ? colorSecondary : (s == 0 ? colorAccent : (s % 2 == 0 ? colorPrimary : colorSecondary)));
                paint.setStrokeWidth(line.invested ? dp(1.5f) : dp(2.4f));
                paint.setStyle(Paint.Style.STROKE);
                paint.setPathEffect(line.invested ? new android.graphics.DashPathEffect(new float[]{dp(5), dp(4)}, 0) : null);
                Path path = new Path();
                int count = line.values.size();
                for (int i = 0; i < count; i++) {
                    double raw = line.values.get(i) == null ? 0 : line.values.get(i);
                    double bounded = Math.max(0, Math.min(max, raw));
                    float x = left + (right - left) * (count <= 1 ? 0 : i / (float) (count - 1));
                    float y = bottom - (float) (bounded / max) * (bottom - top);
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                canvas.drawPath(path, paint);
            }
            paint.setPathEffect(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colorSecondary);
            paint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText(getString(R.string.calculator_chart_start), left, getHeight() - dp(3), paint);
            String endLabel = getString(R.string.calculator_chart_end);
            canvas.drawText(endLabel, right - paint.measureText(endLabel), getHeight() - dp(3), paint);
        }
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
