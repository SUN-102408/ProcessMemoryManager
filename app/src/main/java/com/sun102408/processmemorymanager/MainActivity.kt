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
    private lateinit var reclaimStatus: TextView
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
        page.addView(text("PROCESS MEMORY MANAGER  ·  0.3.0-alpha3", 10, true, BLUE))
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
                isNestedScrollingEnabled = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN,
                        android.view.MotionEvent.ACTION_MOVE ->
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL ->
                            view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
            }
            adapter = ProcessAdapter()
            recycler.adapter = adapter

            val controls = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val sortSpinner = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("按 RSS 排序", "按 PSS 排序", "按 Swap 排序"))
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        this@MainActivity.adapter.setSort(position)
                    }
                }
            }
            val uidSpinner = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("全部进程", "应用 UID（≥10000）", "系统 UID（<10000）"))
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        this@MainActivity.adapter.setUidFilter(position)
                    }
                }
            }
            controls.addView(sortSpinner, LinearLayout.LayoutParams(0, dp(48), 1f))
            controls.addView(uidSpinner, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(controls)
            addView(text("提示：在下方列表区域内上下滑动，可独立浏览全部进程。", 10, false, MUTED))
            addView(recycler, LinearLayout.LayoutParams(-1, dp(560)))
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { adapter.filter(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        })
        content.addView(gap(10))
        content.addView(card("指定进程内存回收（实验能力）") {
            reclaimStatus = text(
                "尚未检测。此功能必须由内核提供 process_madvise + MADV_PAGEOUT 等接口；不支持时不会用 drop_caches 等全局操作冒充进程级压缩。",
                12, false, MUTED
            )
            addView(reclaimStatus)
            addView(button("检测本机进程回收能力") { checkReclaimSupport() })
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
            addView(text("安全边界：不杀进程、不冻结进程；仅在内核明确支持进程级回收时才考虑启用。不会使用 drop_caches、memory.force_empty 或修改全局 Swap 参数代替。", 11, false, MUTED))
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
                    val privateDirty = if (detailedMode) formatKb(s.processes.filter { it.privateDirtyKb >= 0 }.sumOf { it.privateDirtyKb }) else "未采集"
                    val sharedDirty = if (detailedMode) formatKb(s.processes.filter { it.sharedDirtyKb >= 0 }.sumOf { it.sharedDirtyKb }) else "未采集"
                    summary.text = "时间：" + s.time + "\n耗时：" + result.duration + " ms\n进程数：" + s.processes.size +
                        "\nPSS 合计：" + pss + "\nPrivate Dirty 合计：" + privateDirty +
                        "\nShared Dirty 合计：" + sharedDirty + "\nRSS 合计：" + formatKb(s.processes.sumOf { it.rssKb }) +
                        "\n进程 Swap 合计：" + formatKb(s.processes.sumOf { it.swapKb }) +
                        "\nZRAM（全局）：" + formatZram(s.zram) + "\nSwap 设备：" + s.swaps
                    adapter.submit(s.processes)
                    rootStatus.text = "扫描完成。数据只读，没有修改系统状态。"
                    progressText.text = "完成 · " + s.processes.size + " 个进程 · " + result.duration + " ms"
                }
            }
        }
    }

    private fun makeReport(): String = buildString {
        append("Process Memory Manager 诊断报告\n版本：0.3.0-alpha3\n")
        append("设备：" + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + "\n")
        append("生成时间：" + now() + "\n状态：" + lastStatus + "\n耗时：" + lastDuration + " ms\n\n")
        if (lastError.isNotBlank()) append("[错误]\n" + lastError + "\n\n")
        val s = snapshot
        if (s == null) append("尚无成功扫描快照。\n") else {
            append("[概览]\n时间：" + s.time + "\n进程数：" + s.processes.size + "\nZRAM（全局）：" + formatZram(s.zram) + "\nSwap：" + s.swaps + "\n\n")
            append("[进程列表]\n")
            s.processes.forEach {
                append(it.name + "\tPID=" + it.pid + "\tUID=" + it.uid + "\tRSS=" + it.rssKb + "KB\tPSS=" +
                    (if (it.pssKb >= 0) it.pssKb.toString() else "未采集") +
                    "KB\tPrivateDirty=" + (if (it.privateDirtyKb >= 0) it.privateDirtyKb.toString() else "未采集") +
                    "KB\tSharedDirty=" + (if (it.sharedDirtyKb >= 0) it.sharedDirtyKb.toString() else "未采集") +
                    "KB\tSwap=" + it.swapKb + "KB\n")
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
            putExtra(Intent.EXTRA_SUBJECT, "PMM 0.3.0-alpha3 诊断报告")
            putExtra(Intent.EXTRA_TEXT, makeReport())
        }
        startActivity(Intent.createChooser(send, "分享纯文字报告"))
    }

    private fun checkReclaimSupport() {
        reclaimStatus.text = "正在读取内核版本与相关接口…"
        executor.execute {
            val r = Root.run(
                "echo KERNEL=\$(uname -r); echo ARCH=\$(uname -m); " +
                    "echo PROC_RECLAIM=\$(if [ -e /proc/self/reclaim ]; then echo yes; else echo no; fi); " +
                    "echo PROCESS_MADVISE=requires_kernel_5.10_or_vendor_backport; " +
                    "echo MADV_PAGEOUT=requires_kernel_5.4_or_vendor_backport",
                20
            )
            runOnUiThread {
                val kernel = r.output.lineSequence().firstOrNull { it.startsWith("KERNEL=") }?.substringAfter("=") ?: "unknown"
                val majorMinor = Regex("^(\\d+)\\.(\\d+)").find(kernel)
                val major = majorMinor?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val minor = majorMinor?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                val upstreamOld = major < 5 || (major == 5 && minor < 10)
                reclaimStatus.text = if (upstreamOld) {
                    "检测到内核 $kernel。上游 Linux 的 process_madvise 从 5.10 引入，MADV_PAGEOUT 从 5.4 引入；此内核版本不具备上游实现。厂商回移植情况尚未实测，因此本机暂不开放进程级回收，避免误操作。\n\n" + r.output
                } else {
                    "检测到内核 $kernel。版本满足上游接口引入门槛，但这不等于 syscall 一定可用；下一步仍需做无副作用 syscall 探测与目标进程验证。当前尚未执行任何回收。\n\n" + r.output
                }
            }
        }
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

    private fun formatZram(raw: String): String {
        val values = raw.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (values.size < 3) return raw.ifBlank { "不可用" }
        val original = values[0]
        val compressed = values[1]
        val used = values[2]
        val ratio = if (compressed > 0) original.toDouble() / compressed else 0.0
        return "原始数据 " + formatBytes(original) + "；压缩数据 " + formatBytes(compressed) +
            "；实际占用 " + formatBytes(used) + "；压缩比 " +
            (if (ratio > 0) String.format(Locale.US, "%.2f:1", ratio) else "不可用") + "（内核全局统计）"
    }

    private fun formatBytes(bytes: Long) = String.format(Locale.US, "%.1f MiB", bytes / 1048576.0)

    private inner class ProcessAdapter : RecyclerView.Adapter<ProcessAdapter.Holder>() {
        private var all: List<ProcessItem> = emptyList()
        private var visible: List<ProcessItem> = emptyList()
        private var queryText = ""
        private var sortMode = 0
        private var uidFilter = 0
        inner class Holder(val container: LinearLayout) : RecyclerView.ViewHolder(container)
        fun submit(items: List<ProcessItem>) {
            all = items
            applyFilters()
        }
        fun filter(query: String) {
            queryText = query
            applyFilters()
        }
        fun setSort(mode: Int) {
            sortMode = mode
            applyFilters()
        }
        fun setUidFilter(mode: Int) {
            uidFilter = mode
            applyFilters()
        }
        private fun applyFilters() {
            val filtered = all.filter { p ->
                val matchesQuery = p.name.contains(queryText, true) ||
                    p.pid.toString().contains(queryText) || p.uid.toString().contains(queryText)
                val appId = p.uid % 100000
                val matchesUid = when (uidFilter) {
                    1 -> appId >= 10000
                    2 -> appId < 10000
                    else -> true
                }
                matchesQuery && matchesUid
            }
            visible = when (sortMode) {
                1 -> filtered.sortedByDescending { if (it.pssKb >= 0) it.pssKb else it.rssKb }
                2 -> filtered.sortedByDescending { it.swapKb }
                else -> filtered.sortedByDescending { it.rssKb }
            }
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
            holder.container.addView(text(
                "Private Dirty " + (if (p.privateDirtyKb >= 0) formatKb(p.privateDirtyKb) else "未采集") +
                    "  |  Shared Dirty " + (if (p.sharedDirtyKb >= 0) formatKb(p.sharedDirtyKb) else "未采集"),
                10, false, MUTED
            ))
        }
        override fun getItemCount() = visible.size
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}

private data class ProcessItem(val pid: Int, val uid: Int, val startTicks: String, val name: String, val rssKb: Long, val pssKb: Long, val privateDirtyKb: Long, val sharedDirtyKb: Long, val swapKb: Long)
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
            DETAIL=@DETAIL@
            INCLUDE_SYSTEM=@SYSTEM@
            total=0
            for x in /proc/[0-9]*; do [ -d "@D@x" ] && total=@D@((total+1)); done
            echo "TOTAL|@D@total"
            echo "ZRAM|@D@(cat /sys/block/zram0/mm_stat 2>/dev/null)"
            echo "SWAPS_BEGIN"
            cat /proc/swaps 2>/dev/null
            echo "SWAPS_END"
            done_count=0
            for x in /proc/[0-9]*; do
              [ -d "@D@x" ] || continue
              pid=@D@{x##*/}
              status="@D@x/status"
              if [ ! -r "@D@status" ]; then
                done_count=@D@((done_count+1))
                echo "PROGRESS|@D@done_count|@D@total|读取进程|PID @D@pid"
                continue
              fi
              vals=@D@(awk '/^Uid:/{u=@D@2} /^VmRSS:/{r=@D@2} /^VmSwap:/{s=@D@2} END{printf "%s|%s|%s",u,r,s}' "@D@status" 2>/dev/null)
              oldifs=@D@IFS
              IFS='|'
              set -- @D@vals
              IFS=@D@oldifs
              uid=@D@{1:-0}; rss=@D@{2:-0}; vswap=@D@{3:-0}
              fallback=@D@(awk '/^Name:/{sub(/^Name:[ \t]*/, ""); print; exit}' "@D@status" 2>/dev/null)
              name=@D@(tr '\000' '\n' < "@D@x/cmdline" 2>/dev/null | head -n 1)
              [ -n "@D@name" ] || name=@D@fallback
              [ -n "@D@name" ] || name=unknown
              case "@D@uid" in ''|*[!0-9]*) uid=0;; esac
              case "@D@rss" in ''|*[!0-9]*) rss=0;; esac
              case "@D@vswap" in ''|*[!0-9]*) vswap=0;; esac
              if [ "@D@INCLUDE_SYSTEM" = "0" ] && [ "@D@uid" -lt 10000 ] 2>/dev/null; then
                done_count=@D@((done_count+1))
                echo "PROGRESS|@D@done_count|@D@total|过滤系统进程|PID @D@pid"
                continue
              fi
              statline=@D@(cat "@D@x/stat" 2>/dev/null)
              startticks=@D@(printf '%s\n' "@D@statline" | awk '{sub(/^.*\) /,""); print @D@20}')
              [ -n "@D@startticks" ] || startticks=0
              pss=-1
              private_dirty=-1
              shared_dirty=-1
              swap=@D@vswap
              if [ "@D@DETAIL" = "1" ] && [ -r "@D@x/smaps" ]; then
                mem=@D@(awk '/^Pss:/{p+=@D@2} /^Private_Dirty:/{pd+=@D@2} /^Shared_Dirty:/{sd+=@D@2} /^Swap:/{s+=@D@2} END{printf "%d|%d|%d|%d",p,pd,sd,s}' "@D@x/smaps" 2>/dev/null)
                oldifs=@D@IFS; IFS='|'; set -- @D@mem; IFS=@D@oldifs
                pss=@D@{1:--1}; private_dirty=@D@{2:--1}; shared_dirty=@D@{3:--1}; swap=@D@{4:-@D@vswap}
              fi
              name=@D@(printf '%s' "@D@name" | tr '|' '_')
              echo "PROC|@D@pid|@D@uid|@D@startticks|@D@name|@D@rss|@D@pss|@D@private_dirty|@D@shared_dirty|@D@swap"
              done_count=@D@((done_count+1))
              if [ "@D@DETAIL" = "1" ]; then stage="详细 PSS"; else stage="基础扫描"; fi
              echo "PROGRESS|@D@done_count|@D@total|@D@stage|PID @D@pid"
            done
            echo "SCAN_END"
        """.trimIndent()
            .replace("@D@", "\$")
            .replace("@DETAIL@", detailFlag)
            .replace("@SYSTEM@", systemFlag)

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
                    val f = line.split('|', limit = 10)
                    if (f.size == 10) processes.add(ProcessItem(
                        f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: 0, f[3], f[4],
                        f[5].toLongOrNull() ?: 0, f[6].toLongOrNull() ?: -1,
                        f[7].toLongOrNull() ?: -1, f[8].toLongOrNull() ?: -1,
                        f[9].toLongOrNull() ?: 0
                    ))
                }
            }
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return ScanResult(Snapshot(time, processes, zram, swaps.toString().ifBlank { "不可用" }), "", System.currentTimeMillis() - started)
    }
}
