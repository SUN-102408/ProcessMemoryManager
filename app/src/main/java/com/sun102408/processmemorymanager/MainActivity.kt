package com.sun102408.processmemorymanager

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var results: TextView
    private lateinit var logView: TextView
    private lateinit var scanButton: Button
    private lateinit var rootButton: Button
    private lateinit var showSystem: Switch
    private lateinit var detailedScan: Switch
    private lateinit var compareSnapshots: Switch
    private lateinit var loggingEnabled: Switch
    private lateinit var debugLogging: Switch
    private var lastSnapshot: ScanSnapshot? = null
    private var latestSnapshot: ScanSnapshot? = null

    private val logFile get() = File(filesDir, "pmm.log")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        log("APP_START", "Process Memory Manager 0.1.0-alpha1 started")
        status.text = "尚未检查 Root 权限。此版本仅执行只读检测。"
        refreshLog()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(0xFFF8FAFC.toInt())
        }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(text("Process Memory Manager", 24, true))
        body.addView(text("第一版 · 只读诊断模式", 14, false).apply { setTextColor(0xFF64748B.toInt()) })
        body.addView(gap(12))
        status = panelText("状态：等待检测")
        body.addView(status)
        rootButton = Button(this).apply {
            text = "检查 Root 权限"
            setOnClickListener { checkRoot() }
        }
        body.addView(rootButton)
        scanButton = Button(this).apply {
            text = "扫描进程内存（只读）"
            isEnabled = false
            setOnClickListener { runScan() }
        }
        body.addView(scanButton)
        body.addView(gap(8))
        body.addView(text("功能开关", 18, true))
        showSystem = addSwitch(body, "显示系统进程", true, "关闭后隐藏部分系统 UID 进程，仅影响列表显示")
        detailedScan = addSwitch(body, "详细 PSS 扫描", true, "读取 /proc/PID/smaps；可能较慢，仅在手动扫描时运行")
        compareSnapshots = addSwitch(body, "对比上一次快照", true, "计算 PSS / SwapPss 变化，不代表单进程 ZRAM 压缩率")
        loggingEnabled = addSwitch(body, "记录运行日志", true, "记录启动、Root、扫描结果和异常")
        debugLogging = addSwitch(body, "详细调试日志", false, "记录额外诊断信息；日志可能增长较快")
        body.addView(gap(10))
        body.addView(text("内存概览", 18, true))
        summary = panelText("尚无扫描数据")
        body.addView(summary)
        body.addView(text("进程列表（按 PSS 降序）", 18, true))
        results = panelText("点击上方按钮开始扫描。")
        body.addView(results)
        body.addView(gap(10))
        body.addView(text("运行日志", 18, true))
        logView = panelText("日志尚未加载")
        body.addView(logView)
        val logButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logButtons.addView(Button(this).apply { text = "刷新日志"; setOnClickListener { refreshLog() } }, LinearLayout.LayoutParams(0, -2, 1f))
        logButtons.addView(Button(this).apply { text = "分享日志"; setOnClickListener { shareLog() } }, LinearLayout.LayoutParams(0, -2, 1f))
        logButtons.addView(Button(this).apply { text = "清空日志"; setOnClickListener { log("LOG_CLEAR", "User requested log clear"); logFile.writeText(""); log("LOG_CLEAR", "Log cleared"); refreshLog() } }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(logButtons)
        body.addView(text("安全说明：本版本不会杀进程、冻结进程、修改 cgroup、调整 Swap/ZRAM 或执行内存回收。", 12, false).apply { setTextColor(0xFFB45309.toInt()) })
        setContentView(root)
    }

    private fun addSwitch(parent: LinearLayout, title: String, initial: Boolean, hint: String): Switch {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(5), dp(10), dp(5)); setBackgroundColor(0xFFFFFFFF.toInt()) }
        val sw = Switch(this).apply { text = title; isChecked = initial }
        box.addView(sw)
        box.addView(text(hint, 11, false).apply { setTextColor(0xFF64748B.toInt()) })
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        return sw
    }

    private fun checkRoot() {
        rootButton.isEnabled = false
        status.text = "正在请求 Root 授权…"
        log("ROOT_CHECK", "Requesting su identity")
        executor.execute {
            val result = RootReader.run("id")
            runOnUiThread {
                rootButton.isEnabled = true
                if (result.exitCode == 0 && result.output.contains("uid=0")) {
                    status.text = "Root 正常：${result.output.trim()}"
                    scanButton.isEnabled = true
                    log("ROOT_OK", result.output.trim())
                } else {
                    status.text = "未获得 Root 权限。请检查 Kitsune Mask 授权。\n${result.output}"
                    scanButton.isEnabled = false
                    log("ROOT_FAIL", "exit=${result.exitCode}; ${result.output}")
                }
                refreshLog()
            }
        }
    }

    private fun runScan() {
        scanButton.isEnabled = false
        status.text = "正在扫描……请稍候。详细 smaps 扫描可能需要一些时间。"
        val detailed = detailedScan.isChecked
        val includeSystem = showSystem.isChecked
        log("SCAN_BEGIN", "detailed=$detailed; showSystem=$includeSystem")
        executor.execute {
            val result = RootReader.scan(detailed)
            val snapshot = result.snapshot
            runOnUiThread {
                scanButton.isEnabled = true
                if (result.error != null || snapshot == null) {
                    status.text = "扫描失败：${result.error ?: "未知错误"}"
                    log("SCAN_ERROR", result.error ?: "unknown")
                } else {
                    lastSnapshot = latestSnapshot
                    latestSnapshot = snapshot
                    val visible = if (includeSystem) snapshot.processes else snapshot.processes.filter { it.uid >= 10000 }
                    val totalPss = visible.sumOf { it.pssKb }
                    summary.text = buildString {
                        append("扫描时间：${snapshot.time}\n")
                        append("进程数量：${visible.size}\n")
                        append("列表 PSS 合计：${fmt(totalPss)}\n")
                        append("全局 ZRAM：${snapshot.zramSummary}\n")
                        append("Swap：${snapshot.swapSummary}\n")
                        append("注意：列表 PSS 合计不等于系统总 RAM 使用量；ZRAM 是全局数据。")
                    }
                    val previous = lastSnapshot
                    results.text = buildString {
                        if (compareSnapshots.isChecked && previous != null) append("与上次扫描对比（按 PID + 启动时间匹配）\n\n")
                        visible.sortedByDescending { it.pssKb }.take(100).forEach { p ->
                            append("${p.name}  [PID ${p.pid}, UID ${p.uid}]\n")
                            append("  RSS ${fmt(p.rssKb)} | PSS ${fmt(p.pssKb)} | Swap ${fmt(p.swapKb)}\n")
                            if (compareSnapshots.isChecked && previous != null) {
                                val old = previous.processes.firstOrNull { it.pid == p.pid && it.startTicks == p.startTicks }
                                if (old != null) {
                                    val delta = p.pssKb - old.pssKb
                                    val pct = if (old.pssKb > 0) delta * 100.0 / old.pssKb else 0.0
                                    append("  PSS 变化 ${if (delta >= 0) "+" else ""}${fmt(delta)} (${String.format(Locale.US, "%.1f", pct)}%) | Swap 变化 ${signed(p.swapKb - old.swapKb)}\n")
                                } else append("  新进程 / 上次未匹配\n")
                            }
                            append("\n")
                        }
                    }
                    status.text = "扫描完成。只读取数据，没有修改任何系统状态。"
                    log("SCAN_OK", "processes=${snapshot.processes.size}; zram=${snapshot.zramSummary}; swap=${snapshot.swapSummary}")
                    if (debugLogging.isChecked) log("SCAN_DEBUG", "elapsed=${result.elapsedMs}ms; detailed=$detailed")
                }
                refreshLog()
            }
        }
    }

    private fun log(event: String, message: String) {
        if (!::loggingEnabled.isInitialized || !loggingEnabled.isChecked) return
        try {
            logFile.appendText("${now()} [$event] ${message.replace('\n', ' ')}\n")
        } catch (_: Exception) { }
    }

    private fun shareLog() {
        val content = try { logFile.readText() } catch (e: Exception) { "读取日志失败：${e.message}" }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Process Memory Manager 日志")
            putExtra(Intent.EXTRA_TEXT, content.ifBlank { "暂无日志" })
        }
        startActivity(Intent.createChooser(intent, "分享运行日志"))
        log("LOG_SHARE", "Log share sheet opened")
    }

    private fun refreshLog() {
        if (!::logView.isInitialized) return
        logView.text = try { logFile.readLines().takeLast(120).joinToString("\n").ifBlank { "暂无日志" } } catch (e: Exception) { "读取日志失败：${e.message}" }
    }

    private fun text(value: String, size: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(0xFF0F172A.toInt()); setPadding(0, dp(5), 0, dp(5))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun panelText(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTextColor(0xFF1E293B.toInt()); setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(0xFFFFFFFF.toInt())
    }
    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun fmt(kb: Long): String = if (kb < 0) "-${fmt(-kb)}" else if (kb >= 1024) String.format(Locale.US, "%.1f MB", kb / 1024.0) else "$kb KB"
    private fun signed(kb: Long) = (if (kb >= 0) "+" else "") + fmt(kb)

    override fun onDestroy() { super.onDestroy(); executor.shutdown() }
}

