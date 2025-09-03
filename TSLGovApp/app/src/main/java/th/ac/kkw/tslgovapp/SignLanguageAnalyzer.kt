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
    private val requiredConsecutiveDetections = 3

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
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
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
        // เพิ่ม Log บรรทัดนี้เข้าไป
        Log.d(TAG, "processResults called. Landmark count: ${result.landmarks().size}")
        if (result.landmarks().isEmpty()) {
            resetConsecutiveCount()
            return
        }

        val landmarks = result.landmarks().first()
        val currentHandLandmarks = HandLandmarkData(
            landmarks = landmarks.map { landmark ->
                Point3D(landmark.x(), landmark.y(), landmark.z())
            }
        )

        // เรียกฟังก์ชัน recognizeGesture เพื่อเอา RecognitionResult? มา
        val recognitionResult = recognizeGesture(currentHandLandmarks)

        // ตรวจสอบว่าผลลัพธ์ที่ได้ ไม่ใช่ค่าว่าง
        if (recognitionResult != null) {
            // *** แสดงผลลัพธ์การเปรียบเทียบทุกครั้งใน Logcat ตามที่คุณต้องการ ***
            Log.i(
                TAG,
                "Recognition Update -> Word: ${recognitionResult.word}, " +
                        "Confidence: ${"%.2f".format(recognitionResult.confidence * 100)}%, " +
                        "Distance: ${"%.4f".format(recognitionResult.distance)}"
            )

            // นำค่า confidence มาตัดสินใจ
            val confidenceThreshold = 0.5f
            if (recognitionResult.confidence >= confidenceThreshold) {
                handleConsecutiveDetection(recognitionResult.word)
            } else {
                resetConsecutiveCount()
            }

        } else {
            // กรณีที่ recognizeSign คืนค่า null (ไม่มี template)
            resetConsecutiveCount()
        }
    }

    /**
     * ปรับปรุงฟังก์ชันนี้ให้เรียกใช้ VideoProcessor เพียงอย่างเดียว
     * เพื่อทำการเปรียบเทียบกับ Template ทั้งหมด
     */
    private fun recognizeGesture(handLandmarks: HandLandmarkData): RecognitionResult?  {
        try {
            // ตามเป้าหมายโครงการที่ต้องการความแม่นยำ ≥ 70% [cite: 178]
            return videoProcessor.recognizeSign(handLandmarks)
        } catch (e: Exception) {
            Log.e(TAG, "Error recognizing gesture: ${e.message}")
            return null
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
        }

        if (consecutiveCount >= requiredConsecutiveDetections) {
            onResult(word)
            resetConsecutiveCount() // รีเซ็ตหลังจากส่งผลลัพธ์
        }
    }

    private fun resetConsecutiveCount() {
        consecutiveCount = 0
        lastDetectedWord = ""
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