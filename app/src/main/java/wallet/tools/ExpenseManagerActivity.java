package wallet.tools;

import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import wallet.main.R;
import wallet.ui.BaseActivity;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

public class ExpenseManagerActivity extends BaseActivity {
    private static final String PREFS = "expense_manager_preferences";
    private static final String PREF_CURRENCY = "currency";
    private static final String PREF_CURRENCY_LOCALE = "currency_locale";
    private static final String[] CURRENCY_CODES = {"USD", "VND", "EUR", "GBP", "JPY", "CNY", "KRW", "THB", "IDR", "INR", "CAD", "AUD", "CHF", "SGD", "MYR", "PHP", "BRL", "MXN", "ARS", "CLP", "COP", "RUB", "TRY", "ZAR", "SEK", "NOK", "DKK", "NZD", "HKD", "TWD", "PLN", "CZK", "HUF", "RON", "UAH", "ILS", "SAR", "AED", "EGP", "PKR", "BDT", "NPR", "LKR", "IRR", "ISK", "BYN", "KZT", "UZS", "GEL", "AMD", "AZN", "KHR", "LAK"};
    private static final String[] EXPENSE_CATEGORIES = {"food", "transport", "housing", "bills", "health", "shopping", "education", "entertainment", "other"};
    private static final String[] INCOME_CATEGORIES = {"salary", "business", "investment", "gift", "other"};

    private ExpenseDatabase database;
    private Locale locale;
    private YearMonth displayedMonth = YearMonth.now();
    private LocalDate selectedDate = LocalDate.now();
    private long editingId = -1;
    private String selectedCurrency = "USD";
    private boolean suppressTypeChange;
    private boolean suppressCurrencyChange;
    private boolean suppressBudgetWatcher;
    private boolean budgetDraftChanged;
    private int lastTypeSelection = -1;
    private int lastCurrencySelection = -1;

    private TextView monthLabel;
    private TextView netBalanceValue;
    private TextView incomeValue;
    private TextView spendingValue;
    private EditText budgetInput;
    private ProgressBar budgetProgress;
    private TextView budgetStatus;
    private Spinner currencySpinner;
    private Spinner typeSpinner;
    private Spinner categorySpinner;
    private Spinner filterSpinner;
    private EditText amountInput;
    private EditText noteInput;
    private TextView dateValue;
    private TextView formTitle;
    private Button saveTransactionButton;
    private Button cancelEditButton;
    private RecyclerView transactionsRecycler;
    private RecyclerView categoryRecycler;
    private TextView historyEmpty;
    private TextView breakdownEmpty;

