package com.sun102408.processmemorymanager

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val BG = Color.rgb(9, 16, 30)
private val CARD = Color.rgb(20, 31, 51)
private val BLUE = Color.rgb(56, 165, 255)
private val WHITE = Color.rgb(235, 243, 255)
private val MUTED = Color.rgb(145, 163, 188)

class MainActivity : Activity() {
    private lateinit var rootStatus: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var scanButton: Button
    private lateinit var summary: TextView
    private lateinit var includeSystem: Switch
    private lateinit var detailed: Switch
    private lateinit var includeLog: Switch
    private lateinit var adapter: ProcessAdapter
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var snapshot: Snapshot? = null
    private var lastError = ""
    private var lastDuration = 0L
    private var lastStatus = "尚未扫描"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildUi()
    }

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            setBackgroundColor(BG)
        }
        page.addView(text("PMM", 28, true, WHITE))
        page.addView(text("PROCESS MEMORY MANAGER  ·  0.3.0-alpha1", 10, true, BLUE))
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(gap(12))
        content.addView(card("设备与权限") {
            rootStatus = text("Root 尚未检查", 13, false, MUTED)
            addView(rootStatus)
            addView(button("检查 Root 权限") { checkRoot() })
        })
        content.addView(gap(10))
        content.addView(card("扫描控制") {
            includeSystem = Switch(this@MainActivity).apply {
                text = "包含系统进程"; isChecked = false; setTextColor(WHITE)
            }
            detailed = Switch(this@MainActivity).apply {
                text = "详细 PSS（读取 smaps，速度较慢）"; isChecked = false; setTextColor(WHITE)
            }
            addView(includeSystem)
            addView(detailed)
            scanButton = button("开始扫描") { startScan() }.apply { isEnabled = false }
            addView(scanButton)
            progressBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progressTintList = android.content.res.ColorStateList.valueOf(BLUE)
            }
            addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)))
            progressText = text("等待扫描", 12, false, MUTED)
            addView(progressText)
        })
        content.addView(gap(10))
        content.addView(card("内存概览") {
            summary = text("尚无扫描数据", 13, false, WHITE)
            addView(summary)
        })
        content.addView(gap(10))
        content.addView(card("进程列表") {
            val search = EditText(this@MainActivity).apply {
                hint = "搜索进程名 / PID / UID"
                setSingleLine(true)
                setTextColor(WHITE)
                setHintTextColor(MUTED)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundColor(Color.rgb(29, 44, 67))
            }
            addView(search)
            val recycler = RecyclerView(this@MainActivity).apply {
                layoutManager = LinearLayoutManager(this@MainActivity)
                isNestedScrollingEnabled = false
            }
            adapter = ProcessAdapter()
            recycler.adapter = adapter
            addView(recycler, LinearLayout.LayoutParams(-1, dp(420)))
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { adapter.filter(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        })
        content.addView(gap(10))
        content.addView(card("诊断与报告") {
            includeLog = Switch(this@MainActivity).apply {
                text = "报告附带运行摘要"; isChecked = true; setTextColor(WHITE)
            }
            addView(includeLog)
            addView(button("复制完整诊断报告") { copyReport() })
            addView(button("分享纯文字诊断报告") { shareReport() })
            addView(button("只读检测内核能力") { probeKernel() })
            addView(text("安全边界：本版本只读，不杀进程、不冻结进程、不执行页面回收，也不修改 /proc、/sys、cgroup 或 Swap。", 11, false, MUTED))
        })
        setContentView(page)
    }

    private fun card(title: String, body: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackgroundColor(CARD)
        addView(text(title, 16, true, WHITE))
        addView(gap(7))
        body()
    }

    private fun text(value: String, size: Int, bold: Boolean, color: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(5))
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun gap(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun checkRoot() {
        rootStatus.text = "正在请求 Root 授权…"
        executor.execute {
            val result = Root.run("id", 15)
            runOnUiThread {
                val ok = result.code == 0 && result.output.contains("uid=0")
                rootStatus.text = if (ok) "Root 已授权：" + result.output.trim() else "Root 检查失败：" + result.output.take(250)
                scanButton.isEnabled = ok
            }
        }
    }

    private fun startScan() {
        scanButton.isEnabled = false
        progressBar.isIndeterminate = true
        progressText.text = "正在枚举进程…"
        lastError = ""
        val detailedMode = detailed.isChecked
        val systemMode = includeSystem.isChecked
        executor.execute {
            val result = Root.scan(detailedMode, systemMode) { done, total, stage, current ->
                runOnUiThread {
                    progressBar.isIndeterminate = false
                    progressBar.max = total.coerceAtLeast(1)
                    progressBar.progress = done.coerceAtMost(progressBar.max)
                    progressText.text = "$stage · $done / $total" + if (current.isNotBlank()) " · $current" else ""
                }
            }
            runOnUiThread {
                scanButton.isEnabled = true
                progressBar.isIndeterminate = false
                lastDuration = result.duration
                if (result.error.isNotBlank() || result.data == null) {
                    lastError = result.error.ifBlank { "扫描未返回有效数据" }
                    lastStatus = "扫描失败"
                    progressText.text = "失败 · " + result.duration + " ms"
                    rootStatus.text = lastError
                } else {
                    snapshot = result.data
                    lastStatus = "扫描完成"
                    val s = result.data
                    val pss = if (detailedMode) formatKb(s.processes.filter { it.pssKb >= 0 }.sumOf { it.pssKb }) else "快速模式未采集"
                    summary.text = "时间：" + s.time + "\n耗时：" + result.duration + " ms\n进程数：" + s.processes.size +
                        "\nPSS 合计：" + pss + "\nRSS 合计：" + formatKb(s.processes.sumOf { it.rssKb }) +
                        "\n进程 Swap 合计：" + formatKb(s.processes.sumOf { it.swapKb }) +
                        "\nZRAM（全局 mm_stat）：" + s.zram + "\nSwap 设备：" + s.swaps
                    adapter.submit(s.processes)
                    rootStatus.text = "扫描完成。数据只读，没有修改系统状态。"
                    progressText.text = "完成 · " + s.processes.size + " 个进程 · " + result.duration + " ms"
                }
            }
        }
    }

    private fun makeReport(): String = buildString {
        append("Process Memory Manager 诊断报告\n版本：0.3.0-alpha1\n")
        append("设备：" + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + "\n")
        append("生成时间：" + now() + "\n状态：" + lastStatus + "\n耗时：" + lastDuration + " ms\n\n")
        if (lastError.isNotBlank()) append("[错误]\n" + lastError + "\n\n")
        val s = snapshot
        if (s == null) append("尚无成功扫描快照。\n") else {
            append("[概览]\n时间：" + s.time + "\n进程数：" + s.processes.size + "\nZRAM（全局）：" + s.zram + "\nSwap：" + s.swaps + "\n\n")
            append("[进程列表]\n")
            s.processes.forEach {
                append(it.name + "\tPID=" + it.pid + "\tUID=" + it.uid + "\tRSS=" + it.rssKb + "KB\tPSS=" +
                    (if (it.pssKb >= 0) it.pssKb.toString() else "未采集") + "\tSwap=" + it.swapKb + "KB\n")
            }
        }
        if (includeLog.isChecked) append("\n[运行摘要]\n" + lastStatus + "；最近扫描耗时 " + lastDuration + " ms\n")
        append("\n安全说明：只读诊断；未杀进程、冻结进程或执行内存回收。PSS 仅在详细模式采集，ZRAM 为全局统计。\n")
    }

    private fun copyReport() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PMM report", makeReport()))
        Toast.makeText(this, "完整报告已复制为纯文字", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PMM 0.3.0-alpha1 诊断报告")
            putExtra(Intent.EXTRA_TEXT, makeReport())
        }
        startActivity(Intent.createChooser(send, "分享纯文字报告"))
    }

    private fun probeKernel() {
        executor.execute {
            val r = Root.run("echo KERNEL=$(uname -r); echo ARCH=$(uname -m); echo ZRAM=$(cat /sys/block/zram0/mm_stat 2>/dev/null); cat /proc/swaps 2>/dev/null; grep -E 'cgroup|cgroup2' /proc/mounts 2>/dev/null", 25)
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("只读内核信息").setMessage(r.output.take(10000))
                    .setPositiveButton("关闭", null).show()
            }
        }
    }

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun formatKb(kb: Long) = if (kb >= 1024) String.format(Locale.US, "%.1f MB", kb / 1024.0) else "$kb KB"

    private inner class ProcessAdapter : RecyclerView.Adapter<ProcessAdapter.Holder>() {
        private var all: List<ProcessItem> = emptyList()
        private var visible: List<ProcessItem> = emptyList()
        inner class Holder(val container: LinearLayout) : RecyclerView.ViewHolder(container)
        fun submit(items: List<ProcessItem>) {
            all = items.sortedByDescending { if (it.pssKb >= 0) it.pssKb else it.rssKb }
            filter("")
        }
        fun filter(query: String) {
            visible = all.filter { it.name.contains(query, true) || it.pid.toString().contains(query) || it.uid.toString().contains(query) }
            notifyDataSetChanged()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(CARD)
            }
            return Holder(box)
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val p = visible[position]
            holder.container.removeAllViews()
            holder.container.addView(text(p.name + "  ·  PID " + p.pid, 14, true, WHITE))
            holder.container.addView(text("UID " + p.uid + "  |  RSS " + formatKb(p.rssKb) +
                "  |  PSS " + (if (p.pssKb >= 0) formatKb(p.pssKb) else "未采集") +
                "  |  Swap " + formatKb(p.swapKb), 11, false, MUTED))
        }
        override fun getItemCount() = visible.size
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}

