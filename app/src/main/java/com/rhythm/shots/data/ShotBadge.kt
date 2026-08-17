package com.rhythm.shots.data

/** 截图特别标识：FC（全连，含 Full Combo / Full Recall） / AP（全完美，含 All Perfect / Pure Memory） */
enum class ShotBadge(val label: String) {
    NONE(""),
    FC("FC"),
    AP("AP")
}

/**
 * 从识别结果判断该截图是否有 FC / AP 标识：
 * 优先看 VLM 的 rank 字段（可能直接是 FC/AP/FR/PM），
 * 再看 OCR / VLM 全文里是否出现 FULL COMBO / FULL RECALL / ALL PERFECT / PURE MEMORY 等字样
 * （统一标 FC / AP，full recall 也算 FC）。
 */
fun ShotItem.badge(): ShotBadge {
    val rankU = rank.uppercase().trim()
    if (rankU == "AP" || rankU == "PM" || rankU == "ALL PERFECT" || rankU == "PURE MEMORY") {
        return ShotBadge.AP
    }
    if (rankU == "FC" || rankU == "FR" || rankU == "FULL COMBO" || rankU == "FULL RECALL") {
        return ShotBadge.FC
    }

    // 忽略空白/大小写后匹配（兼容 OCR 换行、多余空格）
    val text = buildString {
        append(ocrText); append(' '); append(vlmText)
    }.uppercase().replace(" ", "")

    return when {
        text.contains("ALLPERFECT") || text.contains("PUREMEMORY") -> ShotBadge.AP
        text.contains("FULLCOMBO") || text.contains("FULLRECALL") -> ShotBadge.FC
        else -> ShotBadge.NONE
    }
}
