# Process Memory Manager (PMM)

Version: 0.3.0-alpha1 — redesigned read-only Android process diagnostics.

## Included
- Dark dashboard and adaptive launcher icon.
- RecyclerView process list with name/PID/UID filtering.
- Streaming progress by processed PID count and current stage.
- Quick scan plus optional detailed PSS via /proc/PID/smaps.
- Copy/share diagnostic reports as plain text, without FileProvider attachments.
- Root access is requested only after the user taps the Root check button.

## Safety
This alpha remains read-only. It does not kill, freeze, force-stop, or reclaim processes and does not write to /proc, /sys, cgroups, swap, or system properties.

Progress is a processed-entry count, not an ETA. ZRAM figures are global kernel statistics and are not attributed to individual processes.
