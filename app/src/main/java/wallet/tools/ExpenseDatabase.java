package wallet.tools;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

final class ExpenseDatabase extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "personal_expenses.db";
    private static final int DATABASE_VERSION = 1;
    static final int TYPE_EXPENSE = 0;
    static final int TYPE_INCOME = 1;

    static final class TransactionRecord {
        long id;
        int type;
        BigDecimal amount;
        String currency;
        String category;
        String date;
        String note;
    }

    static final class CategoryTotal {
        String category;
        BigDecimal amount;
    }

    ExpenseDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, type INTEGER NOT NULL, amount TEXT NOT NULL, currency TEXT NOT NULL, category TEXT NOT NULL, transaction_date TEXT NOT NULL, note TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX transactions_month_currency ON transactions(transaction_date, currency, type)");
        db.execSQL("CREATE TABLE budgets (month TEXT NOT NULL, currency TEXT NOT NULL, amount TEXT NOT NULL, PRIMARY KEY(month, currency))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Versioned migrations will be added here when the schema changes.
    }

    long saveTransaction(TransactionRecord record) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("type", record.type);
        values.put("amount", record.amount.toPlainString());
        values.put("currency", record.currency);
        values.put("category", record.category);
        values.put("transaction_date", record.date);
        values.put("note", record.note == null ? "" : record.note);
        if (record.id > 0) {
            int changed = db.update("transactions", values, "id=?", new String[]{String.valueOf(record.id)});
            return changed > 0 ? record.id : -1;
        }
        return db.insertOrThrow("transactions", null, values);
    }

    boolean deleteTransaction(long id) {
        return getWritableDatabase().delete("transactions", "id=?", new String[]{String.valueOf(id)}) > 0;
    }

    List<TransactionRecord> transactions(YearMonth month, String currency, int filterType) {
        List<TransactionRecord> result = new ArrayList<>();
        String start = month.atDay(1).toString();
        String end = month.plusMonths(1).atDay(1).toString();
        String selection = "currency=? AND transaction_date>=? AND transaction_date<?";
        List<String> args = new ArrayList<>();
        args.add(currency); args.add(start); args.add(end);
        if (filterType >= 0) {
            selection += " AND type=?";
            args.add(String.valueOf(filterType));
        }
        try (Cursor cursor = getReadableDatabase().query("transactions", null, selection,
                args.toArray(new String[0]), null, null, "transaction_date DESC, id DESC")) {
            while (cursor.moveToNext()) result.add(readTransaction(cursor));
        }
        return result;
    }

    TransactionRecord transaction(long id) {
        try (Cursor cursor = getReadableDatabase().query("transactions", null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            return cursor.moveToFirst() ? readTransaction(cursor) : null;
        }
    }

    BigDecimal total(YearMonth month, String currency, int type) {
        BigDecimal sum = BigDecimal.ZERO;
        String start = month.atDay(1).toString();
        String end = month.plusMonths(1).atDay(1).toString();
        try (Cursor cursor = getReadableDatabase().query("transactions", new String[]{"amount"},
                "currency=? AND transaction_date>=? AND transaction_date<? AND type=?",
                new String[]{currency, start, end, String.valueOf(type)}, null, null, null)) {
            while (cursor.moveToNext()) {
                try { sum = sum.add(new BigDecimal(cursor.getString(0))); }
                catch (NumberFormatException ignored) { }
            }
        }
        return sum;
    }

    List<CategoryTotal> expenseTotalsByCategory(YearMonth month, String currency) {
        List<CategoryTotal> result = new ArrayList<>();
        String start = month.atDay(1).toString();
        String end = month.plusMonths(1).atDay(1).toString();
        try (Cursor cursor = getReadableDatabase().query("transactions",
                new String[]{"category", "amount"},
                "currency=? AND transaction_date>=? AND transaction_date<? AND type=?",
                new String[]{currency, start, end, String.valueOf(TYPE_EXPENSE)},
                null, null, "category ASC")) {
            while (cursor.moveToNext()) {
                String category = cursor.getString(0);
                BigDecimal amount;
                try { amount = new BigDecimal(cursor.getString(1)); }
                catch (NumberFormatException ex) { continue; }
                CategoryTotal found = null;
                for (CategoryTotal item : result) if (item.category.equals(category)) { found = item; break; }
                if (found == null) { found = new CategoryTotal(); found.category = category; found.amount = BigDecimal.ZERO; result.add(found); }
                found.amount = found.amount.add(amount);
            }
        }
        return result;
    }

    BigDecimal budget(YearMonth month, String currency) {
        try (Cursor cursor = getReadableDatabase().query("budgets", new String[]{"amount"},
                "month=? AND currency=?", new String[]{month.toString(), currency}, null, null, null)) {
            if (cursor.moveToFirst()) return new BigDecimal(cursor.getString(0));
        } catch (NumberFormatException ignored) { }
        return BigDecimal.ZERO;
    }

    void saveBudget(YearMonth month, String currency, BigDecimal amount) {
        ContentValues values = new ContentValues();
        values.put("month", month.toString());
        values.put("currency", currency);
        values.put("amount", amount.toPlainString());
        getWritableDatabase().insertWithOnConflict("budgets", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private TransactionRecord readTransaction(Cursor cursor) {
        TransactionRecord record = new TransactionRecord();
        record.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        record.type = cursor.getInt(cursor.getColumnIndexOrThrow("type"));
        record.amount = new BigDecimal(cursor.getString(cursor.getColumnIndexOrThrow("amount")));
        record.currency = cursor.getString(cursor.getColumnIndexOrThrow("currency"));
        record.category = cursor.getString(cursor.getColumnIndexOrThrow("category"));
        record.date = cursor.getString(cursor.getColumnIndexOrThrow("transaction_date"));
        record.note = cursor.getString(cursor.getColumnIndexOrThrow("note"));
        return record;
    }
}
