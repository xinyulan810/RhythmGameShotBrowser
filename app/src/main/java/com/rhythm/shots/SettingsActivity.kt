package com.rhythm.shots

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rhythm.shots.data.VlmConfig
import com.rhythm.shots.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val cfg = VlmConfig.load(this)
        binding.baseUrlInput.setText(cfg.baseUrl)
        binding.apiKeyInput.setText(cfg.apiKey)
        binding.modelInput.setText(cfg.model)
        binding.concurrencyInput.setText(cfg.concurrency.toString())
        binding.incrementalSwitch.isChecked = cfg.incrementalEnabled
        binding.hintText.text = "默认平台：阿里云百炼（DashScope）OpenAI 兼容端点\n" +
            "模型：qwen3.7-flash（便宜且支持视觉理解，适合截图识别）\n" +
            "填好 API 地址和 Key 后可点「获取模型列表」选择模型"

        binding.fetchModelsButton.setOnClickListener {
            val base = binding.baseUrlInput.text?.toString()?.trim().orEmpty()
            val key = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
            if (base.isBlank() || key.isBlank()) {
                Toast.makeText(this, "请先填写 API 地址和 Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            fetchModelList(VlmConfig(base, key, binding.modelInput.text?.toString().orEmpty(), false))
        }

        binding.recognizeGamesButton.setOnClickListener {
            val base = binding.baseUrlInput.text?.toString()?.trim().orEmpty()
            val key = binding.apiKeyInput.text?.toString()?.trim().orEmpty()
            val model = binding.modelInput.text?.toString()?.trim().orEmpty()
            if (base.isBlank() || key.isBlank() || model.isBlank()) {
                Toast.makeText(this, "请先填写 API 地址、Key 和模型名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            recognizeGameList(VlmConfig(base, key, model, false))
        }

        binding.manageGamesButton.setOnClickListener {
            startActivity(android.content.Intent(this, GameManageActivity::class.java))
        }

        binding.viewPromptButton.setOnClickListener { showPrompt() }

        binding.saveButton.setOnClickListener {
            val newCfg = VlmConfig(
                baseUrl = binding.baseUrlInput.text?.toString().orEmpty(),
                apiKey = binding.apiKeyInput.text?.toString().orEmpty(),
                model = binding.modelInput.text?.toString().orEmpty(),
                incrementalEnabled = binding.incrementalSwitch.isChecked,
                concurrency = binding.concurrencyInput.text?.toString()?.toIntOrNull() ?: 4
            )
            if (newCfg.baseUrl.isBlank() || newCfg.apiKey.isBlank() || newCfg.model.isBlank()) {
                Toast.makeText(this, "请填写完整的 API 地址、Key 和模型名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            VlmConfig.save(this, newCfg)
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            // 用任意一张截图测试连接
            testConnection(newCfg)
        }
    }

    /** 展示当前生效的 AI 识别提示词（游戏列表基于合并+隐藏后的可见游戏） */
    private fun showPrompt() {
        val allGames = (com.rhythm.shots.data.GameMap.load(this).values +
            com.rhythm.shots.data.ShotDb(this).all().map { it.gameName }).toList()
        val visible = com.rhythm.shots.data.GameSettings.effectiveGameList(this, allGames)
        val prompt = com.rhythm.shots.data.VlmEngine.getPrompt(visible)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("当前识别提示词（可见游戏 ${visible.size} 个）")
            .setMessage(prompt)
            .setPositiveButton("复制", null)
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 把 app 内出现的所有包名发给 AI，识别 包名→游戏名 映射，合并进 GameMap */
    private fun recognizeGameList(cfg: VlmConfig) {
        binding.recognizeGamesButton.isEnabled = false
        binding.recognizeGamesButton.text = "识别中..."
        binding.gameMapResultText.text = "正在让 AI 识别包名对应的游戏…"
        lifecycleScope.launch {
            val packages = withContext(Dispatchers.IO) {
                val db = com.rhythm.shots.data.ShotDb(this@SettingsActivity)
                db.all().map { it.pkg }.filter { it.isNotBlank() }.distinct()
            }
            val result = withContext(Dispatchers.IO) {
                com.rhythm.shots.data.VlmEngine.recognizeGameList(cfg, packages)
            }
            binding.recognizeGamesButton.isEnabled = true
            binding.recognizeGamesButton.text = "AI识别游戏列表"
            if (result.isEmpty()) {
                binding.gameMapResultText.text = "❌ AI 未能识别包名，请检查 API 配置"
                return@launch
            }
            // 合并进 GameMap（覆盖/新增）
            val map = com.rhythm.shots.data.GameMap.load(this@SettingsActivity)
            result.forEach { (pkg, game) -> map[pkg] = game }
            com.rhythm.shots.data.GameMap.save(this@SettingsActivity, map)
            // 标记已完成，主界面不再提示
            getSharedPreferences("vlm_config", MODE_PRIVATE)
                .edit().putBoolean("game_list_done", true).apply()
            val lines = result.entries.joinToString("\n") { "${it.key} → ${it.value}" }
            binding.gameMapResultText.text = "✅ AI 识别出 ${result.size} 个游戏：\n$lines"
            Toast.makeText(this@SettingsActivity, "已识别 ${result.size} 个游戏映射", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchModelList(cfg: VlmConfig) {
        binding.fetchModelsButton.isEnabled = false
        binding.fetchModelsButton.text = "获取中..."
        lifecycleScope.launch {
            val models = withContext(Dispatchers.IO) {
                com.rhythm.shots.data.VlmEngine.fetchModels(cfg)
            }
            binding.fetchModelsButton.isEnabled = true
            binding.fetchModelsButton.text = "获取模型列表"
            if (models.isEmpty()) {
                Toast.makeText(this@SettingsActivity, "获取失败：请检查 API 地址和 Key", Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 优先展示视觉/flash 相关模型
            val preferred = models.filter { it.contains("flash", true) || it.contains("vl", true) || it.contains("vision", true) }
            val ordered = (preferred + models.filter { it !in preferred }).distinct()
            val current = binding.modelInput.text?.toString()?.trim().orEmpty()
            val selectedIndex = ordered.indexOf(current).coerceAtLeast(0)
            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                .setTitle("选择模型（共 ${models.size} 个）")
                .setSingleChoiceItems(ordered.toTypedArray(), selectedIndex) { _, which ->
                    binding.modelInput.setText(ordered[which])
                }
                .setPositiveButton("确定") { _, _ ->
                    Toast.makeText(this@SettingsActivity, "已选择模型", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun testConnection(cfg: VlmConfig) {
        binding.saveButton.isEnabled = false
        binding.saveButton.text = "测试中..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                // 找一张已索引的截图测试
                val db = com.rhythm.shots.data.ShotDb(this@SettingsActivity)
                db.vlmPendingPaths(1).firstOrNull()?.let { path ->
                    com.rhythm.shots.data.VlmEngine.recognize(cfg, path)
                }
            }
            binding.saveButton.isEnabled = true
            binding.saveButton.text = "保存并测试"
            if (result != null) {
                binding.hintText.text = "✅ 测试成功！识别结果：\n" +
                    "游戏：${result.game}\n歌曲：${result.song}\n" +
                    "难度：${result.difficulty}  分数：${result.score}  评级：${result.rank}"
                Toast.makeText(this@SettingsActivity, "连接成功，识别正常", Toast.LENGTH_SHORT).show()
            } else {
                binding.hintText.text = "❌ 测试失败：请检查 API 地址 / Key / 模型名是否正确"
                Toast.makeText(this@SettingsActivity, "连接失败，请检查配置", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
