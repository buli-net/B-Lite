package wallet.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.widget.NestedScrollView;

/** Nested scroll container that briefly shows its native thumb when content size changes. */
public final class AutoScrollbarNestedScrollView extends NestedScrollView {
    private static final int FADE_DELAY_MS = 650;
    private static final int FADE_DURATION_MS = 250;

    private int lastScrollRange = -1;
    private int lastScrollExtent = -1;

    public AutoScrollbarNestedScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initializeScrollbars();
    }

    private void initializeScrollbars() {
        setVerticalScrollBarEnabled(true);
        setVerticalScrollbarPosition(View.SCROLLBAR_POSITION_RIGHT);
        setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        setScrollbarFadingEnabled(true);
        setScrollBarDefaultDelayBeforeFade(FADE_DELAY_MS);
        setScrollBarFadeDuration(FADE_DURATION_MS);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        int range = computeVerticalScrollRange();
        int extent = computeVerticalScrollExtent();
        if (range == lastScrollRange && extent == lastScrollExtent) return;

        lastScrollRange = range;
        lastScrollExtent = extent;
        post(this::showNativeScrollbarIfScrollable);
    }

    private void showNativeScrollbarIfScrollable() {
        if (!isAttachedToWindow()) return;
        if (canScrollVertically(-1) || canScrollVertically(1)) {
            // This is the Android View API intended to awaken its native scrollbar.
            // Calling it inside this subclass is permitted; callers outside View cannot.
            awakenScrollBars();
        }
    }
}
