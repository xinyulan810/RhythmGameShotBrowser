package com.rhythm.shots.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * VLM 大模型识别：调用 OpenAI 兼容的 chat/completions 接口，
 * 发送 base64 图片 + 固定提示词，解析返回的 JSON 结构化字段。
 */
object VlmEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 动态提示词：限制 game 取值在已知游戏范围内（省 token 且避免乱猜）。公开供设置页展示。 */
    fun getPrompt(games: List<String>): String {
        val gameList = if (games.isEmpty()) "（不限，自行判断）" else games.joinToString("、")
        return """
你是音游成绩截图识别助手。识别截图，严格返回 JSON：
{"game":"游戏名","song":"歌曲名(原文)","song_cn":"中文译名","difficulty":"难度","score":"分数","accuracy":"准确率","rank":"评级","combo":"最大连击","is_result_screen":true,"full_text":"画面全部文字"}
规则：game 必须从以下列表选一个：$gameList。无法确定填"未知"。不是结算页则 is_result_screen=false，song/score 留空。
""".trimIndent()
    }

    /** 批量识别游戏列表：给定包名列表，让 AI 返回 包名→游戏名 映射（一次调用，省 token） */
    suspend fun recognizeGameList(cfg: VlmConfig, packages: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (packages.isEmpty()) return@withContext emptyMap()
            try {
                val prompt = """
你是音游识别助手。以下是一组 Android 应用包名，请判断每个包名对应的游戏（多为音游），严格返回 JSON 对象：{"包名":"游戏名",...}
只返回你能确定的，不确定的跳过。示例：{"moe.low.arc":"Arcaea","com.sega.pjsekai":"世界计划"}
包名列表：${packages.joinToString("、")}
""".trimIndent()
                val body = JSONObject().apply {
                    put("model", cfg.model)
                    put("temperature", 0.1)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", prompt)
                                })
                            })
                        })
                    })
                    put("response_format", JSONObject().apply { put("type", "json_object") })
                }.toString()

                val base = cfg.baseUrl.trimEnd('/')
                val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${cfg.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyMap()
                    val root = JSONObject(resp.body?.string() ?: "")
                    val content = root.getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").getString("content")
                    val map = mutableMapOf<String, String>()
                    var s = content.trim()
                    if (s.startsWith("```")) s = s.substringAfter('\n').substringBeforeLast("```").trim()
                    val start = s.indexOf('{')
                    val end = s.lastIndexOf('}')
                    if (start >= 0 && end > start) {
                        val obj = JSONObject(s.substring(start, end + 1))
                        val it = obj.keys()
                        while (it.hasNext()) {
                            val k = it.next()
                            val v = obj.optString(k).trim()
                            if (v.isNotEmpty()) map[k] = v
                        }
                    }
                    map
                }
            } catch (e: Exception) {
                android.util.Log.w("VlmEngine", "recognizeGameList error: ${e.message}")
                emptyMap()
            }
        }

    /** 获取平台可用模型列表（OpenAI 兼容 GET /models） */
    suspend fun fetchModels(cfg: VlmConfig): List<String> = withContext(Dispatchers.IO) {
        try {
            val base = cfg.baseUrl.trimEnd('/')
            val url = if (base.endsWith("/models")) base else "$base/models"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val root = JSONObject(resp.body?.string() ?: "")
                val arr = root.optJSONArray("data") ?: return@withContext emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("id", null)
                }.filter { it.isNotBlank() }.sorted()
            }
        } catch (e: Exception) {
            android.util.Log.w("VlmEngine", "fetchModels error: ${e.message}")
            emptyList()
        }
    }

    suspend fun recognize(cfg: VlmConfig, path: String, games: List<String> = emptyList()): VlmResult? = withContext(Dispatchers.IO) {
        try {
            val b64 = encodeImage(path) ?: return@withContext null
            val body = JSONObject().apply {
                put("model", cfg.model)
                put("temperature", 0.1)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", getPrompt(games))
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$b64")
                                })
                            })
                        })
                    })
                })
                put("response_format", JSONObject().apply { put("type", "json_object") })
            }.toString()

            val base = cfg.baseUrl.trimEnd('/')
            val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: return@withContext null
                if (!resp.isSuccessful) {
                    android.util.Log.w("VlmEngine", "HTTP ${resp.code}: ${respBody.take(300)}")
                    return@withContext null
                }
                val root = JSONObject(respBody)
                val content = root.getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content")
                parseJson(content)
            }
        } catch (e: Exception) {
            android.util.Log.w("VlmEngine", "recognize error: ${e.message}")
            null
        }
    }

    private fun parseJson(raw: String): VlmResult? {
        try {
            var s = raw.trim()
            // 剥离 markdown 代码块
            if (s.startsWith("```")) {
                s = s.substringAfter('\n').substringBeforeLast("```").trim()
            }
            val start = s.indexOf('{')
            val end = s.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = JSONObject(s.substring(start, end + 1))
            fun str(key: String) = obj.optString(key, "").trim()
            return VlmResult(
                game = str("game"),
                song = str("song"),
                songCn = str("song_cn"),
                difficulty = str("difficulty"),
                score = str("score"),
                accuracy = str("accuracy"),
                rank = str("rank"),
                combo = str("combo"),
                fullText = str("full_text")
            )
        } catch (e: Exception) {
            android.util.Log.w("VlmEngine", "parse error: ${e.message} raw=$raw")
            return null
        }
    }

    /** 压缩并 base64 编码图片（默认最长边 1280，JPEG 质量 85；批量用 800/70 省体积） */
    private fun encodeImage(path: String, maxDim: Int = 1280, quality: Int = 85): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ==================== 批量调用（Batch API，半价，24h 内返回） ====================

    /** 生成 JSONL 输入文件（流式写入，避免内存爆）。返回写入条数 */
    suspend fun buildBatchJsonl(
        cfg: VlmConfig,
        paths: List<String>,
        games: List<String>,
        outFile: File,
        onProgress: suspend (Int, Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        val prompt = getPrompt(games)
        var count = 0
        outFile.bufferedWriter(Charsets.UTF_8).use { w: BufferedWriter ->
            paths.forEach { path ->
                val b64 = encodeImage(path, 800, 70) ?: return@forEach
                val body = JSONObject().apply {
                    put("model", cfg.model)
                    put("temperature", 0.1)
                    put("enable_thinking", false)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", prompt)
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", "data:image/jpeg;base64,$b64")
                                    })
                                })
                            })
                        })
                    })
                    put("response_format", JSONObject().apply { put("type", "json_object") })
                }
                val line = JSONObject().apply {
                    put("custom_id", path) // 用路径做 custom_id，结果回填时直接匹配
                    put("method", "POST")
                    put("url", "/v1/chat/completions")
                    put("body", body)
                }
                w.write(line.toString())
                w.newLine()
                count++
                if (count % 50 == 0) onProgress(count, paths.size)
            }
        }
        onProgress(count, paths.size)
        count
    }

    /** 上传批量输入文件，返回 file id（batch 用途） */
    suspend fun uploadBatchFile(cfg: VlmConfig, file: File): String? = withContext(Dispatchers.IO) {
        try {
            val base = cfg.baseUrl.trimEnd('/')
            val req = Request.Builder()
                .url("$base/files")
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .post(
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", file.name, file.asRequestBody("application/jsonl".toMediaType()))
                        .addFormDataPart("purpose", "batch")
                        .build()
                )
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w("VlmEngine", "upload error ${resp.code}: ${resp.body?.string()?.take(200)}")
                    return@withContext null
                }
                JSONObject(resp.body?.string() ?: "").optString("id", null)?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            android.util.Log.w("VlmEngine", "upload error: ${e.message}")
            null
        }
    }

    /** 创建批量任务，返回 batch id */
    suspend fun createBatch(cfg: VlmConfig, fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val base = cfg.baseUrl.trimEnd('/')
            val body = JSONObject().apply {
                put("input_file_id", fileId)
                put("endpoint", "/v1/chat/completions")
                put("completion_window", "24h")
                put("metadata", JSONObject().apply { put("ds_name", "ShotBrowser batch") })
            }.toString()
            val req = Request.Builder()
                .url("$base/batches")
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w("VlmEngine", "createBatch error ${resp.code}: ${resp.body?.string()?.take(200)}")
                    return@withContext null
                }
                JSONObject(resp.body?.string() ?: "").optString("id", null)?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            android.util.Log.w("VlmEngine", "createBatch error: ${e.message}")
            null
        }
    }

    /** 查询批量任务状态，返回原始 JSON（status / output_file_id / request_counts 等） */
    suspend fun retrieveBatch(cfg: VlmConfig, batchId: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val base = cfg.baseUrl.trimEnd('/')
            val req = Request.Builder()
                .url("$base/batches/$batchId")
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                JSONObject(resp.body?.string() ?: "")
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 下载批量结果（output 文件），返回逐行读取器 */
    suspend fun downloadBatchOutput(cfg: VlmConfig, outputFileId: String): BufferedReader? =
        withContext(Dispatchers.IO) {
            try {
                val base = cfg.baseUrl.trimEnd('/')
                val req = Request.Builder()
                    .url("$base/files/$outputFileId/content")
                    .header("Authorization", "Bearer ${cfg.apiKey}")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) {
                    resp.close()
                    return@withContext null
                }
                BufferedReader(InputStreamReader(resp.body?.byteStream(), Charsets.UTF_8))
            } catch (e: Exception) {
                null
            }
        }

    /** 解析批量结果的一行：custom_id（路径）→ VlmResult */
    fun parseBatchResultLine(line: String): Pair<String, VlmResult>? {
        return try {
            val obj = JSONObject(line)
            val customId = obj.optString("custom_id", "")
            val body = obj.optJSONObject("response")?.optJSONObject("body")
                ?: return null
            val content = body.optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
                ?: return null
            val result = parseJson(content) ?: return null
            customId to result
        } catch (e: Exception) {
            null
        }
    }
}