data class ProcessInfo(val pid: Int, val uid: Int, val startTicks: String, val name: String, val rssKb: Long, val pssKb: Long, val swapKb: Long)
data class ScanSnapshot(val time: String, val processes: List<ProcessInfo>, val zramSummary: String, val swapSummary: String)
data class ScanResult(val snapshot: ScanSnapshot?, val error: String?, val elapsedMs: Long)
data class ShellResult(val exitCode: Int, val output: String)

object RootReader {
    fun run(command: String): ShellResult {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val code = p.waitFor()
            ShellResult(code, if (out.isNotBlank()) out else err)
        } catch (e: Exception) { ShellResult(-1, e.toString()) }
    }

    fun scan(detailed: Boolean): ScanResult {
        val start = System.currentTimeMillis()
        // Read-only shell script. No writes to /proc, /sys, cgroups, swap or system properties.
        val detailFlag = if (detailed) "1" else "0"
        val script = """
            echo 'META|BEGIN'
            if [ -r /sys/block/zram0/mm_stat ]; then echo "ZRAM|§(cat /sys/block/zram0/mm_stat 2>/dev/null)"; else echo 'ZRAM|unavailable'; fi
            echo 'SWAPS_BEGIN'
            cat /proc/swaps 2>/dev/null
            echo 'SWAPS_END'
            for d in /proc/[0-9]*; do
              [ -d "§d" ] || continue
              pid=§{d##*/}
              [ -r "§d/status" ] || continue
              name=§(tr '\n' ' ' < "§d/cmdline" 2>/dev/null | cut -c1-180)
              [ -n "§name" ] || name=§(awk '/^Name:/{print §2; exit}' "§d/status" 2>/dev/null)
              uid=§(awk '/^Uid:/{print §2; exit}' "§d/status" 2>/dev/null)
              rss=§(awk '/^VmRSS:/{print §2; exit}' "§d/status" 2>/dev/null); [ -n "§rss" ] || rss=0
              vswap=§(awk '/^VmSwap:/{print §2; exit}' "§d/status" 2>/dev/null); [ -n "§vswap" ] || vswap=0
              statline=§(cat "§d/stat" 2>/dev/null)
              rest=§(printf '%s' "§statline" | sed 's/^.*) //')
              set -- §rest; startticks=§{20}; [ -n "§startticks" ] || startticks=0
              pss=0; swapsm=§vswap
              if [ '${detailFlag}' = '1' ] && [ -r "§d/smaps" ]; then
                vals=§(awk '/^Pss:/{p+=§2} /^Swap:/{s+=§2} END{printf "%d %d",p,s}' "§d/smaps" 2>/dev/null)
                set -- §vals; pss=§1; swapsm=§2
                [ -n "§pss" ] || pss=0; [ -n "§swapsm" ] || swapsm=§vswap
              fi
              name=§(printf '%s' "§name" | tr '\t|\r\n' '____')
              echo "PROC|§pid|§{uid:-0}|§startticks|§name|§rss|§pss|§swapsm"
            done
            echo 'META|END'
        """.trimIndent().replace('§', '$')
        val result = run(script)
        if (result.exitCode != 0 || !result.output.contains("META|BEGIN")) return ScanResult(null, "Root 扫描命令失败（exit=${result.exitCode}）：${result.output.take(500)}", System.currentTimeMillis() - start)
        val processes = mutableListOf<ProcessInfo>()
        var zram = "unavailable"
        val swaps = StringBuilder()
        var inSwaps = false
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

    private fun parseZram(raw: String): String {
        val n = raw.trim().split(Regex("\\s+"))
        if (n.size < 3) return raw
        val orig = n[0].toLongOrNull() ?: return raw
        val compr = n[1].toLongOrNull() ?: return raw
        val mem = n[2].toLongOrNull() ?: return raw
        val ratio = if (compr > 0) orig.toDouble() / compr else 0.0
        return "原始 ${orig / 1024 / 1024} MB；压缩数据 ${compr / 1024 / 1024} MB；实际分配 ${mem / 1024 / 1024} MB；压缩比约 ${String.format(Locale.US, "%.2f", ratio)}:1（全局）"
    }
}
