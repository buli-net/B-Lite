package wallet.ui;

import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;


import androidx.appcompat.app.AppCompatActivity;

/** Shared base behavior for system-bar insets and theme-aware selectable buttons. */
public abstract class BaseActivity extends AppCompatActivity {

    /** Applies the shared AppCompat DayNight styling used by selectable buttons. */
    protected final void applyChoiceButtonState(Button button, boolean selected) {
        if (button == null) return;

        button.setSelected(selected);
        button.setActivated(selected);
        if (selected) {
            button.setBackgroundTintList(resolveThemeColorStateList(
                    androidx.appcompat.R.attr.colorAccent));
            button.setTextColor(resolveThemeColorStateList(
                    android.R.attr.textColorPrimaryInverse));
        } else {
            button.setBackgroundTintList(resolveThemeColorStateList(
                    androidx.appcompat.R.attr.colorButtonNormal));
            button.setTextColor(resolveThemeColorStateList(
                    android.R.attr.textColorPrimary));
        }
    }

    private ColorStateList resolveThemeColorStateList(int attribute) {
        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(attribute, value, true)) {
            throw new IllegalStateException("Required theme color attribute is missing: " + attribute);
        }
        if (value.resourceId != 0) {
            ColorStateList colors = androidx.appcompat.content.res.AppCompatResources
                    .getColorStateList(this, value.resourceId);
            if (colors != null) return colors;
        }
        return ColorStateList.valueOf(value.data);
    }

    @Override
    protected void onPostCreate(Bundle state) {
        super.onPostCreate(state);
        applySystemBarInsets();
        TextViewUtils.configureLongValueViews(findViewById(android.R.id.content));
    }

    private void applySystemBarInsets() {
        if (Build.VERSION.SDK_INT < 35) {
            return;
        }

        final View content = findViewById(android.R.id.content);
        if (content == null) {
            return;
        }

        final int baseLeft = content.getPaddingLeft();
        final int baseTop = content.getPaddingTop();
        final int baseRight = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();

        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;

            if (Build.VERSION.SDK_INT >= 30) {
                Insets systemBars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = systemBars.left;
                top = systemBars.top;
                right = systemBars.right;
                bottom = systemBars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }

            view.setPadding(
                    baseLeft + left,
                    baseTop + top,
                    baseRight + right,
                    baseBottom + bottom);
            return insets;
        });
        content.requestApplyInsets();
    }
}
