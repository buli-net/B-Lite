package wallet.ui;

import android.content.Context;
import wallet.main.R;

/** Localized resource-backed exceptions for validation and user-facing operation failures. */
public final class LocalizedErrors {
    private LocalizedErrors() { }

    public interface ResourceMessage {
        int resourceId();
        Object[] resourceArguments();
    }

    private static final class ResourceArgument {
        final int id;
        ResourceArgument(int id) { this.id = id; }
    }

    public static Object resource(int id) { return new ResourceArgument(id); }

    public static class IOException extends java.io.IOException implements ResourceMessage {
        private final int id; private final Object[] args;
        public IOException(int id, Object... args) { super((String) null); this.id=id; this.args=args == null ? new Object[0] : args.clone(); }
        public IOException(int id, Throwable cause) { super((String) null, cause); this.id=id; this.args=new Object[0]; }
        public IOException(int id, Throwable cause, Object... args) { super((String) null, cause); this.id=id; this.args=args == null ? new Object[0] : args.clone(); }
        @Override public int resourceId() { return id; }
        @Override public Object[] resourceArguments() { return args.clone(); }
    }

    public static class IllegalArgumentException extends java.lang.IllegalArgumentException implements ResourceMessage {
        private final int id; private final Object[] args;
        public IllegalArgumentException(int id, Object... args) { super((String) null); this.id=id; this.args=args == null ? new Object[0] : args.clone(); }
        public IllegalArgumentException(int id, Throwable cause) { super((String) null, cause); this.id=id; this.args=new Object[0]; }
        @Override public int resourceId() { return id; }
        @Override public Object[] resourceArguments() { return args.clone(); }
    }

    public static class IllegalStateException extends java.lang.IllegalStateException implements ResourceMessage {
        private final int id; private final Object[] args;
        public IllegalStateException(int id, Object... args) { super((String) null); this.id=id; this.args=args == null ? new Object[0] : args.clone(); }
        public IllegalStateException(int id, Throwable cause) { super((String) null, cause); this.id=id; this.args=new Object[0]; }
        @Override public int resourceId() { return id; }
        @Override public Object[] resourceArguments() { return args.clone(); }
    }

    public static String resolve(Context context, Throwable error) {
        if (context == null) return "";
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ResourceMessage) {
                ResourceMessage message = (ResourceMessage) current;
                Object[] args = message.resourceArguments();
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof ResourceArgument) args[i] = context.getString(((ResourceArgument) args[i]).id);
                }
                try { return context.getString(message.resourceId(), args); }
                catch (RuntimeException ignored) { return context.getString(message.resourceId()); }
            }
            if (current instanceof org.json.JSONException) {
                return context.getString(R.string.backup_app_data_invalid);
            }
        }
        if (error != null && error.getMessage() != null && !error.getMessage().trim().isEmpty()) return error.getMessage();
        return context.getString(R.string.error_unknown);
    }

}
