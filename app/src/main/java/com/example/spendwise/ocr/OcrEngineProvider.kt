package com.example.spendwise.ocr

import android.content.Context

object OcrEngineProvider {
    @Volatile
    private var instance: OcrEngine? = null

    fun get(context: Context): OcrEngine = instance ?: synchronized(this) {
        instance ?: PaddleOcrEngine(context.applicationContext).also { instance = it }
    }
}
