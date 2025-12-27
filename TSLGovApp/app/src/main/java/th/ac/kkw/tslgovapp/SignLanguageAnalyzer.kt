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

    // ✅ เพิ่มตัวแปรจับเวลา
    private var gestureStartTime = 0L
    private var isGestureInProgress = false
    private var hasLoggedThisGesture = false
    private var maxHandsDetectedInGesture = 0
    private var requireHandsRemoved = false

    private var bestLandmarksInGesture: MutableList<Point3D> = mutableListOf()
    private var isDetectionEnabled = false
    // ✅ เพิ่มตัวแปรเหล่านี้
    private var lastRecognitionTime = 0L
    private val RECOGNITION_COOLDOWN = 4000L

    private var handLandmarker: HandLandmarker? = null
    private var lastInferenceTime = 0L
    private val TAG = "SignLanguageAnalyzer"

    private var consecutiveCount = 0
    private var lastDetectedWord = ""
    private var lastAnnouncedWord = "" // ตัวแปรสำหรับจำคำที่พูดไปแล้ว
    private var lastRecognizedWordEver = ""
    private var handsPreviouslyDetected = false
    private val requiredConsecutiveDetections = 5

    private var detectionStartTime = 0L
    private val DETECTION_DELAY = 1000L  // 1 second delay before detection starts
    private val MIN_HAND_SPREAD = 0.05f // Minimum spread of landmarks to be a valid hand
    private val MaX_HAND_SPREAD = 0.8f // Maximum spread (hand shouldn't be entire frame)

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
                .setMinHandDetectionConfidence(0.3f)
                .setMinHandPresenceConfidence(0.3f)
                .setMinTrackingConfidence(0.3f)
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

    fun startDetection() {
        isDetectionEnabled = true
        detectionStartTime = System.currentTimeMillis()  // Record when button was pressed
        // Reset all blocking variables
        requireHandsRemoved = false
        lastAnnouncedWord = ""
        lastRecognizedWordEver = ""
        isGestureInProgress = false
        bestLandmarksInGesture.clear()
        maxHandsDetectedInGesture = 0
        handsPreviouslyDetected = false
        resetConsecutiveCount()
        Log.d(TAG, "▶️ Detection ENABLED (all states reset)")
    }

    fun stopDetection() {
        isDetectionEnabled = false
        bestLandmarksInGesture.clear()
        maxHandsDetectedInGesture = 0
        resetConsecutiveCount()
        Log.d(TAG, "⏹️ Detection DISABLED")
    }

    private fun isValidHand(landmarks: List<Point3D>): Boolean {
        if (landmarks.size < 21) return false

        // calculate bounding box
        val minX = landmarks.minOfOrNull {it.x} ?:return false
        val maxX = landmarks.maxOfOrNull {it.x} ?:return false
        val minY = landmarks.minOfOrNull {it.y} ?:return false
        val maxY = landmarks.maxOfOrNull {it.y} ?:return false

        val width = maxX - minX
        val height =maxY - minY

        if (width < MIN_HAND_SPREAD || height < MIN_HAND_SPREAD) {
            Log.v(TAG, " Hand too small: w=$width, h=$height")
            return false
        }
        if (width > MaX_HAND_SPREAD || height > MaX_HAND_SPREAD) {
            Log.v(TAG, "Hand too large: w=$width, h=$height")
            return false
        }

        val wrist = landmarks[0]
        if (wrist.x < 0.1f || wrist.x > 0.9f || wrist.y < 0.1f || wrist.y > 0.9f) {
            Log.v(
                TAG,
                "Wrist at edge (${String.format("%.2f", wrist.x)}, " +
                        "${String.format("%.2f", wrist.y)})")
            return false
        }
        Log.v(TAG, "Valid hand w = $width, h = $height")
        return true
    }
    private fun processResults(result: HandLandmarkerResult) {
        // ✅ Only process if detection is enabled
        if (!isDetectionEnabled) {
            return
        }
        // ✅ Wait for delay after button press
        val timeSinceStart = System.currentTimeMillis() - detectionStartTime
        if (timeSinceStart < DETECTION_DELAY) {
            Log.v(TAG, "⏳ Waiting for detection delay (${DETECTION_DELAY - timeSinceStart}ms left)")
            return
        }


        val numDetectedHands = result.landmarks().size

        if (numDetectedHands == 0) {
            // ถ้าไม่มีมือ รีเซ็ตการจับเวลา
            if (isGestureInProgress) {
                // ✅ ตรวจสอบว่าผ่าน cooldown แล้วหรือยัง
                val timeSinceLastRecognition = System.currentTimeMillis() - lastRecognitionTime
                if (timeSinceLastRecognition > RECOGNITION_COOLDOWN) {
                    Log.d(TAG, "⏹️ Gesture ended (hands removed)")
                    isGestureInProgress = false
                    hasLoggedThisGesture = false
                    maxHandsDetectedInGesture = 0  // Only reset when gesture TRULY ends``
                    bestLandmarksInGesture.clear()
                    requireHandsRemoved = false  // Reset when hands are removed
                }
            } else {
                // This prevents stale data from being reused in the next detection cycle
                bestLandmarksInGesture.clear()
            }
            resetConsecutiveCount()
            return
        }

        // ✅ Always track max hands FIRST (even during cooldown)
        if (numDetectedHands > maxHandsDetectedInGesture) {
            maxHandsDetectedInGesture = numDetectedHands
            Log.d(TAG, "   Max hands updated: $maxHandsDetectedInGesture")
        }

        // ✅ เช็คว่าอยู่ใน cooldown หรือไม่
        val timeSinceLastRecognition = System.currentTimeMillis() - lastRecognitionTime
        if (timeSinceLastRecognition < RECOGNITION_COOLDOWN) {
            Log.v(TAG, "🚫 In cooldown period (${RECOGNITION_COOLDOWN - timeSinceLastRecognition}ms left)")
            return
        }

        // ✅ Require hands to be removed after successful recognition
        if (requireHandsRemoved) {
            Log.v(TAG, "🛑 Waiting for hands to be removed before next recognition")
            return
        }



        // Only start gesture tracking when hands Enter the frame (not already prsent)
        val handsCurrentlyDetected = numDetectedHands > 0
        if (!isGestureInProgress && handsCurrentlyDetected && !handsPreviouslyDetected) {
            gestureStartTime = System.currentTimeMillis()
            isGestureInProgress = true
            hasLoggedThisGesture = false
            bestLandmarksInGesture.clear()
            Log.d(TAG, "▶️ Gesture started")
        } else if (isGestureInProgress && !handsCurrentlyDetected) {
            Log.d(TAG, "⏹️ Gesture ended (hands left frame)")
            isGestureInProgress = false
            hasLoggedThisGesture = false
            maxHandsDetectedInGesture = 0
            bestLandmarksInGesture.clear()
        }

        handsPreviouslyDetected = handsCurrentlyDetected


        Log.d(TAG, "🔍 Frame analysis:")
        Log.d(TAG, "   Detected: $numDetectedHands hand(s)")

        if (numDetectedHands == 0) {
            Log.v(TAG, "   ⚠️ No hands detected - resetting")
            resetConsecutiveCount()
            return
        }


        // Sort hands by wrist X-coordinate (leftmost hand first)
        val sortedHands = result.landmarks().sortedBy { hand ->
            hand[0].x()  // Sort by wrist (landmark 0) x-position
        }


        // Validate each and separately
        var allHandsValid = true
        val handsToValidate = mutableListOf<List<Point3D>>()

        // First, collect each hand's landmarks separately
        for ((index, hand) in sortedHands.withIndex()) {
            val singleHandLandmarks = mutableListOf<Point3D>()
            hand.forEach { landmark ->
                singleHandLandmarks.add(Point3D(landmark.x(), landmark.y(), landmark.z()))
            }
            handsToValidate.add(singleHandLandmarks)
            Log.v(TAG, "   Hand ${index + 1} (sorted): ${hand.size} landmarks, wrist x=${hand[0].x()}")
        }

        for ((index, handLandmarks) in handsToValidate.withIndex()) {
            if (!isValidHand(handLandmarks)) {
                Log.v(TAG, " Hand ${index + 1} invalid (noise/face), skipping")
                allHandsValid = false
            } else {
                Log.v(TAG, "Hand ${index +1} passed validation")
            }
        }
        if (!allHandsValid) {
            resetConsecutiveCount()
            return
        }
        // Now combine all valid hands
        val allHandsLandmarks = mutableListOf<Point3D>()
        for (handLandMarks in handsToValidate) {
            allHandsLandmarks.addAll(handLandMarks)
        }



        Log.d(TAG, "   Total landmarks: ${allHandsLandmarks.size}")


        // ⭐ Store best landmarks when more hands detected
        if (numDetectedHands >= maxHandsDetectedInGesture && allHandsLandmarks.size > bestLandmarksInGesture.size) {
            bestLandmarksInGesture = allHandsLandmarks.toMutableList()
            Log.d(TAG, "   ⭐ Best landmarks updated: ${bestLandmarksInGesture.size} landmarks, $maxHandsDetectedInGesture hands")
        }


        // ⭐ Wait minimum time to detect all hands before recognition
        val gestureElapsedTime = System.currentTimeMillis() - gestureStartTime
        val minGestureTime = 800L  // Wait 800ms to detect all hands

        if (gestureElapsedTime < minGestureTime) {
            Log.d(TAG, "   ⏳ Waiting for gesture to stabilize (${gestureElapsedTime}ms / 1${minGestureTime}ms)")
            return
        }

        // ⭐ Use best landmarks (from when most hands were detected)
        val landmarksForRecognition = if (maxHandsDetectedInGesture == 2 &&
            numDetectedHands == 2) {
            // When 2 hands detected, always use current landmarks
            allHandsLandmarks
        } else if (bestLandmarksInGesture.size >= allHandsLandmarks.size) {
            bestLandmarksInGesture
        } else {
            allHandsLandmarks
        }
        val combinedHandData = HandLandmarkData(landmarks = landmarksForRecognition)

        // ⭐ ส่งจำนวนมือที่ตรวจพบไปด้วย
        val handsForRecognition = if (landmarksForRecognition.size >= 42) 2 else if (landmarksForRecognition.size
            >= 21) 1 else 0
        Log.d(TAG, "   Using hands for recognition: $handsForRecognition (current: $numDetectedHands, max:$maxHandsDetectedInGesture)")

        // ⭐ If we ever detected 2 hands, only recognize 2-hand gestures
        if (maxHandsDetectedInGesture == 2 && handsForRecognition == 1) {
            Log.d(TAG, "   ⚠️ Skipping: detected 2 hands earlier, but only 1 hand landmarks now")
            return
        }

        // Also check if best landmarks suggest 2-hand gesture
        if (bestLandmarksInGesture.size >= 42 && handsForRecognition == 1) {
            Log.d(TAG, "   ⚠️ Skipping: best landmarks show 2 hands, but only 1 hand recognized now")
            return
        }
        val recognitionResult = recognizeGesture(combinedHandData, handsForRecognition)

        if (recognitionResult != null) {
            Log.v(TAG, "Recognition -> ${recognitionResult.word}: ${String.format("%.1f", recognitionResult.confidence)}%")

            val confidenceThreshold = 55f
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
        // Prevent processing after detection is stop
        if (!isDetectionEnabled) {
            Log.d(TAG, "Detection stopped, ignoring '$word'")
            return
        }

        val timeSinceLastAnnounce = if (lastRecognizedWordEver == word) {
            System.currentTimeMillis() - lastRecognitionTime
        } else 0L
        if (timeSinceLastAnnounce < RECOGNITION_COOLDOWN && timeSinceLastAnnounce > 0) {
            Log.v(TAG, "Recently recognized '$lastRecognizedWordEver', waiting for cooldown (${RECOGNITION_COOLDOWN - timeSinceLastAnnounce})ms left")
            return
        }

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
            requireHandsRemoved = true  // Require hands removal before next recognition
            // ✅ คำนวณเวลาที่ใช้
            val elapsedTime = System.currentTimeMillis() - gestureStartTime
            // ✅ บันทึกเฉพาะครั้งแรกของท่าทางนี้
            if (!hasLoggedThisGesture) {
                Log.d(TAG, "⏱️ RECOGNITION TIME for $word: ${elapsedTime}ms")
                hasLoggedThisGesture = true // ✅ ทำครั้งเดียว
            }

            onResult(word)
            stopDetection()  // ✅ Stop after successful recognition

            lastRecognitionTime = System.currentTimeMillis()
            lastAnnouncedWord = word // "จำไว้" ว่าเราเพิ่งพูดคำนี้ไป
            lastRecognizedWordEver = word

            resetConsecutiveCount() // รีเซ็ตหลังจากส่งผลลัพธ์

            // รีเซ็ตการจับเวลา
            isGestureInProgress = false
            maxHandsDetectedInGesture = 0
            bestLandmarksInGesture.clear()
        }
    }

    private fun resetConsecutiveCount() {
        consecutiveCount = 0
        lastDetectedWord = ""
       // lastAnnouncedWord = "" // ล้างคำ "หน่วยความจำ" ด้วย
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