package wallet.ui;

import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.webkit.WebView;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

import androidx.core.widget.NestedScrollView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import java.util.WeakHashMap;

/** Shared base behavior for system-bar insets and theme-aware selectable buttons. */
public abstract class BaseActivity extends AppCompatActivity {

    private static final int SCROLLBAR_FADE_DELAY_MS = 650;
    private static final int SCROLLBAR_FADE_DURATION_MS = 250;

    private ViewTreeObserver.OnGlobalLayoutListener autoScrollbarLayoutListener;
    private final WeakHashMap<View, Boolean> autoScrollbarConfigured = new WeakHashMap<>();
    private final WeakHashMap<View, Boolean> autoScrollbarAwakened = new WeakHashMap<>();

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

        final View content = findViewById(android.R.id.content);
        TextViewUtils.configureLongValueViews(content);
        if (content != null) {
            installAutomaticScrollbars(content);
        }
    }

    /**
     * Enables native, content-sized scroll indicators for every scrollable widget in
     * the activity view tree. Android computes the thumb size from the current content
     * and viewport, and fades it after scrolling stops.
     */
    private void installAutomaticScrollbars(View root) {
        configureAutomaticScrollbars(root);

        autoScrollbarLayoutListener = () -> configureAutomaticScrollbars(root);
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer.isAlive()) {
            observer.addOnGlobalLayoutListener(autoScrollbarLayoutListener);
        }
    }

    private void configureAutomaticScrollbars(View view) {
        if (view == null) return;

        if (view instanceof ScrollView
                || view instanceof NestedScrollView
                || view instanceof AbsListView) {
            configureScrollbarsOnce(view, true, false);
        } else if (view instanceof HorizontalScrollView) {
            configureScrollbarsOnce(view, false, true);
        } else if (view instanceof RecyclerView || view instanceof WebView) {
            // RecyclerViews can be vertical, horizontal or grid based. WebView pages
            // can scroll in either direction, so let the platform show only the axis
            // that currently has a non-zero scroll range.
            configureScrollbarsOnce(view, true, true);
        } else if (view instanceof EditText) {
            EditText editText = (EditText) view;
            if ((editText.getInputType()
                    & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) {
                configureScrollbarsOnce(view, true, false);
            }
        } else if (view.canScrollVertically(-1) || view.canScrollVertically(1)) {
            // Covers custom views that implement Android's normal vertical scrolling API.
            configureScrollbarsOnce(view, true, false);
        } else if (view.canScrollHorizontally(-1) || view.canScrollHorizontally(1)) {
            configureScrollbarsOnce(view, false, true);
        }

        // Native Android scrollbars are normally awakened by a real scroll gesture.
        // Nudge scrollable views by one pixel and immediately restore their position
        // so a long screen also shows its proportional thumb briefly on first layout.
        awakenScrollbarWhenScrollable(view);

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                configureAutomaticScrollbars(group.getChildAt(i));
            }
        }
    }

    private void awakenScrollbarWhenScrollable(View view) {
        if (autoScrollbarAwakened.containsKey(view)) return;

        final boolean canScrollDown = view.canScrollVertically(1);
        final boolean canScrollUp = view.canScrollVertically(-1);
        final boolean canScrollRight = view.canScrollHorizontally(1);
        final boolean canScrollLeft = view.canScrollHorizontally(-1);
        if (!canScrollDown && !canScrollUp && !canScrollRight && !canScrollLeft) return;

        autoScrollbarAwakened.put(view, Boolean.TRUE);
        view.post(() -> {
            if (view.isAttachedToWindow()) {
                if (view.canScrollVertically(1)) {
                    view.scrollBy(0, 1);
                    view.scrollBy(0, -1);
                } else if (view.canScrollVertically(-1)) {
                    view.scrollBy(0, -1);
                    view.scrollBy(0, 1);
                } else if (view.canScrollHorizontally(1)) {
                    view.scrollBy(1, 0);
                    view.scrollBy(-1, 0);
                } else if (view.canScrollHorizontally(-1)) {
                    view.scrollBy(-1, 0);
                    view.scrollBy(1, 0);
                } else {
                    autoScrollbarAwakened.remove(view);
                }
            } else {
                autoScrollbarAwakened.remove(view);
            }
        });
    }

    private void configureScrollbarsOnce(View view, boolean vertical, boolean horizontal) {
        if (autoScrollbarConfigured.containsKey(view)) return;

        if (vertical) configureVerticalScrollbar(view);
        if (horizontal) configureHorizontalScrollbar(view);
        autoScrollbarConfigured.put(view, Boolean.TRUE);
    }

    private void configureVerticalScrollbar(View view) {
        view.setVerticalScrollBarEnabled(true);
        view.setVerticalScrollbarPosition(View.SCROLLBAR_POSITION_RIGHT);
        view.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        configureScrollbarFading(view);
    }

    private void configureHorizontalScrollbar(View view) {
        view.setHorizontalScrollBarEnabled(true);
        view.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        configureScrollbarFading(view);
    }

    private void configureScrollbarFading(View view) {
        view.setScrollbarFadingEnabled(true);
        view.setScrollBarDefaultDelayBeforeFade(SCROLLBAR_FADE_DELAY_MS);
        view.setScrollBarFadeDuration(SCROLLBAR_FADE_DURATION_MS);
    }

    @Override
    protected void onDestroy() {
        View content = findViewById(android.R.id.content);
        if (content != null && autoScrollbarLayoutListener != null) {
            ViewTreeObserver observer = content.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(autoScrollbarLayoutListener);
            }
        }
        autoScrollbarLayoutListener = null;
        autoScrollbarConfigured.clear();
        autoScrollbarAwakened.clear();
        super.onDestroy();
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
