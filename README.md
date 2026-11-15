# Process Memory Manager (PMM)

Version: 0.3.0-alpha3

## Changes
- Read the first argument from /proc/PID/cmdline for full Android process names; fall back to /proc/PID/status Name when cmdline is unavailable.
- Add detailed smaps metrics: PSS, Private_Dirty, Shared_Dirty, and Swap.
- Add process sorting by RSS, PSS (falls back to RSS when PSS is unavailable), or Swap.
- Add UID-class filtering for all processes, application UIDs, and system UIDs.
- Preserve the alpha2 RecyclerView scrolling fix, read-only diagnostics, global ZRAM reporting, and capability-only reclaim panel.

## Metric notes
- RSS includes shared resident pages and must not be summed as unique physical memory.
- PSS proportionally accounts for shared pages.
- Private_Dirty and Shared_Dirty are smaps totals, in kB.
- Per-process Swap is read from smaps in detailed mode or VmSwap in quick mode.
- ZRAM mm_stat is global kernel accounting, not attributable to an individual process.
- Android UIDs can include a user-id offset; UID classification uses uid modulo 100000.

## Safety
No process reclaim is executed. The app does not kill or freeze processes and does not use drop_caches, memory.force_empty, or global swap changes as a substitute for process-specific page-out.
