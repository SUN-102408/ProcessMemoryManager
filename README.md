# Process Memory Manager (PMM)

Version: 0.2.0-alpha2 — rooted Android process diagnostics and read-only capability probe.

## Included in this version

- Quick scan is the default; optional detailed PSS scan reads `/proc/PID/smaps`.
- System UID filtering is applied before detailed smaps reads.
- Root shell output uses a merged output stream, a reader thread, and command timeouts.
- Process list can select multiple processes. Selection is identified by PID + start-time token and is currently diagnostic only.
- Read-only kernel capability probe checks kernel/config/cgroup/ZRAM-related paths and visible symbols.
- Full diagnostic report and log are exported as UTF-8 `.txt` files through Android's share sheet using FileProvider content URIs. They are attachments, not clipboard text.
- Swap is labeled `Swap`, not `SwapPss`.

## Safety boundary

This version does not write to `/proc`, `/sys`, cgroups, swap devices, system properties, or partitions. It does not kill, freeze, force-stop, or reclaim processes. The reclaim action is deliberately disabled until a safe, per-process mechanism is demonstrated on the target kernel.

A visible kernel interface or config flag is not proof that it can safely reclaim a selected process. `memory.force_empty` is not a per-process compression button; `swappiness` is system-wide; ZRAM statistics are global.

## Build

JDK 17, Gradle 8.7, Android SDK 35. Run `gradle --no-daemon assembleDebug`. GitHub Actions workflow is included.
