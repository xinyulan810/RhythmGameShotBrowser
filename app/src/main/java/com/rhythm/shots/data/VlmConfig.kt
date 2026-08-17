package com.rhythm.shots.data

import android.content.Context

/**
 * VLM 大模型识别配置（设置页可改）。
 * 默认指向阿里云百炼 OpenAI 兼容端点 + qwen3.7-flash。
 */
data class VlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val incrementalEnabled: Boolean,
    val concurrency: Int = 4
) {
    companion object {
        const val DEFAULT_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DEFAULT_MODEL = "qwen3.7-flash"
        const val DEFAULT_CONCURRENCY = 4
        private const val PREFS = "vlm_config"

        fun load(context: Context): VlmConfig {
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return VlmConfig(
                baseUrl = sp.getString("base_url", DEFAULT_BASE) ?: DEFAULT_BASE,
                apiKey = sp.getString("api_key", "") ?: "",
                model = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
                incrementalEnabled = sp.getBoolean("incremental", true),
                concurrency = sp.getInt("concurrency", DEFAULT_CONCURRENCY).coerceIn(1, 16)
            )
        }

        fun save(context: Context, cfg: VlmConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("base_url", cfg.baseUrl.trim())
                .putString("api_key", cfg.apiKey.trim())
                .putString("model", cfg.model.trim())
                .putBoolean("incremental", cfg.incrementalEnabled)
                .putInt("concurrency", cfg.concurrency.coerceIn(1, 16))
                .apply()
        }
    }
}
