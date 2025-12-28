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
    private var maxActiveHandsInGesture = 0

    private var bestLandmarksInGesture: MutableList<Point3D> = mutableListOf()
    private var isDetectionEnabled = false

    // ✅ เพิ่มตัวแปรเหล่านี้
    private var lastRecognitionTime = 0L
    private val RECOGNITION_COOLDOWN = 2000L

    private var handLandmarker: HandLandmarker? = null
    private var lastInferenceTime = 0L
    private val TAG = "SignLanguageAnalyzer"

    private var consecutiveCount = 0
    private var lastDetectedWord = ""
    private var lastAnnouncedWord = "" // ตัวแปรสำหรับจำคำที่พูดไปแล้ว
    private var lastRecognizedWordEver = ""
    private var handsPreviouslyDetected = false
    private val requiredConsecutiveDetections = 3

    private var detectionStartTime = 0L
    private val DETECTION_DELAY = 1000L  // 1 second delay before detection starts
    private val MIN_HAND_SPREAD = 0.05f // Minimum spread of landmarks to be a valid hand
    private val MaX_HAND_SPREAD = 0.8f // Maximum spread (hand shouldn't be entire frame)

    // Gesture stability check variables
    private var previousLandmarks: MutableList<Point3D> = mutableListOf()
    private var wrongWordCount = 0


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
        // requireHandsRemoved = false  // reset to allow new gestures
        lastAnnouncedWord = ""
        isGestureInProgress = false
        bestLandmarksInGesture.clear()
        maxHandsDetectedInGesture = 0
        maxActiveHandsInGesture = 0
        handsPreviouslyDetected = false
        resetConsecutiveCount()
        Log.d(TAG, "▶️ Detection ENABLED (all states reset)")
    }

    fun stopDetection() {
        isDetectionEnabled = false
        bestLandmarksInGesture.clear()
        maxHandsDetectedInGesture = 0
        maxActiveHandsInGesture = 0
        resetConsecutiveCount()
        Log.d(TAG, "⏹️ Detection DISABLED")
    }

    private fun isValidHand(landmarks: List<Point3D>): Boolean {
        if (landmarks.size < 21) return false

        // calculate bounding box
        val minX = landmarks.minOfOrNull { it.x } ?: return false
        val maxX = landmarks.maxOfOrNull { it.x } ?: return false
        val minY = landmarks.minOfOrNull { it.y } ?: return false
        val maxY = landmarks.maxOfOrNull { it.y } ?: return false

        val width = maxX - minX
        val height = maxY - minY

        if (width < MIN_HAND_SPREAD && height < MIN_HAND_SPREAD) {
            Log.v(TAG, " Hand too small: w=$width, h=$height")
            return false
        }
        if (width > MaX_HAND_SPREAD && height > MaX_HAND_SPREAD) {
            Log.v(TAG, "Hand too large: w=$width, h=$height")
            return false
        }

        val wrist = landmarks[0]
        if (wrist.x < 0.03f || wrist.x > 0.97f || wrist.y < 0.03f || wrist.y > 0.97f) {
            Log.v(
                TAG,
                "Wrist at edge (${String.format("%.2f", wrist.x)}, " +
                        "${String.format("%.2f", wrist.y)})"
            )
            return false
        }
        Log.v(TAG, "Valid hand w = $width, h = $height")
        return true
    }

    private fun countActiveHands(hands: List<List<Point3D>>): Int {
        if (hands.isEmpty()) return 0
        if (hands.size == 1) {
            // One hand: check if wrist is in upper half (y < 0.8)
            val wristY = hands[0][0].y
            return if (wristY < 0.8f) 1 else 0
        }

        // Two hands: user stricter threshold and check hand positioning
        // Only count 2 active hands if BOTH wrists are clearly in the upper active zone
        val wristY0 = hands[0][0].y
        val wristY1 = hands[1][0].y

        val threshold = 0.7f // Stricter threshold for 2-hand gestures
        val bothHandsActive = wristY0 < threshold && wristY1 < threshold
        val atLeastOneActive = wristY0 < threshold || wristY1 < threshold
        Log.v(TAG, "writs: Y0=$wristY0, Y1=$wristY1, bothActive=$bothHandsActive, atLeastone=$atLeastOneActive")
        // Return count of active hands
        return when {
            bothHandsActive -> 2 // Both active
            atLeastOneActive -> 1 // One active
            else -> 0                       // None active
        }
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
            if (requireHandsRemoved) {
                requireHandsRemoved = false  // Reset when hands are removed
                Log.d(TAG, "Hands removed, ready for next gesture")
            }
            // ถ้าไม่มีมือ รีเซ็ตการจับเวลา
            if (isGestureInProgress) {
                // ✅ ตรวจสอบว่าผ่าน cooldown แล้วหรือยัง
                val timeSinceLastRecognition = System.currentTimeMillis() - lastRecognitionTime
                Log.v(TAG, "time since last recognition $timeSinceLastRecognition")
                if (timeSinceLastRecognition > RECOGNITION_COOLDOWN) {
                    Log.d(TAG, "⏹️ Gesture ended (hands removed)")
                    isGestureInProgress = false
                    hasLoggedThisGesture = false
                    maxHandsDetectedInGesture = 0  // Only reset when gesture TRULY ends``
                    maxActiveHandsInGesture = 0
                    bestLandmarksInGesture.clear()
                }
            } else {
                // This prevents stale data from being reused in the next detection cycle
                bestLandmarksInGesture.clear()
            }
            resetConsecutiveCount()
            return
        }



        // ✅ เช็คว่าอยู่ใน cooldown หรือไม่
        val timeSinceLastRecognition = System.currentTimeMillis() - lastRecognitionTime
        if (timeSinceLastRecognition < RECOGNITION_COOLDOWN) {
            Log.v(
                TAG,
                "🚫 In cooldown period (${RECOGNITION_COOLDOWN - timeSinceLastRecognition}ms left)"
            )
            return
        }

        // ✅ Require hands to be removed after successful recognition
        val timeSinceDetectionStart = System.currentTimeMillis() - detectionStartTime
        if (requireHandsRemoved && timeSinceDetectionStart > 500L) {
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
            maxHandsDetectedInGesture = numDetectedHands
            Log.d(TAG, "▶️ Gesture started")
        } else if (isGestureInProgress && !handsCurrentlyDetected) {
            Log.d(TAG, "⏹️ Gesture ended (hands left frame)")
            isGestureInProgress = false
            hasLoggedThisGesture = false
            maxHandsDetectedInGesture = 0
            maxActiveHandsInGesture = 0
            bestLandmarksInGesture.clear()
        }

        // ✅ Always track max hands FIRST (even during cooldown)
        if (numDetectedHands > maxHandsDetectedInGesture) {
            maxHandsDetectedInGesture = numDetectedHands
            Log.d(TAG, "   Max hands updated: $maxHandsDetectedInGesture")
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
            Log.v(
                TAG,
                "   Hand ${index + 1} (sorted): ${hand.size} landmarks, wrist x=${hand[0].x()}"
            )
        }

        for ((index, handLandmarks) in handsToValidate.withIndex()) {
            if (!isValidHand(handLandmarks)) {
                Log.v(TAG, " Hand ${index + 1} invalid (noise/face), skipping")
                allHandsValid = false
            } else {
                Log.v(TAG, "Hand ${index + 1} passed validation")
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
        // Count active hands
        val activeHandsCount = countActiveHands(handsToValidate)
        Log.d(TAG, " Active hands: $activeHandsCount (detected: $numDetectedHands)")

        if (activeHandsCount > maxActiveHandsInGesture) {
            maxActiveHandsInGesture = activeHandsCount
            Log.d(TAG, "Max active hands updated: $maxActiveHandsInGesture")
        }

        val activeHandsLandmarks = mutableListOf<Point3D>()
        if (activeHandsCount == 1) {
            val activeHandIndex = if (handsToValidate.size == 2) {
                if (handsToValidate[0][0].y < handsToValidate[1][0].y) 0 else 1
            } else 0
            activeHandsLandmarks.addAll(handsToValidate[activeHandIndex])
        } else {
            activeHandsLandmarks.addAll(allHandsLandmarks)
        }
        if (activeHandsCount >= maxActiveHandsInGesture && (activeHandsLandmarks.size > bestLandmarksInGesture.size ||
            bestLandmarksInGesture.isEmpty())) {
            bestLandmarksInGesture = activeHandsLandmarks.toMutableList()
        }
        // ⭐ Store best landmarks when more hands detected
        /*if (numDetectedHands >= maxHandsDetectedInGesture && allHandsLandmarks.size > bestLandmarksInGesture.size) {
            bestLandmarksInGesture = allHandsLandmarks.toMutableList()
            Log.d(
                TAG,
                "   ⭐ Best landmarks updated: ${bestLandmarksInGesture.size} landmarks, $maxHandsDetectedInGesture hands"
            )
        }*/

        // get the active hand only
        val activeHandIndex = if (numDetectedHands == 2) {
            val hand0Wrist = sortedHands[0][0]
            val hand1Wrist = sortedHands[1][0]
            val hand0Score = hand0Wrist.y() + (hand0Wrist.z() * 0.5f) + kotlin.math.abs(hand0Wrist.x() - 0.5f) * 0.3f
            val hand1Score = hand1Wrist.y() + (hand1Wrist.z() * 0.5f) + kotlin.math.abs(hand1Wrist.x() - 0.5f) * 0.3f
            if (hand0Score < hand1Score) 0 else 1
        } else 0
        val activeHandLandmarks = handsToValidate[activeHandIndex]
        val activeHandData = HandLandmarkData(activeHandLandmarks)

        // ⭐ Wait minimum time to detect all hands before recognition
        val gestureElapsedTime = System.currentTimeMillis() - gestureStartTime
        Log.d(TAG, "   ⏳ Gesture elapsed time: ${gestureElapsedTime}ms")
        var minGestureTime = 500L  // Wait 400ms to detect all hands
        if (activeHandIndex == 1) {
            minGestureTime = 300L
        }
        Log.d(TAG, "   ⏳ Min gesture time: ${minGestureTime}ms")
        if (gestureElapsedTime < minGestureTime) {
            Log.d(
                TAG,
                "   ⏳ Waiting for gesture to stabilize (${gestureElapsedTime}ms / ${minGestureTime}ms)"
            )
            return
        }


        // ⭐ Use best landmarks (from when most hands were detected)
       /* val landmarksForRecognition = if (maxHandsDetectedInGesture == 2 &&
            numDetectedHands == 2
        ) {
            // When 2 hands detected, always use current landmarks
            allHandsLandmarks
        } else if (bestLandmarksInGesture.size >= allHandsLandmarks.size &&
            bestLandmarksInGesture.size <= allHandsLandmarks.size * 1.5f) {
            bestLandmarksInGesture
        } else {
            allHandsLandmarks
        }*/
        val landmarksForRecognition = if (maxActiveHandsInGesture == 2) {
            if (activeHandsLandmarks.size >= 42)
                activeHandsLandmarks.toMutableList()
            else
                bestLandmarksInGesture
        } else {
            activeHandsLandmarks.toMutableList()
        }
        // val landmarksForRecognition = allHandsLandmarks
        val combinedHandData = HandLandmarkData(landmarks = landmarksForRecognition)

        // ⭐ ส่งจำนวนมือที่ตรวจพบไปด้วย
        val handsForRecognition = maxActiveHandsInGesture
        Log.d(
            TAG,
            "   Using hands for recognition: $handsForRecognition (current: $activeHandsCount, max:$maxActiveHandsInGesture)"
        )

        // ⭐ If we ever detected 2 hands, only recognize 2-hand gestures
        /*if (maxHandsDetectedInGesture == 2 && handsForRecognition == 1) {
            Log.d(TAG, "   ⚠️ Skipping: detected 2 hands earlier, but only 1 hand landmarks now")
            return
        }*/

        // Also check if best landmarks suggest 2-hand gesture
        /*if (bestLandmarksInGesture.size >= 42 && handsForRecognition == 1) {
            Log.d(
                TAG,
                "   ⚠️ Skipping: best landmarks show 2 hands, but only 1 hand recognized now"
            )
            return
        }*/
        var recognitionResult = recognizeGesture(combinedHandData, maxActiveHandsInGesture)

        if (recognitionResult == null && maxActiveHandsInGesture == 1)  {

            Log.d(TAG, "Trying with active hand only (hand $activeHandIndex)")
            recognitionResult = recognizeGesture(activeHandData, 1)
        }
        if (recognitionResult != null) {
            Log.v(
                TAG,
                "Recognition -> ${recognitionResult.word}: ${
                    String.format(
                        "%.1f",
                        recognitionResult.confidence
                    )
                }%"
            )

            val confidenceThreshold = 60f
            if (recognitionResult.confidence >= confidenceThreshold) {
                Log.d(TAG, "✅ Above threshold: ${recognitionResult.word}")
                handleConsecutiveDetection(recognitionResult.word)
            } else {
                Log.v(TAG, "⚠️ Below threshold: ${recognitionResult.word}")
                resetConsecutiveCount()
            }
        } else {
            Log.v(TAG, "❌ No match found for $activeHandsCount hand(s)")
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
            Log.v(
                TAG,
                "Recently recognized '$lastRecognizedWordEver', waiting for cooldown (${RECOGNITION_COOLDOWN - timeSinceLastAnnounce})ms left"
            )
            return
        }
        Log.d(TAG, "word is $word lastDetectedWord is $lastDetectedWord")
        if (word == lastDetectedWord && lastDetectedWord.isNotEmpty()) {
            consecutiveCount++
            wrongWordCount = 0
        } else {
            wrongWordCount++;
            // Only reset if we've this different word multiple times
            if (wrongWordCount >= 2) {
                consecutiveCount = 1
                lastDetectedWord = word
                lastAnnouncedWord = "" // สำคัญมาก : รีเซ็ตเพื่อให้คำใหม่พูดได้
            }
            if (lastDetectedWord.isEmpty()) {
                consecutiveCount = 1
                lastDetectedWord = word
                wrongWordCount = 0
            }
        }
        // เพิ่ม Debug Log
        Log.d(
            TAG,
            "📊 Best: $word count=$consecutiveCount/$requiredConsecutiveDetections, wrongWordcount = $wrongWordCount, lastAnnounced=$lastAnnouncedWord"
        )
        if (consecutiveCount >= requiredConsecutiveDetections && word != lastAnnouncedWord) {
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
            maxActiveHandsInGesture = 0
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