package com.example.deepchatdemo.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

object ImageUtils {
    private const val MAX_UPLOAD_SIDE = 1024
    private const val JPEG_QUALITY = 72
    private const val MAX_COMPRESSED_IMAGE_BYTES = 3 * 1024 * 1024
    private const val IMAGE_TOO_LARGE_MESSAGE =
        "图片压缩后仍然过大，请换一张图片重试。"
    private const val IMAGE_PROCESSING_FAILED_MESSAGE =
        "图片处理失败，请换一张图片重试。"
    private val JPEG_QUALITY_STEPS = intArrayOf(JPEG_QUALITY, 64, 56, 48, 40)
    private val MAX_SIDE_STEPS = intArrayOf(MAX_UPLOAD_SIDE, 896, 768, 640, 512)

    fun imageUriToBase64DataUrl(context: Context, uri: Uri): String {
        return try {
            val bitmap = loadBitmapFromUri(context, uri, MAX_UPLOAD_SIDE)
            val jpegBytes = compressJpegWithinLimit(bitmap)
            Log.d(TAG, "Image compression complete: bytes=${jpegBytes.size}")

            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } catch (error: Exception) {
            val userMessage = if (error.message == IMAGE_TOO_LARGE_MESSAGE) {
                IMAGE_TOO_LARGE_MESSAGE
            } else {
                IMAGE_PROCESSING_FAILED_MESSAGE
            }
            Log.e(
                TAG,
                "Image processing failed: errorType=${error.javaClass.simpleName}, " +
                    "errorMessage=${error.toLogMessage()}"
            )
            throw IOException(userMessage, error)
        }
    }

    fun loadBitmapFromUri(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val exifRotationDegrees = readExifRotationDegrees(context, uri)
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                loadBitmapWithBitmapFactory(context, uri, maxSide)
            }.getOrElse {
                loadBitmapWithImageDecoder(context, uri, maxSide)
            }
        } else {
            loadBitmapWithBitmapFactory(context, uri, maxSide)
        }
        return scaleBitmapIfNeeded(
            bitmap = rotateBitmapIfNeeded(decoded, exifRotationDegrees),
            maxSide = maxSide
        )
    }

    fun copyImageUriToCache(context: Context, sourceUri: Uri): Uri {
        val imageFile = createTempImageFile(context, "hightac_ai_gallery_", ".img")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                imageFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Unable to open selected image.")
        } catch (error: Exception) {
            Log.e(
                TAG,
                "Unable to copy selected image: errorType=${error.javaClass.simpleName}, " +
                    "errorMessage=${error.toLogMessage()}"
            )
            throw IOException(IMAGE_PROCESSING_FAILED_MESSAGE, error)
        }

        return fileProviderUri(context, imageFile)
    }

    fun createTempImageUri(context: Context): Uri {
        return fileProviderUri(
            context = context,
            file = createTempImageFile(context, "hightac_ai_", ".jpg")
        )
    }

    private fun createTempImageFile(context: Context, prefix: String, suffix: String): File {
        val imageDir = File(context.cacheDir, "images").apply {
            if (!exists()) mkdirs()
        }
        return File.createTempFile(prefix, suffix, imageDir)
    }

    private fun fileProviderUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun loadBitmapWithBitmapFactory(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }

        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: throw IOException("Unable to open image.")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Unable to read image size.")
        }

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calculateSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxSide = maxSide
            )
        }

        val decoded = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IOException("Unable to decode image.")

        return scaleBitmapIfNeeded(decoded, maxSide)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun loadBitmapWithImageDecoder(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val longestSide = max(width, height)
            if (longestSide > maxSide) {
                val scale = maxSide.toFloat() / longestSide.toFloat()
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1)
                )
            }
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > maxSide || height / sampleSize > maxSide) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longestSide = max(bitmap.width, bitmap.height)
        if (longestSide <= maxSide) return bitmap

        val scale = maxSide.toFloat() / longestSide.toFloat()
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun compressJpegWithinLimit(bitmap: Bitmap): ByteArray {
        val originalLongestSide = max(bitmap.width, bitmap.height)
        val sideCandidates = listOf(
            originalLongestSide,
            *MAX_SIDE_STEPS.toTypedArray()
        ).filter { it <= originalLongestSide }
            .distinct()

        var lastBytes: ByteArray? = null
        for (side in sideCandidates) {
            val candidate = scaleBitmapIfNeeded(bitmap, side)
            for (quality in JPEG_QUALITY_STEPS) {
                val bytes = compressJpeg(candidate, quality)
                lastBytes = bytes
                Log.d(
                    TAG,
                    "Image compression candidate: side=${max(candidate.width, candidate.height)}, " +
                        "quality=$quality, bytes=${bytes.size}"
                )
                if (bytes.size <= MAX_COMPRESSED_IMAGE_BYTES) {
                    return bytes
                }
            }
        }

        Log.e(
            TAG,
            "Image too large after compression: bytes=${lastBytes?.size ?: 0}, " +
                "limit=$MAX_COMPRESSED_IMAGE_BYTES"
        )
        throw IOException(IMAGE_TOO_LARGE_MESSAGE)
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val outputStream = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)) {
            throw IOException("Unable to compress image.")
        }
        return outputStream.toByteArray()
    }

    private fun readExifRotationDegrees(context: Context, uri: Uri): Int {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrElse { error ->
            Log.d(
                TAG,
                "EXIF orientation unavailable: errorType=${error.javaClass.simpleName}, " +
                    "errorMessage=${error.toLogMessage()}"
            )
            0
        }
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap

        return runCatching {
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                Matrix().apply { postRotate(degrees.toFloat()) },
                true
            )
        }.getOrElse { error ->
            Log.d(
                TAG,
                "EXIF rotation skipped: degrees=$degrees, " +
                    "errorType=${error.javaClass.simpleName}, errorMessage=${error.toLogMessage()}"
            )
            bitmap
        }
    }

    private fun Throwable.toLogMessage(): String {
        return message.orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)
    }

    private const val TAG = "HighTacAI"
}
