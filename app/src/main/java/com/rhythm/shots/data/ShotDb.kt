package com.rhythm.shots.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 截图索引库：path / 包名 / 游戏名 / 日期 / OCR 文本 / VLM 大模型识别字段。
 */
class ShotDb(context: Context) : SQLiteOpenHelper(context, "shots.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE shots (" +
                "path TEXT PRIMARY KEY," +
                "display_name TEXT," +
                "pkg TEXT," +
                "game TEXT," +
                "date TEXT," +
                "ocr_text TEXT DEFAULT ''," +
                "indexed INTEGER DEFAULT 0," +
                "song_name TEXT DEFAULT ''," +
                "song_cn TEXT DEFAULT ''," +
                "difficulty TEXT DEFAULT ''," +
                "score TEXT DEFAULT ''," +
                "accuracy TEXT DEFAULT ''," +
                "rank TEXT DEFAULT ''," +
                "combo TEXT DEFAULT ''," +
                "vlm_text TEXT DEFAULT ''," +
                "vlm_game TEXT DEFAULT ''," +
                "vlm_done INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_date ON shots(date)")
        db.execSQL("CREATE INDEX idx_game ON shots(game)")
        db.execSQL("CREATE INDEX idx_ocr ON shots(ocr_text)")
        db.execSQL("CREATE INDEX idx_song ON shots(song_name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val cols = listOf(
                "song_name", "song_cn", "difficulty", "score",
                "accuracy", "rank", "combo", "vlm_text", "vlm_game"
            )
            cols.forEach { col ->
                try {
                    db.execSQL("ALTER TABLE shots ADD COLUMN $col TEXT DEFAULT ''")
                } catch (_: Exception) { /* 已存在 */ }
            }
            try {
                db.execSQL("ALTER TABLE shots ADD COLUMN vlm_done INTEGER DEFAULT 0")
            } catch (_: Exception) {}
        }
    }

    /** 新扫描结果入库，已存在的不覆盖（保留 OCR/VLM 结果） */
    fun upsertAll(items: List<ShotItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach {
                val cv = ContentValues().apply {
                    put("path", it.path)
                    put("display_name", it.displayName)
                    put("pkg", it.pkg)
                    put("game", it.gameName)
                    put("date", it.date)
                }
                db.insertWithOnConflict("shots", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateOcr(path: String, text: String) {
        val cv = ContentValues().apply {
            put("ocr_text", text)
            put("indexed", 1)
        }
        writableDatabase.update("shots", cv, "path=?", arrayOf(path))
    }

    /** 删除一条截图记录（文件已从媒体库移除后调用） */
    fun delete(path: String) {
        writableDatabase.delete("shots", "path=?", arrayOf(path))
    }

    /** 映射表变化后重建所有截图的 game 字段（让改名/新增映射立即生效） */
    fun rebuildGameNames(context: Context) {
        val db = writableDatabase
        val map = GameMap.load(context)
        val lowerMap = map.entries.associate { it.key.lowercase() to it.value }
        val rows = mutableListOf<Pair<String, String>>() // path, pkg
        readableDatabase.rawQuery("SELECT path, pkg FROM shots", null).use { c ->
            while (c.moveToNext()) {
                val pkg = c.getString(1) ?: continue
                if (pkg.isNotBlank()) rows.add(c.getString(0) to pkg)
            }
        }
        if (rows.isEmpty()) return
        db.beginTransaction()
        try {
            rows.forEach { (path, pkg) ->
                val game = map[pkg] ?: lowerMap[pkg.lowercase()] ?: pkg
                val cv = ContentValues().apply { put("game", game) }
                db.update("shots", cv, "path=? AND game<>?", arrayOf(path, game))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateVlm(path: String, r: VlmResult) {
        val cv = ContentValues().apply {
            put("song_name", r.song)
            put("song_cn", r.songCn)
            put("difficulty", r.difficulty)
            put("score", r.score)
            put("accuracy", r.accuracy)
            put("rank", r.rank)
            put("combo", r.combo)
            put("vlm_text", r.fullText)
            put("vlm_game", r.game)
            put("vlm_done", 1)
        }
        writableDatabase.update("shots", cv, "path=?", arrayOf(path))
    }

    /** 待 OCR 的路径，分批返回直到全部处理完 */
    fun pendingOcrPaths(limit: Int = 200): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT path FROM shots WHERE indexed=0 ORDER BY date DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun ocrPendingCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM shots WHERE indexed=0", null
        ).use { c ->
            if (c.moveToNext()) return c.getInt(0)
        }
        return 0
    }

    /** 待 VLM 识别的路径（vlm_done=0），分批 */
    fun vlmPendingPaths(limit: Int = 10): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT path FROM shots WHERE vlm_done=0 ORDER BY date DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun vlmPendingCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM shots WHERE vlm_done=0", null
        ).use { c ->
            if (c.moveToNext()) return c.getInt(0)
        }
        return 0
    }

    private fun rowToItem(c: android.database.Cursor): ShotItem = ShotItem(
        c.getString(0), c.getString(1), c.getString(2),
        c.getString(3), c.getString(4), c.getString(5) ?: "",
        c.getString(6) ?: "", c.getString(7) ?: "", c.getString(8) ?: "",
        c.getString(9) ?: "", c.getString(10) ?: "", c.getString(11) ?: "",
        c.getString(12) ?: "", c.getString(13) ?: "", c.getString(14) ?: "",
        c.getInt(15) == 1
    )

    fun all(): List<ShotItem> {
        val out = mutableListOf<ShotItem>()
        readableDatabase.rawQuery(
            "SELECT path, display_name, pkg, game, date, ocr_text, " +
                "song_name, song_cn, difficulty, score, accuracy, rank, combo, " +
                "vlm_text, vlm_game, vlm_done FROM shots ORDER BY date DESC",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(rowToItem(c))
        }
        return out
    }

    fun search(q: String): List<ShotItem> {
        val out = mutableListOf<ShotItem>()
        val like = "%$q%"
        readableDatabase.rawQuery(
            "SELECT path, display_name, pkg, game, date, ocr_text, " +
                "song_name, song_cn, difficulty, score, accuracy, rank, combo, " +
                "vlm_text, vlm_game, vlm_done FROM shots " +
                "WHERE ocr_text LIKE ? OR display_name LIKE ? OR game LIKE ? " +
                "OR song_name LIKE ? OR song_cn LIKE ? OR vlm_text LIKE ? " +
                "ORDER BY date DESC",
            arrayOf(like, like, like, like, like, like)
        ).use { c ->
            while (c.moveToNext()) out.add(rowToItem(c))
        }
        return out
    }
}

/** VLM 识别结果 */
data class VlmResult(
    val game: String = "",
    val song: String = "",
    val songCn: String = "",
    val difficulty: String = "",
    val score: String = "",
    val accuracy: String = "",
    val rank: String = "",
    val combo: String = "",
    val fullText: String = ""
)
