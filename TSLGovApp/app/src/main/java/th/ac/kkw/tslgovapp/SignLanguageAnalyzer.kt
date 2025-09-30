package th.ac.kkw.tslgovapp

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import th.ac.kkw.tslgovapp.model.HandLandmarkData
import th.ac.kkw.tslgovapp.model.Point3D
import th.ac.kkw.tslgovapp.model.RecognitionResult
import android.util.Log

class SignLanguageAnalyzer(
    private val context: Context,
    private val videoProcessor: VideoProcessor, // VideoProcessor ที่มี Templates ถูกโหลดไว้แล้ว
    private val onResult: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private var handLandmarker: HandLandmarker? = null
    private var lastInferenceTime = 0L
    private val TAG = "SignLanguageAnalyzer"

    private var consecutiveCount = 0
    private var lastDetectedWord = ""
    private var lastAnnouncedWord = "" // ตัวแปรสำหรับจำคำที่พูดไปแล้ว
    private val requiredConsecutiveDetections = 5

    init {
        setupMediaPipe()
    }

    private fun setupMediaPipe() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.4f)
                .setMinTrackingConfidence(0.4f)
                .setResultListener { result: HandLandmarkerResult, _: MPImage ->
                    processResults(result)
                }
                .setErrorListener { error: RuntimeException ->
                    Log.e(TAG, "MediaPipe error: ${error.message}")
                }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up MediaPipe: ${e.message}")
        }
    }

    private fun processResults(result: HandLandmarkerResult) {
        val numDetectedHands = result.landmarks().size

        Log.v(TAG, "🔍 processResults: $numDetectedHands hand(s) detected")

        if (numDetectedHands == 0) {
            resetConsecutiveCount()
            return
        }

        // ⭐ รวมข้อมูลจากทุกมือที่ตรวจพบ
        val allHandsLandmarks = mutableListOf<Point3D>()

        for (handIndex in result.landmarks().indices) {
            val handLandmarks = result.landmarks()[handIndex]
            handLandmarks.forEach { landmark ->
                allHandsLandmarks.add(Point3D(landmark.x(), landmark.y(), landmark.z()))
            }
            Log.v(TAG, "   Hand ${handIndex + 1}: ${handLandmarks.size} landmarks")
        }

        val combinedHandData = HandLandmarkData(landmarks = allHandsLandmarks)

        // ⭐ ส่งจำนวนมือที่ตรวจพบไปด้วย
        val recognitionResult = recognizeGesture(combinedHandData, numDetectedHands)

        if (recognitionResult != null) {
            Log.v(TAG, "Recognition -> ${recognitionResult.word}: ${String.format("%.1f", recognitionResult.confidence)}%")

            val confidenceThreshold = 0.65f

            if (recognitionResult.confidence >= confidenceThreshold) {
                Log.d(TAG, "✅ Above threshold: ${recognitionResult.word}")
                handleConsecutiveDetection(recognitionResult.word)
            } else {
                Log.v(TAG, "⚠️ Below threshold: ${recognitionResult.word}")
                resetConsecutiveCount()
            }
        } else {
            Log.v(TAG, "❌ No match found for $numDetectedHands hand(s)")
            resetConsecutiveCount()
        }
    }

    /**
     * ปรับปรุงฟังก์ชันนี้ให้เรียกใช้ VideoProcessor เพียงอย่างเดียว
     * เพื่อทำการเปรียบเทียบกับ Template ทั้งหมด
     */
    private fun recognizeGesture(
        handLandmarks: HandLandmarkData,
        numDetectedHands: Int  // ⭐ เพิ่มตรงนี้
    ): RecognitionResult? {
        return try {
            // ⭐ ส่งจำนวนมือไปให้ VideoProcessor
            videoProcessor.recognizeSign(handLandmarks, numDetectedHands)
        } catch (e: Exception) {
            Log.e(TAG, "Error recognizing gesture: ${e.message}")
            null
        }
    }

    // ลบฟังก์ชัน recognizeOtherGestures และฟังก์ชันย่อย (isPointingToNeck, isPointingToHead, etc.) ทั้งหมดออกไป
    // เนื่องจาก VideoProcessor จะทำหน้าที่นี้แทน

    private fun handleConsecutiveDetection(word: String) {
        if (word == lastDetectedWord) {
            consecutiveCount++
        } else {
            consecutiveCount = 1
            lastDetectedWord = word
            lastAnnouncedWord = "" // สำคัญมาก : รีเซ็ตเพื่อให้คำใหม่พูดได้
        }
        // เพิ่ม Debug Log
        Log.d(TAG, "📊 Best: $word count=$consecutiveCount/$requiredConsecutiveDetections, lastAnnounced=$lastAnnouncedWord")
        if (consecutiveCount >= requiredConsecutiveDetections && word != lastAnnouncedWord ) {
            onResult(word)
            resetConsecutiveCount() // รีเซ็ตหลังจากส่งผลลัพธ์
            lastAnnouncedWord = word // "จำไว้" ว่าเราเพิ่งพูดคำนี้ไป
        }
    }

    private fun resetConsecutiveCount() {
        consecutiveCount = 0
        lastDetectedWord = ""
        lastAnnouncedWord = "" // ล้างคำ "หน่วยความจำ" ด้วย
    }

    override fun analyze(image: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastInferenceTime < 100) { // 10 FPS
            image.close()
            return
        }
        lastInferenceTime = currentTime

        try {
            val bitmap = image.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            handLandmarker?.detectAsync(mpImage, currentTime)
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame: ${e.message}")
        } finally {
            image.close()
        }
    }

    fun cleanup() {
        handLandmarker?.close()
    }
}

// คลาส Debug ไม่ต้องแก้ไข
class DebugSignLanguageAnalyzer(
    private val context: Context,
    private val videoProcessor: VideoProcessor,
    private val onResult: (String) -> Unit,
    private val onDebug: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val analyzer = SignLanguageAnalyzer(context, videoProcessor) { result ->
        onDebug("🔍 Debug - Detected: $result")
        onResult(result)
    }

    override fun analyze(imageProxy: ImageProxy) {
        analyzer.analyze(imageProxy)
    }

    fun cleanup() {
        analyzer.cleanup()
    }
}