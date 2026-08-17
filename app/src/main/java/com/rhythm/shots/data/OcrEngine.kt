package com.rhythm.shots.data

import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ML Kit OCR：识别截图中的文字（歌名 / 成绩等），中文模型同时覆盖中英字符。
 * 解码时采样缩小到 1600px 以内，显著提速。
 */
object OcrEngine {

    private const val MAX_DIM = 1600

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 返回识别到的全部文本（换行拼接） */
    suspend fun recognize(path: String): String = withContext(Dispatchers.IO) {
        try {
            val bitmap = decodeSampled(path) ?: return@withContext ""
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(recognizer.process(image))
            result.textBlocks.joinToString("\n") { it.text }
        } catch (_: Exception) {
            ""
        }
    }

    private fun decodeSampled(path: String): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIM || bounds.outHeight / sample > MAX_DIM) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts)
    }
}
