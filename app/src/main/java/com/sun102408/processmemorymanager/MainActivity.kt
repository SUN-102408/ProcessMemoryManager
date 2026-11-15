package com.sun102408.processmemorymanager

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Typeface
import android.view.View
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var results: TextView
    private lateinit var logView: TextView
    private lateinit var scanButton: Button
    private lateinit var rootButton: Button
    private lateinit var capabilityButton: Button
    private lateinit var reclaimButton: Button
    private lateinit var showSystem: Switch
    private lateinit var detailedScan: Switch
    private lateinit var compareSnapshots: Switch
    private lateinit var loggingEnabled: Switch
    private lateinit var debugLogging: Switch
    private lateinit var selectionSummary: TextView
    private var lastSnapshot: ScanSnapshot? = null
    private var latestSnapshot: ScanSnapshot? = null
    private var latestCapabilities: CapabilityReport? = null
    private val selectedProcesses = linkedSetOf<String>()
    private var rootReady = false

    private val logFile get() = File(filesDir, "pmm.log")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        log("APP_START", "Process Memory Manager 0.2.0-alpha2 started")
        status.text = "尚未检查 Root 权限。当前版本仍以只读诊断为主。"
        refreshLog()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); setBackgroundColor(0xFFF8FAFC.toInt()) }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(text("Process Memory Manager", 23, true))
        body.addView(text("0.2.0-alpha2 · 进程诊断 / 内核能力探测", 13, false).apply { setTextColor(0xFF64748B.toInt()) })
        body.addView(gap(10))
        status = panelText("状态：等待检测")
        body.addView(status)
        rootButton = Button(this).apply { text = "检查 Root 权限"; setOnClickListener { checkRoot() } }
        body.addView(rootButton)
        scanButton = Button(this).apply { text = "扫描进程内存"; isEnabled = false; setOnClickListener { runScan() } }
        body.addView(scanButton)

        body.addView(gap(8))
        body.addView(text("扫描设置", 18, true))
        showSystem = addSwitch(body, "扫描并显示系统进程", true, "关闭后在扫描阶段就跳过 UID < 10000 的进程，减少详细扫描工作量")
        detailedScan = addSwitch(body, "详细 PSS 扫描（较慢）", false, "开启后读取每个目标进程的 smaps；建议先快速扫描，需要精确 PSS 时再开启")
        compareSnapshots = addSwitch(body, "对比上一次快照", true, "按 PID + 启动时间匹配；Swap 是进程 Swap，不是 SwapPss")
        loggingEnabled = addSwitch(body, "记录运行日志", true, "日志保存在应用私有目录")
        debugLogging = addSwitch(body, "详细调试日志", false, "额外记录扫描耗时与统计")

        body.addView(gap(10))
        body.addView(text("内存概览", 18, true))
        summary = panelText("尚无扫描数据")
        body.addView(summary)

        body.addView(gap(10))
        body.addView(text("进程选择", 18, true))
        selectionSummary = panelText("扫描后可选择进程。当前不会对任何进程执行操作。")
        body.addView(selectionSummary)
        body.addView(Button(this).apply { text = "选择 / 管理进程"; setOnClickListener { showProcessPicker() } })
        body.addView(text("扫描结果（按 PSS 降序，最多显示 100 个）", 16, true))
        results = panelText("点击扫描开始。")
        body.addView(results)

        body.addView(gap(10))
        body.addView(text("内核能力检测与回收实验", 18, true))
        body.addView(text("先检测设备实际提供的接口。检测过程只读，不会修改 cgroup、Swap、ZRAM 或进程状态。", 12, false).apply { setTextColor(0xFF64748B.toInt()) })
        capabilityButton = Button(this).apply { text = "检测内核内存回收能力"; isEnabled = false; setOnClickListener { runCapabilityProbe() } }
        body.addView(capabilityButton)
        reclaimButton = Button(this).apply { text = "对所选进程执行内存回收（尚未启用）"; isEnabled = false; setOnClickListener { showReclaimNotReady() } }
        body.addView(reclaimButton)

        body.addView(gap(10))
        body.addView(text("报告与日志", 18, true))
        body.addView(Button(this).apply { text = "分享完整诊断报告（文件）"; setOnClickListener { shareReport() } })
        body.addView(Button(this).apply { text = "分享运行日志（文件）"; setOnClickListener { shareLogFile() } })
        logView = panelText("日志尚未加载")
        body.addView(logView)
        val logButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logButtons.addView(Button(this).apply { text = "刷新日志"; setOnClickListener { refreshLog() } }, LinearLayout.LayoutParams(0, -2, 1f))
        logButtons.addView(Button(this).apply { text = "清空日志"; setOnClickListener { log("LOG_CLEAR", "User requested log clear"); logFile.writeText(""); log("LOG_CLEAR", "Log cleared"); refreshLog() } }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(logButtons)
        body.addView(text("安全边界：不会杀进程、冻结进程或执行未经验证的回收操作。当前“回收”按钮保持禁用。", 12, false).apply { setTextColor(0xFFB45309.toInt()) })
        setContentView(root)
    }

    private fun addSwitch(parent: LinearLayout, title: String, initial: Boolean, hint: String): Switch {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(5), dp(10), dp(5)); setBackgroundColor(0xFFFFFFFF.toInt()) }
        box.addView(Switch(this).apply { text = title; isChecked = initial }.also { })
        val sw = box.getChildAt(0) as Switch
        box.addView(text(hint, 11, false).apply { setTextColor(0xFF64748B.toInt()) })
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        return sw
    }

    private fun checkRoot() {
        rootButton.isEnabled = false
        status.text = "正在请求 Root 授权…"
        log("ROOT_CHECK", "Requesting su identity")
        executor.execute {
            val result = RootReader.run("id", 15)
            runOnUiThread {
                rootButton.isEnabled = true
                rootReady = result.exitCode == 0 && result.output.contains("uid=0")
                if (rootReady) {
                    status.text = "Root 正常：${result.output.trim()}"
                    scanButton.isEnabled = true
                    capabilityButton.isEnabled = true
                    log("ROOT_OK", result.output.trim())
                } else {
                    status.text = "未获得 Root 权限或命令超时。\n${result.output}"
                    scanButton.isEnabled = false
                    capabilityButton.isEnabled = false
                    log("ROOT_FAIL", "exit=${result.exitCode}; ${result.output}")
                }
                refreshLog()
            }
        }
    }

    private fun runScan() {
        scanButton.isEnabled = false
        status.text = "正在扫描……快速扫描通常较快；详细 smaps 会明显增加耗时。"
        val detailed = detailedScan.isChecked
        val includeSystem = showSystem.isChecked
        log("SCAN_BEGIN", "detailed=$detailed; showSystem=$includeSystem")
        executor.execute {
            val result = RootReader.scan(detailed, includeSystem)
            runOnUiThread {
                scanButton.isEnabled = true
                if (result.error != null || result.snapshot == null) {
                    status.text = "扫描失败：${result.error ?: "未知错误"}"
                    log("SCAN_ERROR", result.error ?: "unknown")
                } else {
                    lastSnapshot = latestSnapshot
                    latestSnapshot = result.snapshot
                    val snapshot = result.snapshot
                    val visible = snapshot.processes
                    val totalPss = visible.sumOf { it.pssKb }
                    summary.text = buildString {
                        append("扫描时间：${snapshot.time}\n耗时：${result.elapsedMs} ms\n")
                        append("扫描进程：${visible.size}\n")
                        append(if (detailed) "列表 PSS 合计：${fmt(totalPss)}\n" else "PSS：本次为快速扫描，未读取 smaps\n")
                        append("全局 ZRAM：${snapshot.zramSummary}\n")
                        append("Swap 设备：${snapshot.swapSummary}\n")
                        append("说明：列表 PSS 合计不等于系统总 RAM 使用量；ZRAM 统计为全局值。")
                    }
                    val previous = lastSnapshot
                    results.text = buildString {
                        if (compareSnapshots.isChecked && previous != null && detailed) append("与上次扫描对比（PID + 启动时间）\n\n")
                        visible.sortedByDescending { if (detailed) it.pssKb else it.rssKb }.take(100).forEach { p ->
                            append("${p.name}  [PID ${p.pid}, UID ${p.uid}]\n")
                            append("  RSS ${fmt(p.rssKb)} | PSS ${if (detailed) fmt(p.pssKb) else "未采集"} | Swap ${fmt(p.swapKb)}\n")
                            if (compareSnapshots.isChecked && previous != null && detailed) {
                                val old = previous.processes.firstOrNull { it.pid == p.pid && it.startTicks == p.startTicks }
                                if (old != null && old.pssKb > 0) append("  PSS 变化 ${signed(p.pssKb - old.pssKb)} | Swap 变化 ${signed(p.swapKb - old.swapKb)}\n")
                                else append("  PSS 对比不可用（上次无详细 PSS 或进程未匹配）\n")
                            }
                            append("\n")
                        }
                    }
                    selectedProcesses.retainAll(snapshot.processes.map { it.identity }.toSet())
                    updateSelectionSummary()
                    status.text = "扫描完成。耗时 ${result.elapsedMs} ms；只读取数据，没有修改系统状态。"
                    log("SCAN_OK", "processes=${snapshot.processes.size}; elapsed=${result.elapsedMs}ms; detailed=$detailed; zram=${snapshot.zramSummary}")
                    if (debugLogging.isChecked) log("SCAN_DEBUG", "swap=${snapshot.swapSummary}")
                }
                refreshLog()
            }
        }
    }

    private fun showProcessPicker() {
        val snapshot = latestSnapshot
        if (snapshot == null) { AlertDialog.Builder(this).setMessage("请先扫描进程。").setPositiveButton("知道了", null).show(); return }
        val items = snapshot.processes.sortedByDescending { if (it.pssKb > 0) it.pssKb else it.rssKb }.take(500)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        val search = EditText(this).apply { hint = "搜索进程名 / PID / UID"; setSingleLine(true) }
        val count = TextView(this).apply { textSize = 12f; setTextColor(0xFF64748B.toInt()); setPadding(0, dp(4), 0, dp(6)) }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = ScrollView(this).apply { addView(rows); layoutParams = LinearLayout.LayoutParams(-1, dp(390)) }
        container.addView(search)
        container.addView(count)
        container.addView(listScroll)
        fun refreshRows(query: String) {
            rows.removeAllViews()
            val filtered = items.filter { p -> query.isBlank() || p.name.contains(query, true) || p.pid.toString().contains(query) || p.uid.toString().contains(query) }
            count.text = "显示 ${filtered.size} / ${items.size} 个进程；已选 ${selectedProcesses.size} 个"
            filtered.forEach { p ->
                val label = "${p.name}  · PID ${p.pid} · UID ${p.uid}\nRSS ${fmt(p.rssKb)} · PSS ${if (p.pssKb > 0) fmt(p.pssKb) else "未采集"} · Swap ${fmt(p.swapKb)}"
                rows.addView(CheckBox(this).apply {
                    text = label
                    textSize = 12f
                    isChecked = selectedProcesses.contains(p.identity)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedProcesses.add(p.identity) else selectedProcesses.remove(p.identity)
                        count.text = "显示 ${filtered.size} / ${items.size} 个进程；已选 ${selectedProcesses.size} 个"
                    }
                })
            }
        }
        refreshRows("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshRows(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        AlertDialog.Builder(this)
            .setTitle("搜索并选择目标进程（最多 500 个）")
            .setView(container)
            .setPositiveButton("完成") { _, _ -> updateSelectionSummary(); log("PROCESS_SELECT", "selected=${selectedProcesses.size}") }
            .setNeutralButton("清空选择") { _, _ -> selectedProcesses.clear(); refreshRows(search.text.toString()); updateSelectionSummary() }
            .setNegativeButton("取消", null).show()
    }

    private fun updateSelectionSummary() {
        val selected = latestSnapshot?.processes?.filter { selectedProcesses.contains(it.identity) }.orEmpty()
        selectionSummary.text = if (selected.isEmpty()) "尚未选择进程。选择仅用于后续诊断，不会触发任何回收或杀进程操作。" else
            "已选择 ${selected.size} 个进程：\n" + selected.joinToString("\n") { "${it.name} (PID ${it.pid}, UID ${it.uid}) · RSS ${fmt(it.rssKb)} · Swap ${fmt(it.swapKb)}" }
    }

    private fun runCapabilityProbe() {
        capabilityButton.isEnabled = false
        status.text = "正在只读检查内核接口…"
        executor.execute {
            val report = RootReader.probeCapabilities()
            latestCapabilities = report
            runOnUiThread {
                capabilityButton.isEnabled = true
                status.text = "内核能力检测完成。注意：发现接口不等于已证明可以安全地回收指定进程。\n${report.summary}"
                log("CAPABILITY_PROBE", report.summary.replace("\n", "; "))
                refreshLog()
            }
        }
    }

    private fun showReclaimNotReady() {
        AlertDialog.Builder(this).setTitle("回收功能尚未开放")
            .setMessage("当前版本只完成进程选择和内核接口探测。我们还没有在这台 Android 10 / Linux 4.4 设备上证明存在安全、可控、针对单个进程的页面回收接口。\n\n不会用 drop_caches、kill、force-stop 或冻结进程来冒充内存回收。")
            .setPositiveButton("明白", null).show()
    }

    private fun shareReport() {
        val snapshot = latestSnapshot
        val report = buildString {
            append("Process Memory Manager 诊断报告\n版本：0.2.0-alpha2\n生成时间：${now()}\n设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}\n\n")
            if (snapshot == null) append("尚无进程扫描数据。\n") else {
                append("[扫描概览]\n时间：${snapshot.time}\n进程数：${snapshot.processes.size}\nZRAM：${snapshot.zramSummary}\nSwap：${snapshot.swapSummary}\n\n")
                append("[进程列表]\n")
                snapshot.processes.sortedByDescending { it.pssKb }.forEach { p -> append("${p.name}\tPID=${p.pid}\tUID=${p.uid}\tstart=${p.startTicks}\tRSS=${p.rssKb}KB\tPSS=${p.pssKb}KB\tSwap=${p.swapKb}KB\tselected=${selectedProcesses.contains(p.identity)}\n") }
            }
            latestCapabilities?.let { append("\n[内核能力探测]\n${it.summary}\n") }
            append("\n[运行日志]\n")
            append(try { logFile.readText() } catch (e: Exception) { "读取日志失败：${e.message}" })
            append("\n[安全说明]\n本报告来自只读诊断。未执行进程回收、杀进程、冻结进程或 cgroup/Swap/ZRAM 写操作。Swap 字段不是 SwapPss；ZRAM 为全局统计。\n")
        }
        shareAsTextFile("PMM-diagnostic-${fileStamp()}.txt", report, "Process Memory Manager 完整诊断报告")
    }

    private fun shareLogFile() {
        val content = try { logFile.readText() } catch (e: Exception) { "读取日志失败：${e.message}" }
        shareAsTextFile("PMM-log-${fileStamp()}.txt", content, "Process Memory Manager 运行日志")
    }

    private fun shareAsTextFile(name: String, content: String, title: String) {
        try {
            val dir = File(cacheDir, "shared_reports").apply { mkdirs() }
            val file = File(dir, name)
            file.writeText(content, Charsets.UTF_8)
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "将诊断文件分享到…"))
            log("FILE_SHARE", "Shared $name")
        } catch (e: Exception) {
            status.text = "创建分享文件失败：${e.message}"
            log("FILE_SHARE_ERROR", e.toString())
        }
    }

    private fun log(event: String, message: String) {
        if (!::loggingEnabled.isInitialized || !loggingEnabled.isChecked) return
        try { logFile.appendText("${now()} [$event] ${message.replace('\n', ' ')}\n") } catch (_: Exception) { }
    }

    private fun refreshLog() { if (::logView.isInitialized) logView.text = try { logFile.readLines().takeLast(100).joinToString("\n").ifBlank { "暂无日志" } } catch (e: Exception) { "读取日志失败：${e.message}" } }
    private fun text(value: String, size: Int, bold: Boolean) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(0xFF0F172A.toInt()); setPadding(0, dp(5), 0, dp(5)); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun panelText(value: String) = TextView(this).apply { text = value; textSize = 13f; setTextColor(0xFF1E293B.toInt()); setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(0xFFFFFFFF.toInt()) }
    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun fileStamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private fun fmt(kb: Long): String = if (kb < 0) "-${fmt(-kb)}" else if (kb >= 1024) String.format(Locale.US, "%.1f MB", kb / 1024.0) else "$kb KB"
    private fun signed(kb: Long) = (if (kb >= 0) "+" else "") + fmt(kb)

    override fun onDestroy() { super.onDestroy(); executor.shutdownNow() }
}

