package wallet.contacts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/** Renders a saved contact photo or a theme-aware initials placeholder. */
final class ContactAvatarView {
    private ContactAvatarView() {}

    static void bind(Context context, AddressBookStore.Entry entry, ImageView image, TextView initials) {
        initials.setText(initials(entry == null ? "" : entry.name));
        Bitmap bitmap = null;
        if (entry != null && !TextUtils.isEmpty(entry.avatarFileName)) {
            File file = AddressBookStore.getAvatarFile(context, entry.avatarFileName);
            if (file != null && file.isFile()) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                options.inSampleSize = 2;
                bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            }
        }
        if (bitmap != null) {
            image.setImageBitmap(bitmap);
            image.setVisibility(View.VISIBLE);
            initials.setVisibility(View.GONE);
        } else {
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
            initials.setVisibility(View.VISIBLE);
        }
    }

    private static String initials(String name) {
        if (TextUtils.isEmpty(name) || name.trim().isEmpty()) return "•";
        String[] parts = name.trim().split("\\s+");
        if (parts.length > 1) {
            return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1))
                    .toUpperCase(Locale.getDefault());
        }
        return parts[0].substring(0, 1).toUpperCase(Locale.getDefault());
    }
}
