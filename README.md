# Process Memory Manager (PMM)

Version: 0.1.0-alpha1 — read-only prototype for rooted Android 8.0+.

## Safety boundary

This build only reads process metadata, `/proc/*/status`, `/proc/*/stat`, `/proc/*/smaps`, `/proc/swaps`, and `/sys/block/zram0/mm_stat`. It does **not** write to `/proc`, `/sys`, cgroups, swap devices, system properties, or partitions. It does not kill, freeze, or reclaim processes.

Root is requested only when the user taps the Root check or scan button. There is no boot receiver or background service.

## Features

- Root identity check (`id`)
- Process PID, UID, command/name, start-time token
- RSS, PSS and Swap values (detailed PSS is collected from smaps)
- Global ZRAM `mm_stat` and `/proc/swaps` snapshot
- Snapshot comparison keyed by PID + process start time
- Local append-only runtime log (`files/pmm.log`)
- Toggles for system-process visibility, detailed smaps scan, snapshot comparison, logging and verbose logs

## Important limitations

- Android/Linux vendor kernels may restrict access to `smaps`; inaccessible processes can be absent or report zero values.
- `smaps` scanning is intentionally manual and can take time. It may cause a short CPU load.
- ZRAM statistics are global, not attributable to an individual process. The app never labels the global ratio as a per-process compression ratio.
- RSS/PSS totals across processes can double-count shared memory; they are not a physical RAM total.
- Process start-time parsing is used with PID to reduce PID-reuse mistakes; it is not a persistent process identity across reboot.
- This prototype has not yet been tested on the target Mi 8 Lite. Validate its read-only output before adding any reclaim operation.

## Build

Open the repository in Android Studio or run `gradle assembleDebug` with JDK 17 and Android SDK 35. GitHub Actions workflow is included under `.github/workflows/build.yml` and can be triggered manually.
