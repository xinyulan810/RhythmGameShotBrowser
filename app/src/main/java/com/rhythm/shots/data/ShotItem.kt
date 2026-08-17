package com.rhythm.shots.data

/** 一条截图记录（含 VLM 大模型识别结果字段） */
data class ShotItem(
    val path: String,
    val displayName: String,
    val pkg: String,
    val gameName: String,
    val date: String, // yyyyMMdd
    val ocrText: String = "",
    // VLM 字段
    val songName: String = "",
    val songCn: String = "",
    val difficulty: String = "",
    val score: String = "",
    val accuracy: String = "",
    val rank: String = "",
    val combo: String = "",
    val vlmText: String = "",
    val vlmGame: String = "",
    val vlmDone: Boolean = false
)
