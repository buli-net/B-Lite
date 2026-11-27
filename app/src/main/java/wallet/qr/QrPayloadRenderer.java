package wallet.qr;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import wallet.main.R;

/** Renders decoded payload fields with the same read-only field layout used by NFC Tools. */
final class QrPayloadRenderer {
    private QrPayloadRenderer() { }

    static void render(Context context, LinearLayout container, String content) {
        container.removeAllViews();
        QrPayloadParser.ParsedContent parsed = QrPayloadParser.parse(context, content);
        LayoutInflater inflater = LayoutInflater.from(context);
        for (QrPayloadParser.Field field : parsed.displayFields) {
            View row = inflater.inflate(R.layout.item_qr_decoded_field, container, false);
            TextView label = row.findViewById(R.id.qrDecodedLabel);
            TextView value = row.findViewById(R.id.qrDecodedValue);
            label.setText(field.label);
            value.setText(field.valueResource != 0
                    ? context.getString(field.valueResource) : field.value);
            container.addView(row);
        }
        if (parsed.displayFields.isEmpty()) {
            TextView empty = (TextView) inflater.inflate(R.layout.item_qr_decoded_empty, container, false);
            empty.setText(R.string.qr_tools_no_decoded_fields);
            container.addView(empty);
        }
    }
}
