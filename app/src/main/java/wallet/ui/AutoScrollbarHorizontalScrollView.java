package wallet.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.HorizontalScrollView;

/** Horizontal scroll container that briefly shows its native thumb when content size changes. */
public final class AutoScrollbarHorizontalScrollView extends HorizontalScrollView {
    private static final int FADE_DELAY_MS = 650;
    private static final int FADE_DURATION_MS = 250;

    private int lastScrollRange = -1;
    private int lastScrollExtent = -1;

    public AutoScrollbarHorizontalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initializeScrollbars();
    }

    private void initializeScrollbars() {
        setHorizontalScrollBarEnabled(true);
        setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        setScrollbarFadingEnabled(true);
        setScrollBarDefaultDelayBeforeFade(FADE_DELAY_MS);
        setScrollBarFadeDuration(FADE_DURATION_MS);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        int range = computeHorizontalScrollRange();
        int extent = computeHorizontalScrollExtent();
        if (range == lastScrollRange && extent == lastScrollExtent) return;

        lastScrollRange = range;
        lastScrollExtent = extent;
        post(this::showNativeScrollbarIfScrollable);
    }

    private void showNativeScrollbarIfScrollable() {
        if (!isAttachedToWindow()) return;
        if (canScrollHorizontally(-1) || canScrollHorizontally(1)) {
            awakenScrollBars();
        }
    }
}
