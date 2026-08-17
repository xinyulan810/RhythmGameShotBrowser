package com.rhythm.shots.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一个批量识别任务记录 */
data class BatchTask(
    val batchId: String,
    val fileId: String,
    val submittedAt: Long,
    var status: String = "submitted", // submitted / processing / completed / failed / cancelled
    var total: Int = 0,
    var done: Int = 0,
    var outputFileId: String = "",
    var errorFileId: String = ""
)

/** 批量任务持久化（SharedPreferences JSON） */
object BatchTaskStore {

    private const val PREFS = "batch_tasks"
    private const val KEY = "tasks"

    fun load(context: Context): MutableList<BatchTask> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<BatchTask>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    BatchTask(
                        batchId = o.getString("batch_id"),
                        fileId = o.getString("file_id"),
                        submittedAt = o.getLong("submitted_at"),
                        status = o.optString("status", "submitted"),
                        total = o.optInt("total", 0),
                        done = o.optInt("done", 0),
                        outputFileId = o.optString("output_file_id", ""),
                        errorFileId = o.optString("error_file_id", "")
                    )
                )
            }
            list
        } catch (_: Exception) { mutableListOf() }
    }

    fun save(context: Context, tasks: List<BatchTask>) {
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(
                JSONObject().apply {
                    put("batch_id", t.batchId)
                    put("file_id", t.fileId)
                    put("submitted_at", t.submittedAt)
                    put("status", t.status)
                    put("total", t.total)
                    put("done", t.done)
                    put("output_file_id", t.outputFileId)
                    put("error_file_id", t.errorFileId)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, task: BatchTask) {
        val list = load(context)
        list.add(0, task)
        save(context, list)
    }

    fun update(context: Context, batchId: String, transform: (BatchTask) -> Unit) {
        val list = load(context)
        list.firstOrNull { it.batchId == batchId }?.let { transform(it) }
        save(context, list)
    }
}
