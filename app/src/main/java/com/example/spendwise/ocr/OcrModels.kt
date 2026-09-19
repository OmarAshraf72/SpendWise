package com.example.spendwise.ocr

import android.net.Uri

interface OcrEngine {
    suspend fun recognize(imageUri: Uri): OcrResult
}

interface NumericOcrEngine {
    suspend fun recognizeNumericCrops(
        imageUri: Uri,
        sourceResult: OcrResult,
        targets: List<PriceRecoveryTarget>
    ): List<NumericOcrCandidate>
}

data class OcrPoint(val x: Float, val y: Float)

data class OcrBoundingBox(val points: List<OcrPoint>) {
    val left: Float get() = points.minOfOrNull { it.x } ?: 0f
    val top: Float get() = points.minOfOrNull { it.y } ?: 0f
    val right: Float get() = points.maxOfOrNull { it.x } ?: 0f
    val bottom: Float get() = points.maxOfOrNull { it.y } ?: 0f
    val centerY: Float get() = (top + bottom) / 2f
}

data class OcrLine(
    val text: String,
    val confidence: Float?,
    val boundingBox: OcrBoundingBox?
)

data class OcrResult(
    val lines: List<OcrLine>,
    val fullText: String = lines.joinToString("\n") { it.text },
    val imageWidth: Int? = null,
    val imageHeight: Int? = null
)

data class NumericOcrCandidate(
    val itemIndex: Int,
    val text: String,
    val confidence: Float,
    val preprocessing: String,
    val boundingBox: OcrBoundingBox
)
