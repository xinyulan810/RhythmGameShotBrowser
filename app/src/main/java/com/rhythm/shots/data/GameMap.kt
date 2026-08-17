package com.rhythm.shots.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 包名 -> 游戏名 映射，支持用户自定义（存 SharedPreferences JSON，覆盖/新增默认表）。
 * 匹配大小写不敏感（vivo 截图文件名会保留应用进程名的实际大小写，如 moe.low.Arc6）。
 * 支持删除默认映射（删除黑名单，删除后重新加载不会复活）。
 */
object GameMap {

    private const val PREFS = "game_map"
    private const val KEY = "map_json"
    private const val KEY_DELETED = "deleted"

    val DEFAULT = linkedMapOf(
        "moe.low.arc" to "Arcaea",
        "moe.low.Arc6" to "Arcaea 6",
        "moe.eve.nex" to "EVE",
        "moe.eve.unx" to "EVE",
        "com.sega.pjsekai" to "世界计划",
        "com.PigeonGames.Phigros" to "Phigros",
        "com.silentgd.musedashcustomplay" to "Muse Dash",
        "org.flos.phira" to "Phira",
        "me.mugzone.malody" to "Malody",
        "me.tigerhix.cytoid" to "Cytoid",
        "com.FosFenes.Sonolus" to "Sonolus",
        "com.Reflektone.AstroDX" to "AstroDX",
        "com.TunerGames.Dynamite" to "Dynamite",
        "com.c4cat.dynamix" to "Dynamix",
        "com.tunergames.paradigm" to "Paradigm",
        "com.ilongyuan.cytus2.bilibili" to "Cytus II",
        "game.taptap.morizero.milthm" to "Milthm",
        "jp.co.craftegg.band" to "BanG Dream!",
        "game.qualiarts.hololive.dreams.jp" to "Hololive",
        "com.kms.worlddaistar" to "世界大明星",
        "com.tencent.game.rhythmmaster" to "节奏大师",
        "com.arcstar.overrapid" to "OverRapid",
        "icu.sanhei.mageki" to "Mageki",
        "com.YoStarJP.MajSoul" to "雀魂",
        "com.bilibili.star.bili" to "Bilibili Star",
        "com.hermes.mk.bilibili" to "Bilibili",
        "com.afteam.AFdan" to "AFdan",
        "com.akira.tyranoemu" to "Tyrano 模拟器"
    )

    /** 被用户删除的包名黑名单（用于删除默认映射） */
    fun deletedSet(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DELETED, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapTo(mutableSetOf()) { arr.getString(it) }
        } catch (_: Exception) { emptySet() }
    }

    /** 加载：默认表 - 删除黑名单 + 自定义覆盖 */
    fun load(context: Context): MutableMap<String, String> {
        val map = DEFAULT.toMutableMap()
        deletedSet(context).forEach { map.remove(it) }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return map
        return try {
            val obj = JSONObject(raw)
            val it = obj.keys()
            while (it.hasNext()) {
                val k = it.next()
                map[k] = obj.getString(k)
            }
            map
        } catch (_: Exception) {
            map
        }
    }

    fun save(context: Context, map: Map<String, String>) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        // 用户显式保存了这些包名，从删除黑名单中移除（防止删了又保存导致复活/矛盾）
        val del = deletedSet(context).toMutableSet()
        del.removeAll(map.keys)
        val arr = JSONArray()
        del.forEach { arr.put(it) }
        sp.edit()
            .putString(KEY, obj.toString())
            .putString(KEY_DELETED, arr.toString())
            .apply()
    }

    /** 删除一个映射：从自定义中移除，并加入删除黑名单（默认表项删除后不复活） */
    fun delete(context: Context, pkg: String) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 从自定义映射中移除
        val raw = sp.getString(KEY, null)
        if (raw != null) {
            try {
                val obj = JSONObject(raw)
                obj.remove(pkg)
                sp.edit().putString(KEY, obj.toString()).apply()
            } catch (_: Exception) {}
        }
        // 加入删除黑名单
        val del = deletedSet(context).toMutableSet()
        del.add(pkg)
        val arr = JSONArray()
        del.forEach { arr.put(it) }
        sp.edit().putString(KEY_DELETED, arr.toString()).apply()
    }

    /** 精确匹配失败时做大小写不敏感匹配 */
    fun resolve(context: Context, pkg: String): String {
        val map = load(context)
        map[pkg]?.let { return it }
        val lower = pkg.lowercase()
        map.entries.firstOrNull { it.key.lowercase() == lower }?.let { return it.value }
        return pkg
    }
}
