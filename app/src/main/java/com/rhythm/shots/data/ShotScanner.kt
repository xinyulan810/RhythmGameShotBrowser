package com.rhythm.shots.data

import android.content.Context
import android.provider.MediaStore
import java.util.regex.Pattern

/**
 * 扫描系统截图。
 * 识别 vivo 截图格式：Screenshot_20250112_004027_moe.low.arc.jpg
 * （文件名含来源应用包名）
 */
object ShotScanner {

    private val NAME_RE = Pattern.compile(
        "^Screenshot_(\\d{8})_(\\d{6})_(.+)\\.(jpg|jpeg|png|webp)$",
        Pattern.CASE_INSENSITIVE
    )

    data class Parsed(val pkg: String, val date: String)

    fun parseFileName(name: String): Parsed? {
        val m = NAME_RE.matcher(name)
        if (!m.matches()) return null
        return Parsed(pkg = m.group(3), date = m.group(1))
    }

    /** 用 MediaStore 查询 Screenshots 目录下的截图 */
    fun scan(context: Context): List<ShotItem> {
        val list = mutableListOf<ShotItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("Screenshot_%")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args, sort
        )?.use { c ->
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iData = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (c.moveToNext()) {
                val name = c.getString(iName) ?: continue
                val parsed = parseFileName(name) ?: continue
                val data = c.getString(iData)
                if (data == null || !data.contains("Screenshots")) continue
                list.add(
                    ShotItem(
                        path = data,
                        displayName = name,
                        pkg = parsed.pkg,
                        gameName = GameMap.resolve(context, parsed.pkg),
                        date = parsed.date
                    )
                )
            }
        }
        return list
    }
}
