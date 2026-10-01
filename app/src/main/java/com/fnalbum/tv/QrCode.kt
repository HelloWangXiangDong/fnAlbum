package com.fnalbum.tv

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 二维码位图生成（纯 zxing core，无第三方 UI 依赖） */
object QrCode {

    fun bitmap(
        text: String,
        sizePx: Int,
        fg: Int = Color.BLACK,
        bg: Int = Color.WHITE,
        margin: Int = 1
    ): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to margin,
            // 屏幕上显示，不需要抗污损，用低纠错换更大的模块（更好扫）
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) fg else bg
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}