    private final List<ExpenseDatabase.TransactionRecord> transactionItems = new ArrayList<>();
    private final List<ExpenseDatabase.CategoryTotal> categoryItems = new ArrayList<>();
    private TransactionAdapter transactionAdapter;
    private CategoryAdapter categoryAdapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_expense_manager);
        LocaleListCompat appLocales = AppCompatDelegate.getApplicationLocales();
        locale = appLocales != null && !appLocales.isEmpty() && appLocales.get(0) != null
                ? appLocales.get(0) : getResources().getConfiguration().getLocales().get(0);
        database = new ExpenseDatabase(this);
        android.content.SharedPreferences expensePreferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedCurrency = expensePreferences.getString(PREF_CURRENCY, defaultCurrency(locale));
        String savedCurrencyLocale = expensePreferences.getString(PREF_CURRENCY_LOCALE, "");
        boolean sameLocale = savedCurrencyLocale.equals(locale.toLanguageTag());
        selectedCurrency = sameLocale && Arrays.asList(CURRENCY_CODES).contains(savedCurrency)
                ? savedCurrency : defaultCurrency(locale);
        bindViews();
        setupBudgetWatcher();
        setupToolbar();
        setupSpinners();
        setupLists();
        selectedDate = defaultDateForDisplayedMonth();
        updateDateLabel();
        if (state != null) restoreState(state);
        refreshAll();
        if (state == null || !state.containsKey("budget")) loadBudgetInput();
    }

    private void bindViews() {
        monthLabel = findViewById(R.id.expenseMonthLabel);
        netBalanceValue = findViewById(R.id.expenseNetBalanceValue);
        incomeValue = findViewById(R.id.expenseIncomeValue);
        spendingValue = findViewById(R.id.expenseSpendingValue);
        budgetInput = findViewById(R.id.expenseBudgetInput);
        budgetProgress = findViewById(R.id.expenseBudgetProgress);
        budgetStatus = findViewById(R.id.expenseBudgetStatus);
        currencySpinner = findViewById(R.id.expenseCurrencySpinner);
        typeSpinner = findViewById(R.id.expenseTypeSpinner);
        categorySpinner = findViewById(R.id.expenseCategorySpinner);
        filterSpinner = findViewById(R.id.expenseFilterSpinner);
        amountInput = findViewById(R.id.expenseAmountInput);
        noteInput = findViewById(R.id.expenseNoteInput);
        dateValue = findViewById(R.id.expenseDateValue);
        formTitle = findViewById(R.id.expenseFormTitle);
        saveTransactionButton = findViewById(R.id.expenseSaveTransactionButton);
        cancelEditButton = findViewById(R.id.expenseCancelEditButton);
        transactionsRecycler = findViewById(R.id.expenseTransactionsRecycler);
        categoryRecycler = findViewById(R.id.expenseBreakdownRecycler);
        historyEmpty = findViewById(R.id.expenseHistoryEmpty);
        breakdownEmpty = findViewById(R.id.expenseBreakdownEmpty);
    }

    private void setupBudgetWatcher() {
        budgetInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (!suppressBudgetWatcher) budgetDraftChanged = true;
            }
            @Override public void afterTextChanged(Editable value) { }
        });
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbarExpenseManager);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.expense_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    private void setupSpinners() {
        List<String> currencyLabels = new ArrayList<>();
        for (String code : CURRENCY_CODES) {
            try {
                Currency c = Currency.getInstance(code);
                currencyLabels.add(getString(R.string.calculator_currency_label, code, c.getDisplayName(locale), c.getSymbol(locale)));
            } catch (RuntimeException ignored) { currencyLabels.add(code); }
        }
        ArrayAdapter<String> currencyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, currencyLabels);
        currencyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        currencySpinner.setAdapter(currencyAdapter);
        int currentCurrencyIndex = Math.max(0, Arrays.asList(CURRENCY_CODES).indexOf(selectedCurrency));
        lastCurrencySelection = currentCurrencyIndex;
        currencySpinner.setSelection(currentCurrencyIndex, false);
        currencySpinner.setOnItemSelectedListener(new SimpleSelectionListener(position -> {
            if (!suppressCurrencyChange && position >= 0 && position < CURRENCY_CODES.length
                    && position != lastCurrencySelection) {
                lastCurrencySelection = position;
                selectedCurrency = CURRENCY_CODES[position];
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(PREF_CURRENCY, selectedCurrency)
                        .putString(PREF_CURRENCY_LOCALE, locale.toLanguageTag())
                        .apply();
                refreshAll();
                loadBudgetInput();
            }
        }));

        ArrayAdapter<String> typeAdapter = ArrayAdapter.createFromResource(this, R.array.expense_type_options, android.R.layout.simple_spinner_item);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(typeAdapter);
        typeSpinner.setSelection(0, false);
        typeSpinner.setOnItemSelectedListener(new SimpleSelectionListener(position -> {
            if (!suppressTypeChange && position != lastTypeSelection) {
                lastTypeSelection = position;
                updateCategorySpinner();
            }
        }));

        ArrayAdapter<String> filterAdapter = ArrayAdapter.createFromResource(this, R.array.expense_filter_options, android.R.layout.simple_spinner_item);
        filterAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filterSpinner.setAdapter(filterAdapter);
        filterSpinner.setSelection(0, false);
        filterSpinner.setOnItemSelectedListener(new SimpleSelectionListener(position -> refreshTransactions()));
        lastTypeSelection = typeSpinner.getSelectedItemPosition();
        updateCategorySpinner();
    }

    private void updateCategorySpinner() {
        boolean income = typeSpinner.getSelectedItemPosition() == 1;
        String[] ids = income ? INCOME_CATEGORIES : EXPENSE_CATEGORIES;
        int[] labels = income
                ? new int[]{R.string.expense_category_salary, R.string.expense_category_business, R.string.expense_category_investment, R.string.expense_category_gift, R.string.expense_category_other}
                : new int[]{R.string.expense_category_food, R.string.expense_category_transport, R.string.expense_category_housing, R.string.expense_category_bills, R.string.expense_category_health, R.string.expense_category_shopping, R.string.expense_category_education, R.string.expense_category_entertainment, R.string.expense_category_other};
        List<String> names = new ArrayList<>();
        for (int label : labels) names.add(getString(label));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        categorySpinner.setAdapter(adapter);
    }

    private void setupLists() {
        transactionAdapter = new TransactionAdapter();
        transactionsRecycler.setLayoutManager(new LinearLayoutManager(this));
        transactionsRecycler.setAdapter(transactionAdapter);
        transactionsRecycler.setNestedScrollingEnabled(false);
        categoryAdapter = new CategoryAdapter();
        categoryRecycler.setLayoutManager(new LinearLayoutManager(this));
        categoryRecycler.setAdapter(categoryAdapter);
        categoryRecycler.setNestedScrollingEnabled(false);

        findViewById(R.id.expensePreviousMonthButton).setOnClickListener(v -> {
            displayedMonth = displayedMonth.minusMonths(1);
            if (editingId <= 0) { selectedDate = defaultDateForDisplayedMonth(); updateDateLabel(); }
            refreshAll(); loadBudgetInput();
        });
        findViewById(R.id.expenseNextMonthButton).setOnClickListener(v -> {
            displayedMonth = displayedMonth.plusMonths(1);
            if (editingId <= 0) { selectedDate = defaultDateForDisplayedMonth(); updateDateLabel(); }
            refreshAll(); loadBudgetInput();
        });
        findViewById(R.id.expenseChooseDateButton).setOnClickListener(v -> chooseDate());
        findViewById(R.id.expenseSaveTransactionButton).setOnClickListener(v -> saveTransaction());
        findViewById(R.id.expenseCancelEditButton).setOnClickListener(v -> clearForm());
        findViewById(R.id.expenseSaveBudgetButton).setOnClickListener(v -> saveBudget());
        findViewById(R.id.expenseCopySummaryButton).setOnClickListener(v -> copySummary());
    }

    private LocalDate defaultDateForDisplayedMonth() {
        LocalDate today = LocalDate.now();
        return YearMonth.from(today).equals(displayedMonth) ? today : displayedMonth.atDay(1);
    }

    private void chooseDate() {
        DatePickerDialog dialog = new DatePickerDialog(this, (picker, year, month, day) -> {
            selectedDate = LocalDate.of(year, month + 1, day);
            updateDateLabel();
        }, selectedDate.getYear(), selectedDate.getMonthValue() - 1, selectedDate.getDayOfMonth());
        dialog.setTitle(R.string.expense_date);
        dialog.show();
    }

    private void updateDateLabel() {
        dateValue.setText(selectedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)));
    }

    private void saveTransaction() {
        BigDecimal amount;
        try { amount = parseAmount(amountInput.getText().toString()); }
        catch (RuntimeException ex) { amount = BigDecimal.ZERO; }
        if (amount.signum() <= 0) {
            amountInput.setError(getString(R.string.expense_invalid_amount));
            return;
        }
        String[] ids = typeSpinner.getSelectedItemPosition() == 1 ? INCOME_CATEGORIES : EXPENSE_CATEGORIES;
        int categoryPosition = categorySpinner.getSelectedItemPosition();
        if (categoryPosition < 0 || categoryPosition >= ids.length) categoryPosition = ids.length - 1;
        ExpenseDatabase.TransactionRecord record = new ExpenseDatabase.TransactionRecord();
        record.id = editingId;
        record.type = typeSpinner.getSelectedItemPosition() == 1 ? ExpenseDatabase.TYPE_INCOME : ExpenseDatabase.TYPE_EXPENSE;
        record.amount = amount;
        record.currency = selectedCurrency;
        record.category = ids[categoryPosition];
        record.date = selectedDate.toString();
        record.note = noteInput.getText().toString().trim();
        try {
            database.saveTransaction(record);
            Toast.makeText(this, editingId > 0 ? R.string.expense_updated : R.string.expense_saved, Toast.LENGTH_SHORT).show();
            displayedMonth = YearMonth.from(selectedDate);
            clearForm();
            refreshAll();
        } catch (RuntimeException ex) {
            Toast.makeText(this, R.string.expense_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private BigDecimal parseAmount(String raw) {
        String value = raw == null ? "" : raw.trim().replace(" ", "").replace("\u00a0", "").replace("\u202f", "");
        if (value.isEmpty()) return BigDecimal.ZERO;
        java.text.DecimalFormatSymbols symbols = java.text.DecimalFormatSymbols.getInstance(locale);
        char decimal = symbols.getDecimalSeparator();
        char grouping = symbols.getGroupingSeparator();
        if (decimal != '.' && value.indexOf(decimal) >= 0) {
            int index = value.lastIndexOf(decimal);
            value = value.substring(0,index).replace(String.valueOf(grouping),"").replace(".","") + "." + value.substring(index+1).replace(String.valueOf(grouping),"").replace(".","");
        } else {
            if (grouping != decimal) value = value.replace(String.valueOf(grouping), "");
            if (decimal != '.') value = value.replace(decimal, '.');
        }
        return new BigDecimal(value);
    }

    private void clearForm() {
        editingId = -1;
        amountInput.setText("");
        noteInput.setText("");
        selectedDate = defaultDateForDisplayedMonth();
        updateDateLabel();
        typeSpinner.setSelection(0);
        lastTypeSelection = 0;
        updateCategorySpinner();
        formTitle.setText(R.string.expense_add_title);
        saveTransactionButton.setText(R.string.expense_add);
        cancelEditButton.setVisibility(View.GONE);
        amountInput.setError(null);
    }

    private void saveBudget() {
        BigDecimal amount;
        try { amount = parseAmount(budgetInput.getText().toString()); }
        catch (RuntimeException ex) { amount = BigDecimal.ZERO; }
        if (amount.signum() <= 0) {
            budgetInput.setError(getString(R.string.expense_budget_invalid));
            return;
        }
        budgetInput.setError(null);
        database.saveBudget(displayedMonth, selectedCurrency, amount);
        budgetDraftChanged = false;
        Toast.makeText(this, R.string.expense_budget_saved, Toast.LENGTH_SHORT).show();
        refreshBudget();
    }

    private void loadBudgetInput() {
        BigDecimal value = database.budget(displayedMonth, selectedCurrency);
        suppressBudgetWatcher = true;
        budgetInput.setText(value.signum() > 0 ? value.toPlainString() : "");
        suppressBudgetWatcher = false;
        budgetDraftChanged = false;
    }

    private void refreshAll() {
        monthLabel.setText(displayedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)));
        BigDecimal income = database.total(displayedMonth, selectedCurrency, ExpenseDatabase.TYPE_INCOME);
        BigDecimal expenses = database.total(displayedMonth, selectedCurrency, ExpenseDatabase.TYPE_EXPENSE);
        netBalanceValue.setText(formatMoney(income.subtract(expenses), selectedCurrency));
        incomeValue.setText(formatMoney(income, selectedCurrency));
        spendingValue.setText(formatMoney(expenses, selectedCurrency));
        refreshBudget();
        refreshCategories();
        refreshTransactions();
    }

    private void refreshBudget() {
        BigDecimal budget = database.budget(displayedMonth, selectedCurrency);
        BigDecimal expenses = database.total(displayedMonth, selectedCurrency, ExpenseDatabase.TYPE_EXPENSE);
        if (budget.signum() <= 0) {
            budgetProgress.setProgress(0);
            budgetStatus.setText(R.string.expense_budget_not_set);
            return;
        }
        int percent = expenses.compareTo(budget) >= 0 ? 100
                : expenses.multiply(BigDecimal.valueOf(100))
                    .divide(budget, 0, java.math.RoundingMode.HALF_UP).intValue();
        budgetProgress.setProgress(Math.min(100, Math.max(0, percent)));
        if (expenses.compareTo(budget) > 0) {
            budgetStatus.setText(getString(R.string.expense_budget_over, formatMoney(expenses.subtract(budget), selectedCurrency)));
        } else {
            budgetStatus.setText(getString(R.string.expense_budget_usage, formatMoney(expenses, selectedCurrency), formatMoney(budget, selectedCurrency)) + " · " + getString(R.string.expense_budget_remaining, formatMoney(budget.subtract(expenses), selectedCurrency)));
        }
    }

    private void refreshCategories() {
        if (categoryAdapter == null || breakdownEmpty == null || categoryRecycler == null) return;
        categoryItems.clear();
        categoryItems.addAll(database.expenseTotalsByCategory(displayedMonth, selectedCurrency));
        categoryItems.sort((left, right) -> right.amount.compareTo(left.amount));
        BigDecimal total = database.total(displayedMonth, selectedCurrency, ExpenseDatabase.TYPE_EXPENSE);
        breakdownEmpty.setVisibility(categoryItems.isEmpty() ? View.VISIBLE : View.GONE);
        categoryRecycler.setVisibility(categoryItems.isEmpty() ? View.GONE : View.VISIBLE);
        categoryAdapter.total = total;
        categoryAdapter.notifyDataSetChanged();
    }

    private void refreshTransactions() {
        if (transactionAdapter == null || historyEmpty == null || transactionsRecycler == null) return;
        transactionItems.clear();
        int filter = filterSpinner == null ? 0 : filterSpinner.getSelectedItemPosition();
        int type = filter == 1 ? ExpenseDatabase.TYPE_INCOME : filter == 2 ? ExpenseDatabase.TYPE_EXPENSE : -1;
        transactionItems.addAll(database.transactions(displayedMonth, selectedCurrency, type));
        historyEmpty.setVisibility(transactionItems.isEmpty() ? View.VISIBLE : View.GONE);
        transactionsRecycler.setVisibility(transactionItems.isEmpty() ? View.GONE : View.VISIBLE);
        transactionAdapter.notifyDataSetChanged();
    }

    private void startEditing(ExpenseDatabase.TransactionRecord record) {
        editingId = record.id;
        suppressTypeChange = true;
        lastTypeSelection = record.type == ExpenseDatabase.TYPE_INCOME ? 1 : 0;
        typeSpinner.setSelection(lastTypeSelection, false);
        updateCategorySpinner();
        String[] ids = record.type == ExpenseDatabase.TYPE_INCOME ? INCOME_CATEGORIES : EXPENSE_CATEGORIES;
        categorySpinner.setSelection(Math.max(0, Arrays.asList(ids).indexOf(record.category)), false);
        suppressTypeChange = false;
        int currencyIndex = Arrays.asList(CURRENCY_CODES).indexOf(record.currency);
        if (currencyIndex >= 0 && currencyIndex != lastCurrencySelection) {
            suppressCurrencyChange = true;
            lastCurrencySelection = currencyIndex;
            selectedCurrency = record.currency;
            currencySpinner.setSelection(currencyIndex, false);
            suppressCurrencyChange = false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_CURRENCY, selectedCurrency)
                    .putString(PREF_CURRENCY_LOCALE, locale.toLanguageTag())
                    .apply();
            refreshAll();
            loadBudgetInput();
        }
        amountInput.setText(record.amount.toPlainString());
        noteInput.setText(record.note);
        selectedDate = LocalDate.parse(record.date);
        updateDateLabel();
        formTitle.setText(R.string.expense_edit_title);
        saveTransactionButton.setText(R.string.expense_update);
        cancelEditButton.setVisibility(View.VISIBLE);
    }

    private void confirmDelete(ExpenseDatabase.TransactionRecord record) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.expense_delete_title)
                .setMessage(R.string.expense_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.expense_delete, (dialog, which) -> {
                    database.deleteTransaction(record.id);
                    if (editingId == record.id) clearForm();
                    Toast.makeText(this, R.string.expense_deleted, Toast.LENGTH_SHORT).show();
                    refreshAll();
                }).show();
    }

    private void showTransactionActions(ExpenseDatabase.TransactionRecord record) {
        String[] actions = {getString(R.string.expense_edit), getString(R.string.expense_delete)};
        new AlertDialog.Builder(this).setItems(actions, (dialog, which) -> {
            if (which == 0) startEditing(record); else confirmDelete(record);
        }).show();
    }

    private void copySummary() {
        BigDecimal income = database.total(displayedMonth, selectedCurrency, ExpenseDatabase.TYPE_INCOME);
        BigDecimal expenses = database.total(displayedMonth, selectedCurrency, ExpenseDatabase.TYPE_EXPENSE);
        String text = getString(R.string.expense_month_summary) + " · " + monthLabel.getText() + "\n"
                + getString(R.string.expense_income) + ": " + formatMoney(income, selectedCurrency) + "\n"
                + getString(R.string.expense_spending) + ": " + formatMoney(expenses, selectedCurrency) + "\n"
                + getString(R.string.expense_balance) + ": " + formatMoney(income.subtract(expenses), selectedCurrency);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.expense_title), text));
        Toast.makeText(this, R.string.expense_summary_copied, Toast.LENGTH_SHORT).show();
    }

    private String formatMoney(BigDecimal amount, String code) {
        try {
            Currency currency = Currency.getInstance(code);
            NumberFormat format = NumberFormat.getCurrencyInstance(locale);
            format.setCurrency(currency);
            int digits = Math.max(0, currency.getDefaultFractionDigits());
            format.setMinimumFractionDigits(digits);
            format.setMaximumFractionDigits(digits);
            return format.format(amount);
        } catch (RuntimeException ex) { return amount.toPlainString() + " " + code; }
    }

    private String defaultCurrency(Locale current) {
        String language = current.getLanguage().toLowerCase(Locale.ROOT);
        if ("vi".equals(language)) return "VND";
        if ("en".equals(language)) return "USD";
        String country = current.getCountry();
        if (country != null && !country.isEmpty()) {
            try {
                String byCountry = Currency.getInstance(new Locale("", country)).getCurrencyCode();
                if (Arrays.asList(CURRENCY_CODES).contains(byCountry)) return byCountry;
            } catch (RuntimeException ignored) { }
        }
        switch (language) {
            case "ja": return "JPY";
            case "zh": return "CNY";
            case "ko": return "KRW";
            case "fr": case "de": case "it": case "nl": case "fi": case "el":
            case "hr": case "sk": case "sl": case "et": case "lv": case "lt":
            case "mt": case "ca": case "ga": case "bg": return "EUR";
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
            default: return "USD";
        }
    }

    private void restoreState(Bundle state) {
        try {
            String month = state.getString("month");
            if (month != null) displayedMonth = YearMonth.parse(month);
            String date = state.getString("date");
            if (date != null) selectedDate = LocalDate.parse(date);
        } catch (RuntimeException ignored) {
            displayedMonth = YearMonth.now();
            selectedDate = LocalDate.now();
        }
        amountInput.setText(state.getString("amount", ""));
        noteInput.setText(state.getString("note", ""));
        suppressBudgetWatcher = true;
        budgetInput.setText(state.getString("budget", ""));
        suppressBudgetWatcher = false;
        budgetDraftChanged = state.getBoolean("budget_draft_changed", false);
        long restoredEditingId = state.getLong("editing_id", -1);
        String savedLocaleTag = state.getString("locale_tag", "");
        boolean localeUnchanged = savedLocaleTag.isEmpty() || savedLocaleTag.equals(locale.toLanguageTag());
        boolean hasDraft = !state.getString("amount", "").trim().isEmpty()
                || !state.getString("note", "").trim().isEmpty()
                || restoredEditingId > 0 || budgetDraftChanged;
        String restoredCurrency = state.getString("currency", selectedCurrency);
        if (localeUnchanged || hasDraft) {
            if (Arrays.asList(CURRENCY_CODES).contains(restoredCurrency)) selectedCurrency = restoredCurrency;
        } else {
            selectedCurrency = defaultCurrency(locale);
        }
        int restoredCurrencyIndex = Math.max(0, Arrays.asList(CURRENCY_CODES).indexOf(selectedCurrency));
        suppressCurrencyChange = true;
        lastCurrencySelection = restoredCurrencyIndex;
        currencySpinner.setSelection(restoredCurrencyIndex, false);
        suppressCurrencyChange = false;
        if (!localeUnchanged && !budgetDraftChanged) loadBudgetInput();
        int type = state.getInt("type", 0);
        suppressTypeChange = true;
        lastTypeSelection = type == 1 ? 1 : 0;
        typeSpinner.setSelection(lastTypeSelection, false);
        updateCategorySpinner();
        String category = state.getString("category", "");
        String[] categoryIds = type == 1 ? INCOME_CATEGORIES : EXPENSE_CATEGORIES;
        int categoryPosition = Arrays.asList(categoryIds).indexOf(category);
        if (categoryPosition >= 0) categorySpinner.setSelection(categoryPosition, false);
        suppressTypeChange = false;
        int filter = state.getInt("filter", 0);
        filterSpinner.setSelection(Math.max(0, Math.min(filterSpinner.getCount() - 1, filter)), false);
        editingId = restoredEditingId;
        if (editingId > 0) {
            formTitle.setText(R.string.expense_edit_title);
            saveTransactionButton.setText(R.string.expense_update);
            cancelEditButton.setVisibility(View.VISIBLE);
        }
        updateDateLabel();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("month", displayedMonth.toString());
        out.putString("date", selectedDate.toString());
        out.putString("currency", selectedCurrency);
        out.putString("locale_tag", locale == null ? "" : locale.toLanguageTag());
        out.putBoolean("budget_draft_changed", budgetDraftChanged);
        out.putString("amount", amountInput == null ? "" : amountInput.getText().toString());
        out.putString("note", noteInput == null ? "" : noteInput.getText().toString());
        out.putString("budget", budgetInput == null ? "" : budgetInput.getText().toString());
        out.putInt("type", typeSpinner == null ? 0 : typeSpinner.getSelectedItemPosition());
        String[] ids = typeSpinner != null && typeSpinner.getSelectedItemPosition() == 1 ? INCOME_CATEGORIES : EXPENSE_CATEGORIES;
        int categoryPosition = categorySpinner == null ? -1 : categorySpinner.getSelectedItemPosition();
        out.putString("category", categoryPosition >= 0 && categoryPosition < ids.length ? ids[categoryPosition] : "");
        out.putInt("filter", filterSpinner == null ? 0 : filterSpinner.getSelectedItemPosition());
        out.putLong("editing_id", editingId);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onDestroy() {
        if (database != null) database.close();
        super.onDestroy();
    }

    private interface SelectionAction { void selected(int position); }
    private static final class SimpleSelectionListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final SelectionAction action;
        SimpleSelectionListener(SelectionAction action) { this.action = action; }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { action.selected(position); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
    }

    private final class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.Holder> {
        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_expense_transaction, parent, false));
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            ExpenseDatabase.TransactionRecord record = transactionItems.get(position);
            holder.title.setText(categoryLabel(record.category));
            String note = record.note == null || record.note.isEmpty() ? getString(record.type == ExpenseDatabase.TYPE_INCOME ? R.string.expense_transaction_income : R.string.expense_transaction_expense) : record.note;
            String date = LocalDate.parse(record.date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale));
            holder.subtitle.setText(date + " · " + note);
            String prefix = record.type == ExpenseDatabase.TYPE_INCOME ? "+ " : "− ";
            holder.amount.setText(prefix + formatMoney(record.amount, record.currency));
            holder.itemView.setOnClickListener(v -> showTransactionActions(record));
        }
        @Override public int getItemCount() { return transactionItems.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final TextView title, subtitle, amount;
            Holder(View view) { super(view); title=view.findViewById(R.id.expenseRowTitle); subtitle=view.findViewById(R.id.expenseRowSubtitle); amount=view.findViewById(R.id.expenseRowAmount); }
        }
    }

    private final class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.Holder> {
        BigDecimal total = BigDecimal.ZERO;
        @Override public Holder onCreateViewHolder(ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_expense_category, parent, false));
        }
        @Override public void onBindViewHolder(Holder holder, int position) {
            ExpenseDatabase.CategoryTotal item = categoryItems.get(position);
            int percent = total.signum() <= 0 ? 0 : item.amount.multiply(BigDecimal.valueOf(100)).divide(total, 0, java.math.RoundingMode.HALF_UP).intValue();
            holder.name.setText(categoryLabel(item.category));
            holder.amount.setText(formatMoney(item.amount, selectedCurrency));
            holder.progress.setProgress(Math.max(0, Math.min(100, percent)));
        }
        @Override public int getItemCount() { return categoryItems.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final TextView name, amount; final ProgressBar progress;
            Holder(View view) { super(view); name=view.findViewById(R.id.expenseCategoryName); amount=view.findViewById(R.id.expenseCategoryAmount); progress=view.findViewById(R.id.expenseCategoryProgress); }
        }
    }

    private int categoryLabel(String id) {
        switch (id) {
            case "food": return R.string.expense_category_food;
            case "transport": return R.string.expense_category_transport;
            case "housing": return R.string.expense_category_housing;
            case "bills": return R.string.expense_category_bills;
            case "health": return R.string.expense_category_health;
            case "shopping": return R.string.expense_category_shopping;
            case "education": return R.string.expense_category_education;
            case "entertainment": return R.string.expense_category_entertainment;
            case "salary": return R.string.expense_category_salary;
            case "business": return R.string.expense_category_business;
            case "investment": return R.string.expense_category_investment;
            case "gift": return R.string.expense_category_gift;
            default: return R.string.expense_category_other;
        }
    }
}
