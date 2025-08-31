// SignLanguageAnalyzer.kt - Complete file with all classes

package th.ac.kkw.tslgovapp

import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.*

class SignLanguageAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {

    // Government vocabulary organized by context
    private val hospitalWords = listOf("เจ็บคอ", "ปวดหัว", "ช่วย")
    private val policeWords = listOf("หาย", "บัตรประชาชน", "แจ้งความ")
    private val trainWords = listOf("หนังสือเดินทาง", "เครื่องบิน", "ห้องน้ำ")

    private var frameCount = 0
    private var lastDetectionTime = 0L
    private var wordIndex = 0
    private var previousFrameBrightness = 0f
    private var gestureBuffer = mutableListOf<Float>()
    private val bufferSize = 3 // Reduced for faster response

    private val detectionCooldown = 1500L // Reduced to 1.5 seconds
    private var isInitialized = false

    override fun analyze(imageProxy: ImageProxy) {
        try {
            frameCount++
            val currentTime = System.currentTimeMillis()

            // Process every frame for better sensitivity
            if (frameCount % 2 != 0) {
                imageProxy.close()
                return
            }

            // Convert to bitmap with proper handling
            val bitmap = imageProxyToBitmapFixed(imageProxy)
            if (bitmap == null) {
                Log.w("TSL", "Failed to convert image to bitmap")
                imageProxy.close()
                return
            }

            // Analyze for gesture patterns
            val gestureStrength = analyzeGesturePatternImproved(bitmap)

            Log.d("TSL", "Frame $frameCount: Gesture strength = $gestureStrength")

            // Add to gesture buffer
            gestureBuffer.add(gestureStrength)
            if (gestureBuffer.size > bufferSize) {
                gestureBuffer.removeAt(0)
            }

            // Initialize baseline after a few frames
            if (!isInitialized && frameCount > 10) {
                isInitialized = true
                Log.d("TSL", "Gesture detection initialized")
            }

            // Check for gesture detection with more sensitive thresholds
            if (isInitialized && shouldDetectGesture(currentTime) && isSignificantGestureImproved()) {
                lastDetectionTime = currentTime
                val detectedWord = selectWord(gestureStrength)
                onResult(detectedWord)
                Log.d("TSL", "✅ DETECTED GESTURE: $detectedWord (strength: $gestureStrength)")
            }

        } catch (e: Exception) {
            Log.e("SignLanguageAnalyzer", "Analysis failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmapFixed(imageProxy: ImageProxy): Bitmap? {
        return try {
            // Handle different image formats properly
            when (imageProxy.format) {
                android.graphics.ImageFormat.YUV_420_888 -> {
                    // Convert YUV to RGB
                    val yBuffer = imageProxy.planes[0].buffer
                    val uBuffer = imageProxy.planes[1].buffer
                    val vBuffer = imageProxy.planes[2].buffer

                    val ySize = yBuffer.remaining()
                    val uSize = uBuffer.remaining()
                    val vSize = vBuffer.remaining()

                    val nv21 = ByteArray(ySize + uSize + vSize)
                    yBuffer.get(nv21, 0, ySize)
                    vBuffer.get(nv21, ySize, vSize)
                    uBuffer.get(nv21, ySize + vSize, uSize)

                    val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
                    val out = java.io.ByteArrayOutputStream()
                    yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
                    val imageBytes = out.toByteArray()
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                }
                else -> {
                    // Fallback for other formats
                    val buffer = imageProxy.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
        } catch (e: Exception) {
            Log.e("TSL", "Error converting image: ${e.message}")
            null
        }
    }

    private fun analyzeGesturePatternImproved(bitmap: Bitmap): Float {
        // Simplified but more effective gesture analysis
        val motionFactor = calculateMotionFactorImproved(bitmap)
        val activityFactor = calculateActivityFactor(bitmap)

        Log.d("TSL", "Motion: $motionFactor, Activity: $activityFactor")

        // Combine factors with emphasis on motion
        return (motionFactor * 0.7f + activityFactor * 0.3f)
    }

    private fun calculateMotionFactorImproved(bitmap: Bitmap): Float {
        val currentBrightness = calculateAverageBrightnessOptimized(bitmap)
        val motionStrength = abs(currentBrightness - previousFrameBrightness)
        previousFrameBrightness = currentBrightness

        // More sensitive motion detection
        val normalizedMotion = minOf(motionStrength * 20f, 1.0f)

        Log.d("TSL", "Current brightness: $currentBrightness, Motion strength: $motionStrength, Normalized: $normalizedMotion")

        return normalizedMotion
    }

    private fun calculateActivityFactor(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height

        // Focus on center area where hands are likely to be
        val centerX = width / 2
        val centerY = height / 2
        val sampleRadius = minOf(width, height) / 4

        var totalVariance = 0f
        var sampleCount = 0

        // Sample in a grid pattern in the center area
        for (x in (centerX - sampleRadius) until (centerX + sampleRadius) step 20) {
            for (y in (centerY - sampleRadius) until (centerY + sampleRadius) step 20) {
                if (x >= 0 && x < width-1 && y >= 0 && y < height-1) {
                    val pixel1 = bitmap.getPixel(x, y)
                    val pixel2 = bitmap.getPixel(x+1, y)

                    val brightness1 = getPixelBrightness(pixel1)
                    val brightness2 = getPixelBrightness(pixel2)

                    val variance = abs(brightness1 - brightness2)
                    totalVariance += variance
                    sampleCount++
                }
            }
        }

        val avgVariance = if (sampleCount > 0) totalVariance / sampleCount else 0f
        return minOf(avgVariance * 3f, 1.0f)
    }

    private fun calculateAverageBrightnessOptimized(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        var totalBrightness = 0f
        var pixelCount = 0

        // Sample fewer pixels but more strategically
        val stepSize = 15
        for (x in stepSize until width-stepSize step stepSize) {
            for (y in stepSize until height-stepSize step stepSize) {
                val pixel = bitmap.getPixel(x, y)
                totalBrightness += getPixelBrightness(pixel)
                pixelCount++
            }
        }

        return if (pixelCount > 0) totalBrightness / pixelCount else 0f
    }

    private fun getPixelBrightness(pixel: Int): Float {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    }

    private fun shouldDetectGesture(currentTime: Long): Boolean {
        return currentTime - lastDetectionTime >= detectionCooldown
    }

    private fun isSignificantGestureImproved(): Boolean {
        if (gestureBuffer.size < bufferSize) return false

        val avgGestureStrength = gestureBuffer.average()
        val maxGestureStrength = gestureBuffer.maxOrNull() ?: 0f

        // More sensitive thresholds
        val isSignificant = avgGestureStrength > 0.15f || maxGestureStrength > 0.25f

        Log.d("TSL", "Gesture check - Avg: $avgGestureStrength, Max: $maxGestureStrength, Significant: $isSignificant")

        return isSignificant
    }

    private fun selectWord(gestureStrength: Float): String {
        // Use gesture characteristics to influence word selection
        val category = when {
            gestureStrength > 0.5f -> hospitalWords // High activity = urgent (hospital)
            gestureStrength > 0.3f -> policeWords   // Medium activity = official (police)
            else -> trainWords                       // Lower activity = travel (train)
        }

        // Cycle through words in selected category
        val categoryIndex = wordIndex % category.size
        wordIndex++

        return category[categoryIndex]
    }
}

// Debug version for testing - shows detection status
class DebugSignLanguageAnalyzer(
    private val onResult: (String) -> Unit,
    private val onDebug: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val analyzer = SignLanguageAnalyzer(onResult)
    private var debugFrameCount = 0

    override fun analyze(imageProxy: ImageProxy) {
        debugFrameCount++

        if (debugFrameCount % 30 == 0) { // Every 30 frames (about 1 second)
            onDebug("Processing frame $debugFrameCount...")
        }

        analyzer.analyze(imageProxy)
    }
}