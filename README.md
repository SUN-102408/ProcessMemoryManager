# Process Memory Manager (PMM)

Version: 0.3.0-alpha2

## Changes
- Fix process-list scrolling inside the dashboard; allow the RecyclerView to own touch gestures.
- Fix process-name sanitization (the previous tr character set accidentally replaced ordinary letters).
- Decode global ZRAM mm_stat into original/compressed/physical-used sizes and ratio.
- Add a guarded process-reclaim capability panel with kernel-version diagnostics.
- Keep copy/share reports as plain text.

## Reclaim capability and safety
The app does not pretend that global ZRAM activity is per-process compression. A real process-specific page-out path requires kernel support such as process_madvise() and MADV_PAGEOUT, plus runtime validation. These interfaces are not part of upstream Linux 4.4. On older kernels, the app reports the limitation and does not run drop_caches, memory.force_empty, process freezing, process killing, or global swap changes as a substitute.

No process reclaim is executed by this alpha build. It remains a diagnostic/capability-check release.