data class ProcessInfo(val pid: Int, val uid: Int, val startTicks: String, val name: String, val rssKb: Long, val pssKb: Long, val swapKb: Long) {
    val identity: String get() = "$pid:$startTicks"
}
data class ScanSnapshot(val time: String, val processes: List<ProcessInfo>, val zramSummary: String, val swapSummary: String)
data class ScanResult(val snapshot: ScanSnapshot?, val error: String?, val elapsedMs: Long)
data class ShellResult(val exitCode: Int, val output: String, val timedOut: Boolean = false)
data class CapabilityReport(val summary: String)

object RootReader {
    @Volatile private var activeProcess: Process? = null

    fun run(command: String, timeoutSeconds: Long = 120): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            activeProcess = process
            val output = StringBuilder()
            val reader = thread(start = true, name = "pmm-shell-reader") {
                try { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> synchronized(output) { if (output.length < 2_000_000) output.append(line).append('\n') } } } } catch (_: Exception) { }
            }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
                reader.join(1500)
                ShellResult(-2, synchronized(output) { output.toString() } + "\n[PMM] 命令超过 ${timeoutSeconds}s，已终止。", true)
            } else {
                reader.join(3000)
                ShellResult(process.exitValue(), synchronized(output) { output.toString() })
            }
        } catch (e: Exception) { ShellResult(-1, e.toString()) }
        finally { activeProcess = null }
    }

    fun scan(detailed: Boolean, includeSystem: Boolean): ScanResult {
        val start = System.currentTimeMillis()
        val detailFlag = if (detailed) "1" else "0"
        val systemFlag = if (includeSystem) "1" else "0"
        val script = """
            echo 'META|BEGIN'
            if [ -r /sys/block/zram0/mm_stat ]; then echo "ZRAM|§(cat /sys/block/zram0/mm_stat 2>/dev/null)"; else echo 'ZRAM|unavailable'; fi
            echo 'SWAPS_BEGIN'; cat /proc/swaps 2>/dev/null; echo 'SWAPS_END'
            for d in /proc/[0-9]*; do
              [ -d "§d" ] || continue
              pid=§{d##*/}; [ -r "§d/status" ] || continue
              uid=§(awk '/^Uid:/{print §2; exit}' "§d/status" 2>/dev/null); [ -n "§uid" ] || uid=0
              if [ '$systemFlag' = '0' ] && [ "§uid" -lt 10000 ] 2>/dev/null; then continue; fi
              name=§(tr '\000' ' ' < "§d/cmdline" 2>/dev/null | cut -c1-180)
              [ -n "§name" ] || name=§(awk '/^Name:/{print §2; exit}' "§d/status" 2>/dev/null)
              rss=§(awk '/^VmRSS:/{print §2; exit}' "§d/status" 2>/dev/null); [ -n "§rss" ] || rss=0
              vswap=§(awk '/^VmSwap:/{print §2; exit}' "§d/status" 2>/dev/null); [ -n "§vswap" ] || vswap=0
              statline=§(cat "§d/stat" 2>/dev/null); rest=§(printf '%s' "§statline" | sed 's/^.*) //')
              set -- §rest; startticks=§{20}; [ -n "§startticks" ] || startticks=0
              pss=0; swapsm=§vswap
              if [ '$detailFlag' = '1' ] && [ -r "§d/smaps" ]; then
                vals=§(awk '/^Pss:/{p+=§2} /^Swap:/{s+=§2} END{printf "%d %d",p,s}' "§d/smaps" 2>/dev/null)
                set -- §vals; pss=§1; swapsm=§2; [ -n "§pss" ] || pss=0; [ -n "§swapsm" ] || swapsm=§vswap
              fi
              name=§(printf '%s' "§name" | tr '\t|\r\n' '____')
              echo "PROC|§pid|§uid|§startticks|§name|§rss|§pss|§swapsm"
            done
            echo 'META|END'
        """.trimIndent().replace('§', '$')
        val result = run(script, if (detailed) 240 else 90)
        if (result.exitCode != 0 || !result.output.contains("META|BEGIN") || result.timedOut)
            return ScanResult(null, "Root 扫描失败/超时（exit=${result.exitCode}）：${result.output.take(700)}", System.currentTimeMillis() - start)
        val processes = mutableListOf<ProcessInfo>()
        var zram = "unavailable"
        val swaps = StringBuilder(); var inSwaps = false
        result.output.lineSequence().forEach { line ->
            when {
                line.startsWith("ZRAM|") -> zram = parseZram(line.removePrefix("ZRAM|"))
                line == "SWAPS_BEGIN" -> inSwaps = true
                line == "SWAPS_END" -> inSwaps = false
                inSwaps -> if (line.isNotBlank()) swaps.append(line).append("; ")
                line.startsWith("PROC|") -> {
                    val a = line.split('|', limit = 8)
                    if (a.size == 8) {
                        val pid = a[1].toIntOrNull() ?: return@forEach
                        processes.add(ProcessInfo(pid, a[2].toIntOrNull() ?: 0, a[3], a[4].ifBlank { "[unknown]" }, a[5].toLongOrNull() ?: 0, a[6].toLongOrNull() ?: 0, a[7].toLongOrNull() ?: 0))
                    }
                }
            }
        }
        val snap = ScanSnapshot(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()), processes, zram, swaps.toString().ifBlank { "unavailable" })
        return ScanResult(snap, null, System.currentTimeMillis() - start)
    }

    fun probeCapabilities(): CapabilityReport {
        val script = """
            echo "KERNEL|§(uname -r)"
            echo "ARCH|§(uname -m)"
            if [ -r /proc/config.gz ]; then zcat /proc/config.gz 2>/dev/null | grep -E 'CONFIG_(SWAP|ZRAM|MEMCG|MEMCG_SWAP|ZRAM_WRITEBACK|ZSMALLOC)=' | sort; else echo 'CONFIG|/proc/config.gz unavailable'; fi
            echo "CGROUP_MOUNTS_BEGIN"
            grep -E 'cgroup|cgroup2' /proc/mounts 2>/dev/null
            echo "CGROUP_MOUNTS_END"
            for f in /sys/fs/cgroup/memory/memory.force_empty /sys/fs/cgroup/memory/memory.reclaim /sys/fs/cgroup/cgroup.controllers /proc/sys/vm/swappiness; do
              if [ -e "§f" ]; then echo "PATH|present|§f|readable=§( [ -r "§f" ] && echo yes || echo no )|writable=§( [ -w "§f" ] && echo yes || echo no )"; else echo "PATH|absent|§f"; fi
            done
            if [ -r /proc/kallsyms ]; then grep -m 3 -E 'process_madvise|madvise' /proc/kallsyms 2>/dev/null; else echo 'SYMBOLS|kallsyms unavailable'; fi
            if [ -r /sys/block/zram0/mm_stat ]; then echo "ZRAM_MM_STAT|§(cat /sys/block/zram0/mm_stat)"; fi
        """.trimIndent().replace('§', '$')
        val r = run(script, 30)
        val body = if (r.timedOut) "探测命令超时。\n${r.output}" else r.output.ifBlank { "无输出（exit=${r.exitCode}）" }
        val caution = "\n\n解读边界：以上仅表示接口/配置在文件系统中可见。不能据此推断可安全回收某个 PID 的匿名页；memory.force_empty 不是指定进程压缩按钮，swappiness 是系统级策略，process_madvise 还需检查内核实现与权限。当前版本没有执行任何写操作。"
        return CapabilityReport(body + caution)
    }

    private fun parseZram(raw: String): String {
        val n = raw.trim().split(Regex("\\s+")); if (n.size < 3) return raw
        val orig = n[0].toLongOrNull() ?: return raw; val compr = n[1].toLongOrNull() ?: return raw; val mem = n[2].toLongOrNull() ?: return raw
        val ratio = if (compr > 0) orig.toDouble() / compr else 0.0
        return "原始 ${orig / 1024 / 1024} MB；压缩数据 ${compr / 1024 / 1024} MB；实际分配 ${mem / 1024 / 1024} MB；压缩比约 ${String.format(Locale.US, "%.2f", ratio)}:1（全局）"
    }
}
