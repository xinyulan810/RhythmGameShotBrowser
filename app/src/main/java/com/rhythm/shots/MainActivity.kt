package com.rhythm.shots

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.rhythm.shots.data.GameMap
import com.rhythm.shots.data.GameSettings
import com.rhythm.shots.data.ShotDb
import com.rhythm.shots.data.ShotItem
import com.rhythm.shots.data.ShotScanner
import com.rhythm.shots.data.OcrEngine
import com.rhythm.shots.data.VlmConfig
import com.rhythm.shots.data.VlmEngine
import com.rhythm.shots.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        /** 文件名 Screenshot_yyyyMMdd_HHmmss_xxx → 时间排序键（yyyyMMddHHmmss） */
        private val SHOT_TIME_RE = Regex("^Screenshot_(\\d{8})_(\\d{6})_")
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: ShotDb
    private lateinit var deleter: ShotDeleter
    private val sectionAdapter = ShotSectionAdapter()

    private var selectedGame: String? = null   // null = 全部
    private var startDate: String? = null      // yyyyMMdd
    private var endDate: String? = null        // yyyyMMdd
    private var ocrJob: Job? = null
    private var vlmJob: Job? = null
    private val vlmPaused = AtomicBoolean(false)
    private var allShots: List<ShotItem> = emptyList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadData() else Snackbar.make(binding.root, "没有读取图片权限，无法扫描截图", Snackbar.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = ShotDb(this)
        deleter = ShotDeleter(this)

        binding.sectionList.layoutManager = LinearLayoutManager(this)
        binding.sectionList.adapter = sectionAdapter
        // 滚动性能：高度固定 / 增大缓存 / 无增删动画
        binding.sectionList.setHasFixedSize(true)
        binding.sectionList.setItemViewCacheSize(20)
        binding.sectionList.itemAnimator = null

        binding.scanFab.setOnClickListener { loadData() }
        binding.swipeRefresh.setOnRefreshListener { loadData() }
        binding.searchButton.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        binding.mapButton.setOnClickListener {
            startActivity(Intent(this, GameMapActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.batchButton.setOnClickListener { showBatchDialog() }
        binding.aiFab.setOnClickListener { startVlmBatch() }
        binding.aiPauseButton.setOnClickListener { toggleVlmPause() }
        binding.dateClearButton.setOnClickListener {
            startDate = null; endDate = null
            binding.dateStartButton.text = "起始日期"
            binding.dateEndButton.text = "结束日期"
            refreshUi()
        }
        binding.dateStartButton.setOnClickListener { pickDateRange(true) }
        binding.dateEndButton.setOnClickListener { pickDateRange(false) }

        sectionAdapter.onShotClick = { shots, index ->
            val intent = Intent(this, ViewerActivity::class.java)
                .putStringArrayListExtra(ViewerActivity.EXTRA_PATHS, ArrayList(shots.map { it.path }))
                .putExtra(ViewerActivity.EXTRA_INDEX, index)
            startActivity(intent)
        }
        // 长按缩略图：分享 / 复制识别文本 / 删除
        sectionAdapter.onShotLongClick = { item ->
            ShotActions.showActionsMenu(this, item) {
                deleteShot(item)
            }
        }

        if (hasReadPermission()) loadData() else permissionLauncher.launch(readPermission())
    }

    override fun onResume() {
        super.onResume()
        // 从映射/设置/游戏管理页返回时：重建游戏名 + 刷新（让改名立即生效）
        if (::db.isInitialized && allShots.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                db.rebuildGameNames(this@MainActivity)
                val fresh = db.all()
                withContext(Dispatchers.Main) {
                    allShots = fresh
                    buildGameChips()
                    refreshUi()
                }
            }
        }
    }

    // ============ VLM AI 批量识别 ============

    private fun startVlmBatch() {
        val cfg = VlmConfig.load(this)
        if (cfg.apiKey.isBlank()) {
            Snackbar.make(binding.root, "请先在「AI设置」中填写 API Key", Snackbar.LENGTH_LONG)
                .setAction("去设置") {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }.show()
            return
        }
        if (vlmJob?.isActive == true) return
        // 游戏范围：限制 AI 只在已知游戏中判断（省 token 且不乱猜）
        val games = GameMap.load(this).values.distinct()
        val concurrency = cfg.concurrency.coerceAtLeast(1)
        vlmPaused.set(false)
        binding.aiPauseButton.text = "暂停"
        vlmJob = lifecycleScope.launch(Dispatchers.IO) {
            val total = db.vlmPendingCount()
            if (total == 0) return@launch // 没有待识别截图：不显示状态行，有新图时再弹出
            withContext(Dispatchers.Main) {
                binding.aiStatusRow.visibility = View.VISIBLE
            }
            var done = 0
            updateAiStatus("AI 识别中 0/$total（并发 $concurrency）")
            while (isActive) {
                if (vlmPaused.get()) {
                    updateAiStatus("已暂停（$done/$total），点「继续」恢复")
                    withContext(Dispatchers.Main) { binding.aiPauseButton.text = "继续" }
                    return@launch
                }
                val pending = db.vlmPendingPaths(concurrency)
                if (pending.isEmpty()) break
                // 真正的并行：同一批并发识别，全部完成后统一写库
                val results = pending.map { path ->
                    async {
                        path to VlmEngine.recognize(cfg, path, games)
                    }
                }.awaitAll()
                for ((path, r) in results) {
                    if (r != null) db.updateVlm(path, r)
                    done++
                    if (done % 2 == 0 || done == total) {
                        updateAiStatus("AI 识别中 $done/$total（并发 $concurrency）")
                    }
                }
            }
            updateAiStatus("AI 识别完成 $done/$total")
            allShots = db.all()
            refreshUi()
            // 完成信息短暂停留后自动隐藏；下次扫描发现新图时会重新弹出
            delay(2000)
            withContext(Dispatchers.Main) {
                binding.aiStatusRow.visibility = View.GONE
            }
        }
    }

    private fun toggleVlmPause() {
        if (vlmJob?.isActive != true) return
        vlmPaused.set(!vlmPaused.get())
        binding.aiPauseButton.text = if (vlmPaused.get()) "继续" else "暂停"
        binding.aiStatusText.text = if (vlmPaused.get()) "暂停中，当前这张识别完即停…" else "继续识别中…"
    }

    private suspend fun updateAiStatus(text: String) {
        withContext(Dispatchers.Main) {
            binding.aiStatusText.text = text
            binding.aiStatusRow.visibility = View.VISIBLE
        }
    }

    /** 增量识别：配置了 Key 且开关打开时，扫描后自动补识别新截图 */
    private fun maybeAutoVlm() {
        val cfg = VlmConfig.load(this)
        if (!cfg.incrementalEnabled || cfg.apiKey.isBlank()) return
        if (vlmJob?.isActive == true) return
        startVlmBatch()
    }

    // ============ 批量识别（Batch API，半价，24h 内返回） ============

    private fun showBatchDialog() {
        val cfg = VlmConfig.load(this)
        if (cfg.apiKey.isBlank()) {
            Snackbar.make(binding.root, "请先在「AI设置」中填写 API Key", Snackbar.LENGTH_LONG).show()
            return
        }
        val tasks = com.rhythm.shots.data.BatchTaskStore.load(this)
        val pending = db.vlmPendingCount()
        val msg = buildString {
            append("待识别截图：$pending 张\n\n")
            if (tasks.isEmpty()) {
                append("尚未提交过批量任务。")
            } else {
                append("已有批量任务：\n")
                tasks.forEach { t ->
                    append("• ${t.batchId.takeLast(10)}：${t.status}（${t.done}/${t.total}）\n")
                }
            }
            append("\n批量识别半价、24h 内返回，适合一次处理全部存量。")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("批量识别（半价）")
            .setMessage(msg)
            .setPositiveButton("提交新任务") { _, _ ->
                confirmSubmitBatch()
            }
            .setNeutralButton("刷新/下载结果") { _, _ ->
                refreshBatchTasks()
                importBatchResults()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmSubmitBatch() {
        val pending = db.vlmPendingCount()
        if (pending == 0) {
            Toast.makeText(this, "没有待识别截图", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("提交批量任务")
            .setMessage("将把 $pending 张截图打包提交到批量接口（半价，约 24h 内完成，完成后手动下载结果）。\n提交后不可撤回，确定？")
            .setPositiveButton("提交") { _, _ -> submitBatchTask() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun submitBatchTask() {
        val cfg = VlmConfig.load(this)
        val games = GameMap.load(this).values.distinct()
        val paths = db.vlmPendingPaths(50000)
        if (paths.isEmpty()) {
            Toast.makeText(this, "没有待识别截图", Toast.LENGTH_SHORT).show()
            return
        }
        binding.aiStatusRow.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                updateAiStatus("批量：生成文件 0/${paths.size}…")
                val file = File(cacheDir, "batch_input_${System.currentTimeMillis()}.jsonl")
                val count = com.rhythm.shots.data.VlmEngine.buildBatchJsonl(
                    cfg, paths, games, file
                ) { done, total ->
                    if (done % 100 == 0) updateAiStatus("批量：生成文件 $done/$total…")
                }
                if (count == 0) {
                    updateAiStatus("批量：没有可编码的图片")
                    return@launch
                }
                updateAiStatus("批量：上传文件（${file.length() / 1024 / 1024}MB）…")
                val fileId = com.rhythm.shots.data.VlmEngine.uploadBatchFile(cfg, file) ?: run {
                    updateAiStatus("批量：上传失败，检查 API 配置")
                    return@launch
                }
                updateAiStatus("批量：创建任务…")
                val batchId = com.rhythm.shots.data.VlmEngine.createBatch(cfg, fileId) ?: run {
                    updateAiStatus("批量：创建任务失败")
                    return@launch
                }
                com.rhythm.shots.data.BatchTaskStore.add(
                    this@MainActivity,
                    com.rhythm.shots.data.BatchTask(
                        batchId = batchId, fileId = fileId, submittedAt = System.currentTimeMillis(),
                        status = "submitted", total = count
                    )
                )
                file.delete()
                updateAiStatus("已提交批量任务 $batchId（$count 张，半价，24h 内完成）")
                Toast.makeText(this@MainActivity, "批量任务已提交", Toast.LENGTH_SHORT).show()
                // 自动轮询一次状态
                refreshBatchTasks()
            } catch (e: Exception) {
                updateAiStatus("批量：出错 ${e.message}")
            }
        }
    }

    /** 轮询所有未完成任务的状态，并更新显示 */
    private fun refreshBatchTasks() {
        val cfg = VlmConfig.load(this)
        val tasks = com.rhythm.shots.data.BatchTaskStore.load(this)
        if (tasks.isEmpty()) return
        binding.aiStatusRow.visibility = View.VISIBLE
        lifecycleScope.launch {
            var completed = false
            tasks.filter { it.status != "completed" && it.status != "failed" && it.status != "cancelled" }
                .forEach { t ->
                    val json = withContext(Dispatchers.IO) {
                        com.rhythm.shots.data.VlmEngine.retrieveBatch(cfg, t.batchId)
                    } ?: return@forEach
                    val status = json.optString("status", t.status)
                    val out = json.optString("output_file_id", "")
                    val err = json.optString("error_file_id", "")
                    val counts = json.optJSONObject("request_counts")
                    val done = counts?.optInt("completed", t.done) ?: t.done
                    val total = counts?.optInt("total", t.total) ?: t.total
                    com.rhythm.shots.data.BatchTaskStore.update(this@MainActivity, t.batchId) { u ->
                        u.status = status
                        u.done = done
                        u.total = total
                        u.outputFileId = out
                        u.errorFileId = err
                    }
                    if (status == "completed") completed = true
                }
            val updated = com.rhythm.shots.data.BatchTaskStore.load(this@MainActivity)
            val line = updated.joinToString("；") { "${it.batchId.takeLast(10)} ${it.status} ${it.done}/${it.total}" }
            updateAiStatus("批量任务：$line")
            if (completed) {
                Toast.makeText(this@MainActivity, "有批量任务已完成，点「批量 → 刷新/下载结果」导入", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 下载已完成任务的结果并回填数据库（先刷新任务状态） */
    private fun importBatchResults() {
        val cfg = VlmConfig.load(this)
        binding.aiStatusRow.visibility = View.VISIBLE
        lifecycleScope.launch {
            // 先刷新一遍状态，找到真正完成的
            val tasks = com.rhythm.shots.data.BatchTaskStore.load(this@MainActivity)
            tasks.filter { it.status != "completed" && it.status != "failed" && it.status != "cancelled" }
                .forEach { t ->
                    val json = withContext(Dispatchers.IO) {
                        com.rhythm.shots.data.VlmEngine.retrieveBatch(cfg, t.batchId)
                    } ?: return@forEach
                    com.rhythm.shots.data.BatchTaskStore.update(this@MainActivity, t.batchId) { u ->
                        u.status = json.optString("status", u.status)
                        u.outputFileId = json.optString("output_file_id", u.outputFileId)
                        u.errorFileId = json.optString("error_file_id", u.errorFileId)
                    }
                }
            val ready = com.rhythm.shots.data.BatchTaskStore.load(this@MainActivity)
                .filter { it.status == "completed" && it.outputFileId.isNotBlank() }
            if (ready.isEmpty()) {
                updateAiStatus("批量：暂无已完成任务（仍在处理中，稍后再试）")
                Toast.makeText(this@MainActivity, "批量任务还未完成", Toast.LENGTH_SHORT).show()
                return@launch
            }
            var imported = 0
            ready.forEach { t ->
                updateAiStatus("批量：下载结果 ${t.batchId.takeLast(10)}…")
                val reader = withContext(Dispatchers.IO) {
                    com.rhythm.shots.data.VlmEngine.downloadBatchOutput(cfg, t.outputFileId)
                } ?: return@forEach
                withContext(Dispatchers.IO) {
                    var line: String? = reader.readLine()
                    while (line != null) {
                        val parsed = com.rhythm.shots.data.VlmEngine.parseBatchResultLine(line)
                        if (parsed != null) {
                            val (path, result) = parsed
                            db.updateVlm(path, result)
                            imported++
                        }
                        line = reader.readLine()
                    }
                    reader.close()
                }
            }
            updateAiStatus("批量：已导入 $imported 条结果")
            allShots = db.all()
            refreshUi()
            Toast.makeText(this@MainActivity, "已导入 $imported 条识别结果", Toast.LENGTH_LONG).show()
        }
    }

    /** 删除截图：系统确认后清理数据库并刷新列表 */
    private fun deleteShot(item: ShotItem) {
        deleter.delete(item.path) {
            allShots = db.all()
            refreshUi()
        }
    }

    private fun hasReadPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun readPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun loadData() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { ShotScanner.scan(this@MainActivity) }
            withContext(Dispatchers.IO) { db.upsertAll(items) }
            // 映射可能被改过：按当前映射重建所有截图的游戏名（让改名立即生效）
            withContext(Dispatchers.IO) { db.rebuildGameNames(this@MainActivity) }
            allShots = db.all()
            buildGameChips()
            refreshUi()
            binding.swipeRefresh.isRefreshing = false
            if (allShots.isEmpty()) {
                Snackbar.make(binding.root, "未发现 vivo 格式截图（Pictures/Screenshots 下）", Snackbar.LENGTH_LONG).show()
            }
            startOcrPending()
            maybeAutoVlm()
            maybePromptGameList()
        }
    }

    /** 首次使用引导：配置了 Key 但还没让 AI 识别过游戏列表时提示 */
    private fun maybePromptGameList() {
        val cfg = VlmConfig.load(this)
        if (cfg.apiKey.isBlank()) return
        val sp = getSharedPreferences("vlm_config", Context.MODE_PRIVATE)
        if (sp.getBoolean("game_list_done", false)) return
        Snackbar.make(binding.root, "建议先用「AI设置 → AI识别游戏列表」让 AI 确定游戏范围", Snackbar.LENGTH_LONG)
            .setAction("去设置") {
                startActivity(Intent(this, SettingsActivity::class.java))
            }.show()
    }

    private fun buildGameChips() {
        // 应用合并+隐藏后的可见游戏列表
        val games = GameSettings.effectiveGameList(this, allShots.map { it.gameName })
        binding.gameChipGroup.removeAllViews()
        val allChip = Chip(this).apply {
            text = "全部"
            isCheckable = true
            isChecked = selectedGame == null
            setOnClickListener { selectedGame = null; refreshUi() }
        }
        binding.gameChipGroup.addView(allChip)
        games.forEach { g ->
            val chip = Chip(this).apply {
                text = g
                isCheckable = true
                isChecked = selectedGame == g
                setOnClickListener { selectedGame = g; refreshUi() }
            }
            binding.gameChipGroup.addView(chip)
        }
    }

    private fun refreshUi() {
        val s = startDate
        val e = endDate
        val hidden = GameSettings.hiddenGames(this)
        val filtered = allShots.filter { shot ->
            val game = GameSettings.effectiveName(this, shot.gameName)
            (selectedGame == null || game == selectedGame) &&
                game !in hidden &&
                (s == null || shot.date >= s) &&
                (e == null || shot.date <= e)
        }
        // 按合并后的游戏分组，组内按日期降序
        val groups = LinkedHashMap<String, MutableList<ShotItem>>()
        filtered.forEach { shot ->
            val game = GameSettings.effectiveName(this, shot.gameName)
            groups.getOrPut(game) { mutableListOf() }.add(shot)
        }
        // 组（游戏）按最新截图时间降序：最后截图的游戏排最上
        val sectionList = groups.entries
            .sortedByDescending { it.value.maxOfOrNull { s -> shotTimeKey(s) } ?: "" }
            .map { it.key to it.value }
        sectionAdapter.submitList(sectionList)
    }

    /** 截图时间排序键：优先取文件名中的 yyyyMMddHHmmss，解析失败兜底用 date（yyyyMMdd） */
    private fun shotTimeKey(shot: ShotItem): String {
        val m = SHOT_TIME_RE.find(shot.displayName)
        return m?.let { it.groupValues[1] + it.groupValues[2] } ?: shot.date
    }

    private fun pickDateRange(isStart: Boolean) {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(if (isStart) "选择起始日期" else "选择结束日期")
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val utc = java.util.TimeZone.getTimeZone("UTC")
            val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = utc }
            if (isStart) {
                startDate = selection.first?.let { fmt.format(Date(it)) }
                binding.dateStartButton.text = startDate?.let { fmtDate(it) } ?: "起始日期"
            } else {
                endDate = selection.second?.let { fmt.format(Date(it)) }
                binding.dateEndButton.text = endDate?.let { fmtDate(it) } ?: "结束日期"
            }
            refreshUi()
        }
        picker.show(supportFragmentManager, "dateRange")
    }

    private fun fmtDate(yyyyMMdd: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
            val d = sdf.parse(yyyyMMdd) ?: return yyyyMMdd
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)
        } catch (_: Exception) { yyyyMMdd }
    }

    /** 后台对未识别的新截图跑 OCR，分批循环直到全部完成，边识别边更新 UI */
    private fun startOcrPending() {
        if (ocrJob?.isActive == true) return
        ocrJob = lifecycleScope.launch(Dispatchers.IO) {
            val total = db.ocrPendingCount()
            if (total == 0) return@launch
            var done = 0
            withContext(Dispatchers.Main) {
                binding.ocrStatusText.visibility = View.VISIBLE
                binding.ocrStatusText.text = "识别中 0/$total"
            }
            while (isActive) {
                val pending = db.pendingOcrPaths()
                if (pending.isEmpty()) break
                pending.forEach { path ->
                    if (!isActive) return@launch
                    val text = OcrEngine.recognize(path)
                    db.updateOcr(path, text)
                    done++
                    if (done % 10 == 0 || done == total) {
                        withContext(Dispatchers.Main) {
                            binding.ocrStatusText.text = "识别中 $done/$total"
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                binding.ocrStatusText.visibility = View.GONE
                allShots = db.all()
                refreshUi()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ocrJob?.cancel()
        vlmJob?.cancel()
    }
}
