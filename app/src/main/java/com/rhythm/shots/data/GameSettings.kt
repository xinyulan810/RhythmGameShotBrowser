package com.rhythm.shots.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 游戏显示设置：隐藏游戏 + 合并游戏（如 Arcaea 6 → Arcaea）。
 * 展示层生效，不改数据库原始 game 字段。
 */
object GameSettings {

    private const val PREFS = "game_settings"
    private const val KEY_HIDDEN = "hidden"
    private const val KEY_MERGED = "merged"

    /** 隐藏的游戏名集合 */
    fun hiddenGames(context: Context): MutableSet<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_HIDDEN, null) ?: return mutableSetOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapTo(mutableSetOf()) { arr.getString(it) }
        } catch (_: Exception) { mutableSetOf() }
    }

    fun setHidden(context: Context, games: Set<String>) {
        val arr = JSONArray()
        games.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HIDDEN, arr.toString()).apply()
    }

    /** 合并映射：原游戏名 → 目标游戏名 */
    fun mergedGames(context: Context): MutableMap<String, String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_MERGED, null) ?: return mutableMapOf()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                map[k] = obj.getString(k)
            }
            map
        } catch (_: Exception) { mutableMapOf() }
    }

    fun setMerged(context: Context, map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MERGED, obj.toString()).apply()
    }

    /** 应用合并映射后的游戏名 */
    fun effectiveName(context: Context, game: String): String {
        val merged = mergedGames(context)
        // 链式合并：A→B，B→C 则 A→C
        var cur = game
        val seen = mutableSetOf<String>()
        while (merged.containsKey(cur) && seen.add(cur)) {
            cur = merged[cur] ?: break
        }
        return cur
    }

    /** 可见游戏列表（应用合并+隐藏，去重排序） */
    fun effectiveGameList(context: Context, allGames: List<String>): List<String> {
        val hidden = hiddenGames(context)
        val mapped = allGames.map { effectiveName(context, it) }
        return mapped.filter { it !in hidden }.distinct().sorted()
    }
}
