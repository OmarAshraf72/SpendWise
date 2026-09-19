package com.example.spendwise.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaddleOcrEngineTest {
    @Test
    fun recognizesSyntheticReceiptOnDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val imageFile = File(context.cacheDir, "ocr_smoke_receipt.png")
        val bitmap = Bitmap.createBitmap(900, 1100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 58f
        }
        listOf(
            "CARREFOUR",
            "19/09/2026",
            "Milk                         65.00",
            "Panadol                      80.00",
            "TOTAL                       145.00"
        ).forEachIndexed { index, line ->
            canvas.drawText(line, 55f, 140f + index * 150f, paint)
        }
        imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val result = OcrEngineProvider.get(context).recognize(Uri.fromFile(imageFile))

        assertTrue("Expected PaddleOCR to detect at least one text line", result.lines.isNotEmpty())
    }
}
