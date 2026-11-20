package com.yuji.app

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ReceiptOcr {
    suspend fun read(context: Context, uri: Uri): ReceiptFields = withContext(Dispatchers.IO) {
        ReceiptImage.read(context.contentResolver, uri).let { bitmap ->
            try {
                val text = recognize(context, bitmap)
                if (text.isBlank()) ReceiptFields(warning = "图片上没有识别到文字，可以手动填写。")
                else ReceiptReader.read(text)
            } finally {
                bitmap.recycle()
            }
        }
    }

    internal fun recognize(context: Context, bitmap: Bitmap): String {
        val directory = installModels(context)
        val recognizer = TessBaseAPI()
        try {
            check(recognizer.init(directory.absolutePath, "chi_sim+eng", TessBaseAPI.OEM_LSTM_ONLY))
            recognizer.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            recognizer.setImage(bitmap)
            return recognizer.getUTF8Text().orEmpty()
        } finally {
            recognizer.recycle()
        }
    }

    @Synchronized
    private fun installModels(context: Context): File {
        val directory = File(context.filesDir, "receipt-ocr-4.1.0")
        val tessdata = File(directory, "tessdata")
        check(tessdata.isDirectory || tessdata.mkdirs())
        for (model in listOf("chi_sim.traineddata", "eng.traineddata")) {
            val target = File(tessdata, model)
            if (target.isFile && target.length() > 0) continue
            val temporary = File.createTempFile("model-", ".part", tessdata)
            try {
                context.assets.open("tessdata/$model").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(temporary.renameTo(target))
            } finally {
                temporary.delete()
            }
        }
        return directory
    }
}

internal object ReceiptImage {
    fun read(resolver: ContentResolver, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height))
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val orientation = resolver.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        }
        val bitmap = requireNotNull(resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) })
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90f
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180f
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270f
            else -> 0f
        }
        val mirrored = orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL ||
            orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE
        if (rotation == 0f && !mirrored) return bitmap
        try {
            val transform = Matrix().apply {
                if (mirrored) postScale(-1f, 1f)
                postRotate(rotation)
            }
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
            if (oriented !== bitmap) bitmap.recycle()
            return oriented
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > 4096 || (width.toLong() / sample) * (height / sample) > 8_000_000) {
            sample *= 2
        }
        return sample
    }
}
