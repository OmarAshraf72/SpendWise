package com.example.spendwise.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.baidu.paddle.lite.MobileConfig
import com.baidu.paddle.lite.PaddlePredictor
import com.baidu.paddle.lite.PowerMode
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PaddleOcrEngine(private val context: Context) : OcrEngine, NumericOcrEngine {
    private val inferenceMutex = Mutex()
    private var detector: PaddlePredictor? = null
    private var recognizer: PaddlePredictor? = null
    private var labels: List<String>? = null

    override suspend fun recognize(imageUri: Uri): OcrResult = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            ensureLoaded()
            val original = decodeBitmap(imageUri, MAX_IMAGE_SIDE)
                ?: throw IllegalArgumentException("Receipt image could not be decoded")
            try {
                val boxes = detectText(original)
                val lines = boxes.mapNotNull { box -> recognizeBox(original, box) }
                    .filter { it.text.isNotBlank() }
                    .sortedWith(compareBy<OcrLine> { it.boundingBox?.top ?: 0f }
                        .thenBy { it.boundingBox?.left ?: 0f })
                OcrResult(lines, imageWidth = original.width, imageHeight = original.height)
            } finally {
                original.recycle()
            }
        }
    }

    override suspend fun recognizeNumericCrops(
        imageUri: Uri,
        sourceResult: OcrResult,
        targets: List<PriceRecoveryTarget>
    ): List<NumericOcrCandidate> = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            ensureLoaded()
            val original = decodeBitmap(imageUri, maxSide = null)
                ?: throw IllegalArgumentException("Receipt image could not be decoded")
            try {
                val sourceWidth = sourceResult.imageWidth?.coerceAtLeast(1) ?: original.width
                val sourceHeight = sourceResult.imageHeight?.coerceAtLeast(1) ?: original.height
                val scaleX = original.width.toFloat() / sourceWidth
                val scaleY = original.height.toFloat() / sourceHeight
                buildList {
                    targets.forEach targetLoop@ { target ->
                        val sourceBox = recoveryBox(target) ?: return@targetLoop
                        val cropBox = sourceBox.scaledToPixels(scaleX, scaleY, original.width, original.height)
                        val crop = Bitmap.createBitmap(
                            original,
                            cropBox.left,
                            cropBox.top,
                            (cropBox.right - cropBox.left).coerceAtLeast(1),
                            (cropBox.bottom - cropBox.top).coerceAtLeast(1)
                        )
                        try {
                            numericVariants(crop).forEach variantLoop@ { variant ->
                                try {
                                    val line = recognizeBox(
                                        variant.bitmap,
                                        PixelBox(0, 0, variant.bitmap.width, variant.bitmap.height)
                                    ) ?: return@variantLoop
                                    add(
                                        NumericOcrCandidate(
                                            itemIndex = target.itemIndex,
                                            text = line.text,
                                            confidence = line.confidence ?: 0f,
                                            preprocessing = variant.name,
                                            boundingBox = target.priceBoundingBox ?: sourceBox
                                        )
                                    )
                                } finally {
                                    variant.bitmap.recycle()
                                }
                            }
                        } finally {
                            crop.recycle()
                        }
                    }
                }
            } finally {
                original.recycle()
            }
        }
    }

    private fun ensureLoaded() {
        if (detector != null && recognizer != null && labels != null) return
        val modelDirectory = File(context.filesDir, "paddle_ocr").apply { mkdirs() }
        val detectorFile = copyAsset("models/det_db.nb", File(modelDirectory, "det_db.nb"))
        val recognizerFile = copyAsset("models/rec_crnn.nb", File(modelDirectory, "rec_crnn.nb"))
        detector = createPredictor(detectorFile)
        recognizer = createPredictor(recognizerFile)
        labels = buildList {
            add("") // CTC blank token
            context.assets.open("labels/arabic_dict.txt").bufferedReader(Charsets.UTF_8).useLines { sequence ->
                sequence.forEach { add(it.trimEnd('\r')) }
            }
            add(" ")
        }
    }

    private fun createPredictor(model: File): PaddlePredictor {
        val config = MobileConfig().apply {
            setModelFromFile(model.absolutePath)
            setThreads(min(4, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)))
            setPowerMode(PowerMode.LITE_POWER_HIGH)
        }
        return checkNotNull(PaddlePredictor.createPaddlePredictor(config)) {
            "Paddle Lite could not load ${model.name}"
        }
    }

    private fun copyAsset(assetPath: String, destination: File): File {
        if (!destination.exists() || destination.length() == 0L) {
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            context.assets.open(assetPath).use { input ->
                temporary.outputStream().use(input::copyTo)
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        }
        return destination
    }

    private fun decodeBitmap(uri: Uri, maxSide: Int?): Bitmap? {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val scale = maxSide?.let { min(1f, it.toFloat() / max(width, height)) } ?: 1f
                if (maxSide != null && scale < 1f) {
                    decoder.setTargetSize((width * scale).roundToInt(), (height * scale).roundToInt())
                }
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        }
        return decoded?.copy(Bitmap.Config.ARGB_8888, false)?.also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private fun recoveryBox(target: PriceRecoveryTarget): OcrBoundingBox? {
        val row = target.rowBoundingBox
        val price = target.priceBoundingBox
        if (price != null) {
            val rowHeight = row?.let { (it.bottom - it.top).coerceAtLeast(1f) }
                ?: (price.bottom - price.top).coerceAtLeast(1f)
            val horizontalPadding = rowHeight * 0.8f
            val verticalPadding = rowHeight * 0.35f
            return rectangle(
                price.left - horizontalPadding,
                price.top - verticalPadding,
                price.right + horizontalPadding,
                price.bottom + verticalPadding
            )
        }
        if (row != null && target.expectedColumnX != null) {
            val rowHeight = (row.bottom - row.top).coerceAtLeast(1f)
            return rectangle(
                target.expectedColumnX - rowHeight * 2.8f,
                row.top - rowHeight * 0.25f,
                target.expectedColumnX + rowHeight * 2.8f,
                row.bottom + rowHeight * 0.25f
            )
        }
        return null
    }

    private fun OcrBoundingBox.scaledToPixels(
        scaleX: Float,
        scaleY: Float,
        width: Int,
        height: Int
    ): PixelBox {
        val left = (this.left * scaleX).roundToInt().coerceIn(0, width - 1)
        val top = (this.top * scaleY).roundToInt().coerceIn(0, height - 1)
        val right = (this.right * scaleX).roundToInt().coerceIn(left + 1, width)
        val bottom = (this.bottom * scaleY).roundToInt().coerceIn(top + 1, height)
        return PixelBox(left, top, right, bottom)
    }

    private fun numericVariants(crop: Bitmap): List<PreprocessedBitmap> = listOf(
        PreprocessedBitmap("grayscale_3x", upscale(toGrayscale(crop), 3)),
        PreprocessedBitmap("contrast_3x", upscale(normalizeContrast(crop), 3)),
        PreprocessedBitmap("sharpened_3x", upscale(sharpen(crop), 3)),
        PreprocessedBitmap("adaptive_threshold_4x", upscale(adaptiveThreshold(crop), 4))
    )

    private fun upscale(bitmap: Bitmap, factor: Int): Bitmap {
        val scaled = Bitmap.createScaledBitmap(bitmap, bitmap.width * factor, bitmap.height * factor, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun toGrayscale(source: Bitmap): Bitmap = transformGrayscale(source) { value, _, _ -> value }

    private fun normalizeContrast(source: Bitmap): Bitmap {
        val grayscale = grayscaleValues(source)
        val low = grayscale.minOrNull() ?: 0
        val high = grayscale.maxOrNull() ?: 255
        val range = (high - low).coerceAtLeast(1)
        return bitmapFromGray(source.width, source.height, IntArray(grayscale.size) { index ->
            ((grayscale[index] - low) * 255 / range).coerceIn(0, 255)
        })
    }

    private fun sharpen(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val input = grayscaleValues(source)
        val output = input.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                output[index] = (input[index] * 5 - input[index - 1] - input[index + 1] -
                    input[index - width] - input[index + width]).coerceIn(0, 255)
            }
        }
        return bitmapFromGray(width, height, output)
    }

    private fun adaptiveThreshold(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val input = grayscaleValues(source)
        val integral = LongArray((width + 1) * (height + 1))
        for (y in 1..height) {
            var rowSum = 0L
            for (x in 1..width) {
                rowSum += input[(y - 1) * width + x - 1]
                integral[y * (width + 1) + x] = integral[(y - 1) * (width + 1) + x] + rowSum
            }
        }
        val radius = max(4, min(width, height) / 8)
        val output = IntArray(input.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val left = (x - radius).coerceAtLeast(0)
                val top = (y - radius).coerceAtLeast(0)
                val right = (x + radius + 1).coerceAtMost(width)
                val bottom = (y + radius + 1).coerceAtMost(height)
                val sum = integral[bottom * (width + 1) + right] - integral[top * (width + 1) + right] -
                    integral[bottom * (width + 1) + left] + integral[top * (width + 1) + left]
                val mean = sum / ((right - left) * (bottom - top)).coerceAtLeast(1)
                output[y * width + x] = if (input[y * width + x] < mean - 8) 0 else 255
            }
        }
        return bitmapFromGray(width, height, output)
    }

    private fun transformGrayscale(source: Bitmap, transform: (Int, Int, Int) -> Int): Bitmap {
        val values = grayscaleValues(source)
        return bitmapFromGray(source.width, source.height, IntArray(values.size) { index ->
            transform(values[index], index % source.width, index / source.width).coerceIn(0, 255)
        })
    }

    private fun grayscaleValues(source: Bitmap): IntArray {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return IntArray(pixels.size) { index ->
            val color = pixels[index]
            (((color shr 16 and 0xFF) * 299 + (color shr 8 and 0xFF) * 587 + (color and 0xFF) * 114) / 1000)
        }
    }

    private fun bitmapFromGray(width: Int, height: Int, values: IntArray): Bitmap {
        val colors = IntArray(values.size) { index ->
            val value = values[index].coerceIn(0, 255)
            (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun rectangle(left: Float, top: Float, right: Float, bottom: Float) = OcrBoundingBox(
        listOf(OcrPoint(left, top), OcrPoint(right, top), OcrPoint(right, bottom), OcrPoint(left, bottom))
    )

    private fun detectText(original: Bitmap): List<PixelBox> {
        val scale = min(1f, DETECTOR_MAX_SIDE.toFloat() / max(original.width, original.height))
        val targetWidth = alignedDimension((original.width * scale).roundToInt())
        val targetHeight = alignedDimension((original.height * scale).roundToInt())
        val resized = Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true)
        try {
            val pixels = IntArray(targetWidth * targetHeight)
            resized.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
            val planeSize = pixels.size
            val inputData = FloatArray(planeSize * 3)
            pixels.forEachIndexed { index, color ->
                inputData[index] = (((color shr 16) and 0xFF) / 255f - 0.485f) / 0.229f
                inputData[planeSize + index] = (((color shr 8) and 0xFF) / 255f - 0.456f) / 0.224f
                inputData[planeSize * 2 + index] = ((color and 0xFF) / 255f - 0.406f) / 0.225f
            }
            val predictor = checkNotNull(detector)
            check(predictor.getInput(0).resize(longArrayOf(1, 3, targetHeight.toLong(), targetWidth.toLong())))
            check(predictor.getInput(0).setData(inputData))
            check(predictor.run())
            val output = predictor.getOutput(0)
            val shape = output.shape()
            val outputHeight = shape[shape.size - 2].toInt()
            val outputWidth = shape[shape.size - 1].toInt()
            val probabilities = output.floatData
            val scaleX = original.width.toFloat() / outputWidth
            val scaleY = original.height.toFloat() / outputHeight
            return connectedTextBoxes(probabilities, outputWidth, outputHeight)
                .map { it.scaled(scaleX, scaleY, original.width, original.height) }
                .sortedWith(compareBy<PixelBox> { it.top }.thenBy { it.left })
        } finally {
            if (resized !== original) resized.recycle()
        }
    }

    private fun connectedTextBoxes(probabilities: FloatArray, width: Int, height: Int): List<PixelBox> {
        val size = min(probabilities.size, width * height)
        val visited = BooleanArray(size)
        val queue = IntArray(size)
        val found = mutableListOf<PixelBox>()
        for (start in 0 until size) {
            if (visited[start] || probabilities[start] < DETECTION_THRESHOLD) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            var count = 0
            var score = 0f
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                left = min(left, x)
                top = min(top, y)
                right = max(right, x)
                bottom = max(bottom, y)
                score += probabilities[index]
                count++
                fun visit(next: Int) {
                    if (next in 0 until size && !visited[next] && probabilities[next] >= DETECTION_THRESHOLD) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
                if (x > 0) visit(index - 1)
                if (x + 1 < width) visit(index + 1)
                if (y > 0) visit(index - width)
                if (y + 1 < height) visit(index + width)
            }
            val boxWidth = right - left + 1
            val boxHeight = bottom - top + 1
            if (count >= MIN_COMPONENT_PIXELS && score / count >= BOX_SCORE_THRESHOLD &&
                boxWidth >= MIN_TEXT_SIDE && boxHeight >= MIN_TEXT_SIDE
            ) {
                val horizontalPadding = max(2, (boxHeight * 0.45f).roundToInt())
                val verticalPadding = max(1, (boxHeight * 0.18f).roundToInt())
                found += PixelBox(
                    (left - horizontalPadding).coerceAtLeast(0),
                    (top - verticalPadding).coerceAtLeast(0),
                    (right + horizontalPadding).coerceAtMost(width - 1),
                    (bottom + verticalPadding).coerceAtMost(height - 1)
                )
            }
        }
        return found.sortedByDescending { it.area }.take(MAX_TEXT_BOXES)
    }

    private fun recognizeBox(original: Bitmap, box: PixelBox): OcrLine? {
        val cropWidth = (box.right - box.left).coerceAtLeast(1)
        val cropHeight = (box.bottom - box.top).coerceAtLeast(1)
        var crop = Bitmap.createBitmap(original, box.left, box.top, cropWidth, cropHeight)
        if (crop.height > crop.width * 1.5f) {
            val matrix = android.graphics.Matrix().apply { postRotate(90f) }
            val rotated = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, matrix, true)
            crop.recycle()
            crop = rotated
        }
        try {
            val ratio = crop.width.toFloat() / crop.height.coerceAtLeast(1)
            val targetWidth = ceil(RECOGNITION_HEIGHT * ratio).toInt()
                .coerceIn(MIN_RECOGNITION_WIDTH, MAX_RECOGNITION_WIDTH)
            val resized = Bitmap.createScaledBitmap(crop, targetWidth, RECOGNITION_HEIGHT, true)
            try {
                val pixels = IntArray(targetWidth * RECOGNITION_HEIGHT)
                resized.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, RECOGNITION_HEIGHT)
                val planeSize = pixels.size
                val inputData = FloatArray(planeSize * 3)
                pixels.forEachIndexed { index, color ->
                    inputData[index] = (((color shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
                    inputData[planeSize + index] = (((color shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
                    inputData[planeSize * 2 + index] = ((color and 0xFF) / 255f - 0.5f) / 0.5f
                }
                val predictor = checkNotNull(recognizer)
                check(predictor.getInput(0).resize(longArrayOf(1, 3, RECOGNITION_HEIGHT.toLong(), targetWidth.toLong())))
                check(predictor.getInput(0).setData(inputData))
                check(predictor.run())
                val output = predictor.getOutput(0)
                val shape = output.shape()
                if (shape.size < 3) return null
                val steps = shape[shape.size - 2].toInt()
                val classes = shape[shape.size - 1].toInt()
                val values = output.floatData
                val decoded = StringBuilder()
                var previous = -1
                var confidenceSum = 0f
                var characterCount = 0
                val currentLabels = checkNotNull(labels)
                for (step in 0 until steps) {
                    val offset = step * classes
                    var bestIndex = 0
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (candidate in 0 until classes) {
                        val value = values.getOrElse(offset + candidate) { Float.NEGATIVE_INFINITY }
                        if (value > bestScore) {
                            bestScore = value
                            bestIndex = candidate
                        }
                    }
                    if (bestIndex > 0 && bestIndex != previous && bestIndex < currentLabels.size) {
                        decoded.append(currentLabels[bestIndex])
                        confidenceSum += bestScore
                        characterCount++
                    }
                    previous = bestIndex
                }
                if (characterCount == 0) return null
                val text = reverseArabicPrediction(decoded.toString()).trim()
                if (text.isBlank()) return null
                return OcrLine(
                    text = text,
                    confidence = confidenceSum / characterCount,
                    boundingBox = box.toOcrBoundingBox()
                )
            } finally {
                if (resized !== crop) resized.recycle()
            }
        } finally {
            crop.recycle()
        }
    }

    private fun reverseArabicPrediction(value: String): String {
        val chunks = mutableListOf<String>()
        val latin = StringBuilder()
        fun flushLatin() {
            if (latin.isNotEmpty()) {
                chunks += latin.toString()
                latin.clear()
            }
        }
        value.forEach { character ->
            if (character.isLetterOrDigit() && character.code < 128 || character in " :*./%+-") {
                latin.append(character)
            } else {
                flushLatin()
                chunks += character.toString()
            }
        }
        flushLatin()
        return chunks.asReversed().joinToString("")
    }

    private fun alignedDimension(value: Int): Int = max(32, value / 32 * 32)

    private data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val area: Int get() = (right - left + 1) * (bottom - top + 1)

        fun scaled(scaleX: Float, scaleY: Float, maxWidth: Int, maxHeight: Int): PixelBox = PixelBox(
            (left * scaleX).roundToInt().coerceIn(0, maxWidth - 1),
            (top * scaleY).roundToInt().coerceIn(0, maxHeight - 1),
            ((right + 1) * scaleX).roundToInt().coerceIn(1, maxWidth),
            ((bottom + 1) * scaleY).roundToInt().coerceIn(1, maxHeight)
        )

        fun toOcrBoundingBox() = OcrBoundingBox(
            listOf(
                OcrPoint(left.toFloat(), top.toFloat()),
                OcrPoint(right.toFloat(), top.toFloat()),
                OcrPoint(right.toFloat(), bottom.toFloat()),
                OcrPoint(left.toFloat(), bottom.toFloat())
            )
        )
    }

    private data class PreprocessedBitmap(val name: String, val bitmap: Bitmap)

    private companion object {
        const val MAX_IMAGE_SIDE = 1800
        const val DETECTOR_MAX_SIDE = 960
        const val RECOGNITION_HEIGHT = 48
        const val MIN_RECOGNITION_WIDTH = 16
        const val MAX_RECOGNITION_WIDTH = 320
        const val DETECTION_THRESHOLD = 0.3f
        const val BOX_SCORE_THRESHOLD = 0.5f
        const val MIN_COMPONENT_PIXELS = 10
        const val MIN_TEXT_SIDE = 3
        const val MAX_TEXT_BOXES = 160
    }
}
