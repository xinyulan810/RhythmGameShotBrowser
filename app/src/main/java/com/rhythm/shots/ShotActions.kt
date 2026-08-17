package com.rhythm.shots

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.rhythm.shots.data.GameSettings
import com.rhythm.shots.data.ShotItem

/**
 * 截图通用操作：分享 / 复制识别文本 / 删除 / 长按操作菜单。
 */
object ShotActions {

    /** 由文件路径取 MediaStore content uri（截图来自媒体库扫描，正常都能查到） */
    fun mediaUri(context: Context, path: String): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DATA}=?"
        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, arrayOf(path), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 分享单张截图 */
    fun share(context: Context, path: String) {
        val uri = mediaUri(context, path)
        if (uri == null) {
            Toast.makeText(context, "图片不存在，无法分享", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享截图"))
    }

    /** 复制该截图的 OCR / VLM 识别文本到剪贴板 */
    fun copyOcr(context: Context, item: ShotItem) {
        val text = item.ocrText.ifBlank { item.vlmText }
        if (text.isBlank()) {
            Toast.makeText(context, "该截图暂无识别文本", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("OCR", text))
        Toast.makeText(context, "已复制识别文本", Toast.LENGTH_SHORT).show()
    }

    /**
     * API 30+ 的系统删除请求（会弹用户确认框）；低版本返回 null，
     * 由调用方 fallback 到 [deleteDirect]。
     */
    fun deleteRequest(context: Context, path: String): IntentSender? {
        if (Build.VERSION.SDK_INT < 30) return null
        val uri = mediaUri(context, path) ?: return null
        return try {
            MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 低版本直接删除：API 29 走 MediaStore delete，更老走 File.delete。
     * 无写入权限时可能失败，返回 false。
     */
    fun deleteDirect(context: Context, path: String): Boolean {
        return try {
            val uri = mediaUri(context, path)
            if (uri != null && Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.delete(uri, null, null) > 0
            } else {
                java.io.File(path).delete()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 长按图片弹出的操作菜单：分享 / 复制识别文本 / 删除 */
    fun showActionsMenu(context: Context, item: ShotItem, onDelete: () -> Unit) {
        val items = arrayOf("分享", "复制识别文本", "删除")
        val title = buildString {
            val song = if (item.songName.isNotBlank()) item.songName else item.songCn
            if (song.isNotBlank()) append(song) else append(item.displayName)
            val game = GameSettings.effectiveName(context, item.gameName)
            if (game.isNotBlank()) append(" · ").append(game)
        }
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> share(context, item.path)
                    1 -> copyOcr(context, item)
                    2 -> onDelete()
                }
            }
            .show()
    }
}
