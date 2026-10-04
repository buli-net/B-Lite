# B-Lite v37

Base: B-Lite v36.

Change:
- Reverted TextViewUtils ellipsis range selection to the exact balanced pixel-based two-ended `findVisibleRange()` algorithm used by v17.
- Kept the v33/v36-safe rendering infrastructure: `setTextIfChanged`, source tracking, selection preservation, and per-frame coalesced apply.
- No wallet/business logic changes.
- No debug logging added.

Validation:
- v17 `findVisibleRange()` == v37 `findVisibleRange()`.
- Required ProGuard lines retained; `-dontoptimize` absent.
