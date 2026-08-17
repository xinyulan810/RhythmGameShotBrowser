package com.rhythm.shots

import android.app.Activity
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.rhythm.shots.data.ShotDb

/**
 * 删除截图的统一流程：
 * - API 30+ 走系统删除确认框（MediaStore.createDeleteRequest，无需额外权限）；
 * - 低版本直接尝试删除；
 * 删除成功后自动清理数据库记录，并回调调用方刷新列表。
 */
class ShotDeleter(private val activity: AppCompatActivity) {

    private var pendingPath: String? = null
    private var onDeleted: (() -> Unit)? = null

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val path = pendingPath
            pendingPath = null
            val cb = onDeleted
            onDeleted = null
            if (result.resultCode == Activity.RESULT_OK && path != null) {
                ShotDb(activity).delete(path)
                cb?.invoke()
            }
        }

    /** 弹确认框 → 删除 → 成功后执行 [onSuccess]（调用方负责刷新界面） */
    fun delete(path: String, onSuccess: () -> Unit) {
        onDeleted = onSuccess
        AlertDialog.Builder(activity)
            .setTitle("删除截图")
            .setMessage("删除后该图片将从系统相册中移除，且不可恢复。确定删除？")
            .setPositiveButton("删除") { _, _ -> startDelete(path) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startDelete(path: String) {
        val sender = ShotActions.deleteRequest(activity, path)
        if (sender != null) {
            pendingPath = path
            launcher.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            val cb = onDeleted
            onDeleted = null
            if (ShotActions.deleteDirect(activity, path)) {
                ShotDb(activity).delete(path)
                cb?.invoke()
            } else {
                Toast.makeText(activity, "删除失败：请到系统相册中删除该图片", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