private data class ProcessItem(val pid: Int, val uid: Int, val startTicks: String, val name: String, val rssKb: Long, val pssKb: Long, val swapKb: Long)
private data class Snapshot(val time: String, val processes: List<ProcessItem>, val zram: String, val swaps: String)
private data class ScanResult(val data: Snapshot?, val error: String, val duration: Long)
private data class CommandResult(val code: Int, val output: String)

private object Root {
    fun run(command: String, timeoutSeconds: Long): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> synchronized(output) { if (output.length < 1000000) output.append(line).append('\n') } }
                }
            }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
                reader.join(1000)
                CommandResult(-2, output.toString() + "\n[PMM] timeout")
            } else {
                reader.join(1500)
                CommandResult(process.exitValue(), output.toString())
            }
        } catch (e: Exception) {
            CommandResult(-1, e.toString())
        }
    }

    fun scan(detailed: Boolean, includeSystem: Boolean, onProgress: (Int, Int, String, String) -> Unit): ScanResult {
        val started = System.currentTimeMillis()
        val detailFlag = if (detailed) "1" else "0"
        val systemFlag = if (includeSystem) "1" else "0"
        val script = """
            DETAIL=$detailFlag
            INCLUDE_SYSTEM=$systemFlag
            total=0
            for x in /proc/[0-9]*; do [ -d "\${'$'}x" ] && total=\${'$'}((total+1)); done
            echo "TOTAL|\${'$'}total"
            echo "ZRAM|\${'$'}(cat /sys/block/zram0/mm_stat 2>/dev/null)"
            echo "SWAPS_BEGIN"
            cat /proc/swaps 2>/dev/null
            echo "SWAPS_END"
            done_count=0
            for x in /proc/[0-9]*; do
              [ -d "\${'$'}x" ] || continue
              pid=\${'$'}{x##*/}
              status="\${'$'}x/status"
              if [ ! -r "\${'$'}status" ]; then
                done_count=\${'$'}((done_count+1))
                echo "PROGRESS|\${'$'}done_count|\${'$'}total|读取进程|PID \${'$'}pid"
                continue
              fi
              vals=\${'$'}(awk '/^Uid:/{u=\${'$'}2} /^VmRSS:/{r=\${'$'}2} /^VmSwap:/{s=\${'$'}2} /^Name:/{n=\${'$'}2} END{printf "%s|%s|%s|%s",u,r,s,n}' "\${'$'}status" 2>/dev/null)
              oldifs=\${'$'}IFS
              IFS='|'
              set -- \${'$'}vals
              IFS=\${'$'}oldifs
              uid=\${'$'}{1:-0}; rss=\${'$'}{2:-0}; vswap=\${'$'}{3:-0}; name=\${'$'}{4:-unknown}
              case "\${'$'}uid" in ''|*[!0-9]*) uid=0;; esac
              case "\${'$'}rss" in ''|*[!0-9]*) rss=0;; esac
              case "\${'$'}vswap" in ''|*[!0-9]*) vswap=0;; esac
              if [ "\${'$'}INCLUDE_SYSTEM" = "0" ] && [ "\${'$'}uid" -lt 10000 ] 2>/dev/null; then
                done_count=\${'$'}((done_count+1))
                echo "PROGRESS|\${'$'}done_count|\${'$'}total|过滤系统进程|PID \${'$'}pid"
                continue
              fi
              statline=\${'$'}(cat "\${'$'}x/stat" 2>/dev/null)
              startticks=\${'$'}(printf '%s\\n' "\${'$'}statline" | awk '{sub(/^.*\\) /,""); print \${'$'}20}')
              [ -n "\${'$'}startticks" ] || startticks=0
              pss=-1
              swap=\${'$'}vswap
              if [ "\${'$'}DETAIL" = "1" ] && [ -r "\${'$'}x/smaps" ]; then
                mem=\${'$'}(awk '/^Pss:/{p+=\${'$'}2} /^Swap:/{s+=\${'$'}2} END{printf "%d|%d",p,s}' "\${'$'}x/smaps" 2>/dev/null)
                oldifs=\${'$'}IFS; IFS='|'; set -- \${'$'}mem; IFS=\${'$'}oldifs
                pss=\${'$'}{1:--1}; swap=\${'$'}{2:-\${'$'}vswap}
              fi
              name=\${'$'}(printf '%s' "\${'$'}name" | tr '|\\t\\r\\n' '____')
              echo "PROC|\${'$'}pid|\${'$'}uid|\${'$'}startticks|\${'$'}name|\${'$'}rss|\${'$'}pss|\${'$'}swap"
              done_count=\${'$'}((done_count+1))
              if [ "\${'$'}DETAIL" = "1" ]; then stage="详细 PSS"; else stage="基础扫描"; fi
              echo "PROGRESS|\${'$'}done_count|\${'$'}total|\${'$'}stage|PID \${'$'}pid"
            done
            echo "SCAN_END"
        """.trimIndent()

        val process = try {
            ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return ScanResult(null, e.toString(), System.currentTimeMillis() - started)
        }
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        var total = 0
        val reader = thread(name = "pmm-stream-reader") {
            try {
                process.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        if (lines.size < 100000) lines.add(line)
                        when {
                            line.startsWith("TOTAL|") -> total = line.substringAfter('|').toIntOrNull() ?: 0
                            line.startsWith("PROGRESS|") -> {
                                val fields = line.split('|', limit = 5)
                                if (fields.size >= 4) {
                                    onProgress(fields[1].toIntOrNull() ?: 0, total, fields[3], fields.getOrElse(4) { "" })
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        val finished = process.waitFor(if (detailed) 300 else 120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            reader.join(2000)
            return ScanResult(null, "扫描超过时间限制（详细模式 300 秒 / 快速模式 120 秒）", System.currentTimeMillis() - started)
        }
        reader.join(2000)
        val output = lines.toList()
        if (process.exitValue() != 0 || "SCAN_END" !in output) {
            return ScanResult(null, output.takeLast(35).joinToString("\n"), System.currentTimeMillis() - started)
        }
        var zram = "不可用"
        val swaps = StringBuilder()
        var inSwaps = false
        val processes = mutableListOf<ProcessItem>()
        output.forEach { line ->
            when {
                line.startsWith("ZRAM|") -> zram = line.substringAfter('|').ifBlank { "不可用" }
                line == "SWAPS_BEGIN" -> inSwaps = true
                line == "SWAPS_END" -> inSwaps = false
                inSwaps -> if (line.isNotBlank()) swaps.append(line).append("; ")
                line.startsWith("PROC|") -> {
                    val f = line.split('|', limit = 8)
                    if (f.size == 8) processes.add(ProcessItem(
                        f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: 0, f[3], f[4],
                        f[5].toLongOrNull() ?: 0, f[6].toLongOrNull() ?: -1, f[7].toLongOrNull() ?: 0
                    ))
                }
            }
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return ScanResult(Snapshot(time, processes, zram, swaps.toString().ifBlank { "不可用" }), "", System.currentTimeMillis() - started)
    }
}
