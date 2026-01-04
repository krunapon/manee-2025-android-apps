package th.ac.kkw.tslgovapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import th.ac.kkw.tslgovapp.model.HandLandmarkData
import th.ac.kkw.tslgovapp.model.Point3D
import kotlin.math.*
import android.os.Handler
import android.os.Looper

/**
 * ============================================================================
 * IMPROVED SIGN LANGUAGE ANALYZER FOR THAI SIGN LANGUAGE (TSL)
 * ============================================================================
 * 
 * Version: 2.0 (Improved - Compatible with existing VideoProcessor)
 * 
 * Key Improvements:
 * 1. ANGLE-BASED FEATURES - Scale and position invariant
 * 2. GESTURE CHARACTERISTIC CHECKING - Pre-filter for accuracy
 * 3. IMPROVED NORMALIZATION - Better handling of hand position/size
 * 4. HYBRID MATCHING - Combines Euclidean distance with angle features
 * 5. CONSECUTIVE DETECTION - Reduces false positives
 * 
 * Compatible with:
 * - VideoProcessor.kt (existing template system)
 * - CameraActivity.kt (existing UI)
 * - SignLanguageConfig.kt (word configuration)
 * 
 * ============================================================================
 */

/**
 * Main Sign Language Analyzer class
 * Integrates with CameraX for real-time gesture recognition
 */
class SignLanguageAnalyzer(
    private val context: Context,
    private val videoProcessor: VideoProcessor,
    private val onResult: (String) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "SignLanguageAnalyzer"
        private const val MODEL_FILE = "hand_landmarker.task"

        // Detection confidence settings
        private const val MIN_DETECTION_CONFIDENCE = 0.3f
        private const val MIN_TRACKING_CONFIDENCE = 0.3f
        private const val MAX_NUM_HANDS = 2

        // Frame rate limiting (12.5 FPS for performance)
        private const val INFERENCE_INTERVAL_MS = 80L

        // Consecutive detection settings
        private const val REQUIRED_CONSECUTIVE_DETECTIONS = 3  // Increased from 2 to reduce false positives

        // Hand validation bounds
        private const val MIN_HAND_SPREAD = 0.05f  // Minimum spread of landmarks to be a valid hand
        private const val MAX_HAND_SPREAD = 0.8f   // Maximum spread (hand shouldn't be entire frame)

        // Capture mode settings
        private const val MODE_CONTINUOUS = 0
        private const val MODE_SINGLE_FRAME = 1
        private const val CAPTURE_WINDOW_MS  = 200L
        private const val PROCESSING_TIMEOUT_MS = 500L

    }

    // MediaPipe Hand Landmarker
    private var handLandmarker: HandLandmarker? = null

    // Detection state
    private var isDetectionActive = false
    private var lastInferenceTime = 0L

    // Consecutive detection tracking (prevents false positives)
    private var lastDetectedWord = ""
    private var consecutiveCount = 0

    // Gesture timing tracking
    private var gestureStartTime = 0L
    private var isGestureInProgress = false

    // Announcement tracking
    private val announceLock = Object()
    private val detectionLock = Object()
    private var lastAnnouncedWord = ""
    private var lastAnnouncedTime = 0L
    private val ANNOUNCE_COOLDOWN = 2000L  // Increased from 200ms to prevent re-announcement

    // session tracking to prevent stale frames from being processed
    private var currentSessionId = 0L
    private var detectionStartTime = 0L

    // Feature extractor for improved recognition
    private val featureExtractor = HandFeatureExtractor()

    // Single frame capture mode
    private var captureMode = MODE_CONTINUOUS
    private var shouldCaptureFrame = false
    private var isProcessingCapture = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameCount = 0L

    // Camera type tracking (for coordinate mirroring)
    var isFrontCamera = true

    // Add these properties to the class
    private val recentLandmarks = mutableListOf<HandLandmarkData>()
    private val STABILITY_HISTORY_SIZE = 5
    // Max allowed movement between frames
    // The larger value it is, the more chance that false positive will occur
    // The smaller value it is, the more chance that headache may not be detected
    private val MAX_MOVEMENT_THRESHOLD = 0.20f


    /**
     * Check if hand position is stable (not moving too much)
     * Helps prevent false positives from transitional movements
     */
    private fun isHandStable(currentLandmarks: HandLandmarkData): Boolean {
        if (recentLandmarks.isEmpty()) {
            recentLandmarks.add(currentLandmarks)
            return false  // Not enough history yet
        }

        // Compare current wrist position with previous
        val prevWrist = recentLandmarks.last().landmarks[0]
        val currWrist = currentLandmarks.landmarks[0]

        val movement = sqrt(
            (currWrist.x - prevWrist.x).pow(2) +
                    (currWrist.y - prevWrist.y).pow(2)
        )

        // Update history
        recentLandmarks.add(currentLandmarks)
        if (recentLandmarks.size > STABILITY_HISTORY_SIZE) {
            recentLandmarks.removeAt(0)
        }

        val isStable = movement < MAX_MOVEMENT_THRESHOLD
        if (!isStable) {
            Log.v(TAG, "   Hand moving: movement=$movement (threshold=$MAX_MOVEMENT_THRESHOLD)")
        }

        return isStable
    }
    init {
        Logger.saveLogcatFor(context, SignLanguageAnalyzer::class)
        setupHandLandmarker()

    }

    /**
     * Initialize MediaPipe Hand Landmarker
     */
    private fun setupHandLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setMinHandDetectionConfidence(MIN_DETECTION_CONFIDENCE)
                .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                .setNumHands(MAX_NUM_HANDS)
                .setResultListener { result, _ -> processResults(result) }
                .setErrorListener { error -> Log.e(TAG, "MediaPipe error: ${error.message}") }
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.i(TAG, "✅ HandLandmarker initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize HandLandmarker: ${e.message}")
        }
    }

    /**
     * Start detection mode
     */
    fun startDetection() {
        isDetectionActive = true
        resetConsecutiveCount()
        lastAnnouncedWord = ""

        // Reset capture state to ensure clean start
        shouldCaptureFrame = false
        isProcessingCapture = false

        // Ensure the stability check starts fresh and doesn't use old hand positions
        // Start a new session and clear landmarks history
        currentSessionId++
        detectionStartTime = System.currentTimeMillis()
        recentLandmarks.clear()

        isGestureInProgress = false
        gestureStartTime = 0L
        Log.d(TAG, "🟢 Detection started (session $currentSessionId)")
    }

    /**
     * Stop detection mode
     */
    fun stopDetection() {
        isDetectionActive = false
        resetConsecutiveCount()
        isGestureInProgress = false
        gestureStartTime = 0L
        Log.d(TAG, "🔴 Detection stopped")
    }

    // ========================================================================
    // HAND VALIDATION FUNCTIONS
    // ========================================================================

    /**
     * Validate that detected hand landmarks represent a real hand
     * Filters out noise and false detections like faces
     */
    private fun isValidHand(landmarks: List<Point3D>): Boolean {
        if (landmarks.size < 21) return false

        // Calculate bounding box
        val minX = landmarks.minOfOrNull { it.x } ?: return false
        val maxX = landmarks.maxOfOrNull { it.x } ?: return false
        val minY = landmarks.minOfOrNull { it.y } ?: return false
        val maxY = landmarks.maxOfOrNull { it.y } ?: return false

        val width = maxX - minX
        val height = maxY - minY

        // Check hand is not too small (noise)
        if (width < MIN_HAND_SPREAD && height < MIN_HAND_SPREAD) {
            Log.v(TAG, "   Hand too small: w=$width, h=$height")
            return false
        }
        // Check hand is not too large (entire frame)
        if (width > MAX_HAND_SPREAD && height > MAX_HAND_SPREAD) {
            Log.v(TAG, "   Hand too large: w=$width, h=$height")
            return false
        }

        // Check wrist is not at frame edges
        val wrist = landmarks[0]
        if (wrist.x < 0.03f || wrist.x > 0.97f || wrist.y < 0.03f || wrist.y > 0.97f) {
            Log.v(TAG, "   Wrist at edge (${String.format("%.2f", wrist.x)}, ${String.format("%.2f", wrist.y)})")
            return false
        }

        Log.v(TAG, "   Valid hand: w=$width, h=$height")
        return true
    }

    /**
     * Count how many hands are in the "active zone" (upper portion of frame)
     * This filters out hands resting at the bottom
     */
    private fun countActiveHands(hands: List<List<Point3D>>): Int {
        if (hands.isEmpty()) return 0
        if (hands.size == 1) {
            // One hand: check if wrist is in upper portion (y < 0.8)
            val wristY = hands[0][0].y
            return if (wristY < 0.8f) 1 else 0
        }

        // Two hands: use balanced threshold
        // - Stricter to filter hands at sides (Y > 0.75 = too low)
        // - Lenient enough for 2-hand gestures where bottom hand might be at waist level
        val wristY0 = hands[0][0].y
        val wristY1 = hands[1][0].y

        // Threshold: hands below 0.75 are considered "at side" and not active
        val threshold = 0.85f
        val bothHandsActive = wristY0 < threshold && wristY1 < threshold
        val atLeastOneActive = wristY0 < threshold || wristY1 < threshold

        Log.d(TAG, "   📍 Active hands: wrists Y0=$wristY0, Y1=$wristY1, threshold=$threshold, both=$bothHandsActive, atLeastOne=$atLeastOneActive")

        return when {
            bothHandsActive -> 2
            atLeastOneActive -> 1
            else -> 0
        }
    }

    /**
     * Process hand detection results from MediaPipe
     */
    private fun processResults(result: HandLandmarkerResult) {
        if (!isDetectionActive) return

        // Release lock after getting result (single-frame mode)
        // In single-frame mode, only process the result that was explicitly captured
        val wasCaptured = isProcessingCapture
        if (captureMode == MODE_SINGLE_FRAME && isProcessingCapture) {
            isProcessingCapture = false
            Log.d(TAG, "📸 Single frame processing complete")
        } else if (captureMode == MODE_SINGLE_FRAME && !wasCaptured) {
            return
        }

        // Reject frames from before detection started (prevent stale frame processing)
        // MediaPipe LIVE_STREAM may deliver frames that were queued before startDetection() was called
        // We only accept frames captured after detection started (+ a small buffer for latency)
        val frameTimestamp = result.timestampMs()
        if (frameTimestamp < detectionStartTime - 100) {
            Log.v(TAG, "Ignoring stale frame from before detection (t=$frameTimestamp, start=$detectionStartTime)")
            return
        }
        val landmarks = result.landmarks()

        if (landmarks.isEmpty()) {
            // No hands detected - reset tracking
            resetConsecutiveCount()
            if (captureMode == MODE_SINGLE_FRAME && wasCaptured) {
                Log.w(TAG, "Single-frame capture triggered but NO hands detected!")
            }
            isGestureInProgress = false
            gestureStartTime = 0L
            return
        }

        val numDetectedHands = landmarks.size
        Log.d(TAG, "👋 MediaPipe detected: $numDetectedHands hand(s) in this frame")
        if (numDetectedHands == 1) {
            Log.w(TAG, "   ⚠️ Only 1 hand detected! For 'help' gesture, keep BOTH hands in frame and closer together")
        }

        // Start gesture timing if not already started
        if (!isGestureInProgress) {
            gestureStartTime = System.currentTimeMillis()
            isGestureInProgress = true
            Log.d(TAG, "▶️ Gesture started")
        }

        // ============================================================
        // 1. HAND VALIDATION - Filter out false detections
        // ============================================================

        // Sort hands by wrist X-coordinate (leftmost first)
        val sortedHands = result.landmarks().sortedBy { it[0].x() }

        // Convert each hand to our format for validation
        val handsToValidate = mutableListOf<List<Point3D>>()
        for ((index, hand) in sortedHands.withIndex()) {
            val singleHandLandmarks = mutableListOf<Point3D>()
            hand.forEach { landmark ->
                // Apply mirror transformation for front camera
                val originalX = landmark.x()
                val x = if (isFrontCamera) 1.0f - originalX else originalX
                singleHandLandmarks.add(Point3D(x, landmark.y(), landmark.z()))
            }
            handsToValidate.add(singleHandLandmarks)
            // Log both original and transformed coordinates for debugging
            Log.d(TAG, "   👋 Hand ${index + 1}: frontCamera=$isFrontCamera, originalX=${String.format("%.3f", hand[0].x())}, transformedX=${String.format("%.3f", singleHandLandmarks[0].x)}, y=${String.format("%.3f", singleHandLandmarks[0].y)}")
        }

        // Validate each hand
        var allHandsValid = true
        for ((index, handLandmarks) in handsToValidate.withIndex()) {
            if (!isValidHand(handLandmarks)) {
                Log.v(TAG, "   Hand ${index + 1} invalid (noise/edge), skipping")
                allHandsValid = false
            }
        }
        if (!allHandsValid) {
            resetConsecutiveCount()
            return
        }

        // Count active hands (in upper portion of frame)
        val activeHandsCount = countActiveHands(handsToValidate)
        if (activeHandsCount == 0) {
            Log.v(TAG, "   No active hands in detection zone")
            resetConsecutiveCount()
            return
        }

        // Build landmarks data for active hands only
        val activeHandsLandmarks = mutableListOf<Point3D>()
        if (activeHandsCount == 1 && handsToValidate.size == 2) {
            // Use only the active hand (higher one)
            val activeHandIndex = if (handsToValidate[0][0].y < handsToValidate[1][0].y) 0 else 1
            activeHandsLandmarks.addAll(handsToValidate[activeHandIndex])
            Log.v(TAG, "   Using active hand $activeHandIndex")
        } else {
            // Use all valid hands
            for (handLandmarks in handsToValidate) {
                activeHandsLandmarks.addAll(handLandmarks)
            }
        }

        val handLandmarkData = HandLandmarkData(activeHandsLandmarks)

        // ============================================================
        // 2. GESTURE TIMING CHECK & STABILITY CHECKS - Skip for single-frame mode
        // ============================================================
        // In single-frame mdoe, user had time to prepare during countdown
        // In continuous mode, wait for gesture to stablize
        if (captureMode == MODE_CONTINUOUS) {
            val gestureElapsedTime = System.currentTimeMillis() - gestureStartTime
            val minGestureTime = 500L  // Wait 500ms for gesture to stabilize

            if (gestureElapsedTime < minGestureTime) {
                Log.v(
                    TAG,
                    "   ⏳ Waiting for gesture to stabilize (${gestureElapsedTime}ms / ${minGestureTime}ms)"
                )
                return
            }


            // ============================================================
            // ADD STABILITY CHECK HERE (NEW)
            // ============================================================
            if (!isHandStable(handLandmarkData)) {
                Log.v(TAG, "   🔄 Hand moving, skipping recognition")
                resetConsecutiveCount()
                return  // Skip recognition while hand is moving
            }
        }

        // ============================================================
        // RECOGNITION PIPELINE
        // ============================================================

        // Step 1: Extract angle-based features for additional validation
        val angleFeatures = featureExtractor.extractAngleFeatures(handLandmarkData)
        val fingerStates = featureExtractor.detectFingerStates(handLandmarkData)

        Log.v(TAG, "📊 Features: ${angleFeatures.size} angles, fingers=${fingerStates.joinToString()}")

        // Step 2: Use VideoProcessor for template matching
        val recognitionResult = videoProcessor.recognizeSign(handLandmarkData, activeHandsCount)

        if (recognitionResult != null) {
            val word = recognitionResult.word
            val confidence = recognitionResult.confidence

            Log.d(TAG, "🎯 VideoProcessor result: '$word' (${String.format("%.1f%%", confidence)})")

            // Step 3: Additional validation using angle features
            if (validateWithAngleFeatures(word, handLandmarkData, angleFeatures, fingerStates)) {
                handleConsecutiveDetection(word)
            } else {
                Log.d(TAG, "❌ Failed angle feature validation for '$word'")
                resetConsecutiveCount()
            }
        } else {
            // No match from VideoProcessor
            resetConsecutiveCount()
        }
    }

    /**
     * Additional validation using angle-based features
     * This helps distinguish between visually similar gestures
     */
    private fun validateWithAngleFeatures(
        word: String,
        landmarks: HandLandmarkData,
        angleFeatures: FloatArray,
        fingerStates: IntArray
    ): Boolean {
        // Get required hand count from config
        val config = SignLanguageConfig.getWordByName(word)
        val requiredHands = config?.numHands ?: 1
        val actualHands = landmarks.landmarks.size / 21
        
        // Basic hand count validation
        if (actualHands < requiredHands) {
            Log.v(TAG, "   Hand count mismatch: need $requiredHands, got $actualHands")
            return false
        }
        
        // Word-specific angle validation
        return when (word) {
            // โรงพยาบาล (Hospital) - 3 words
            "ปวดหัว" -> validateHeadacheGesture(landmarks, fingerStates)
            "ช่วย" -> validateHelpGesture(landmarks, fingerStates)
            "เจ็บคอ" -> validateSoreThroatGesture(landmarks, fingerStates)


            // สถานีตำรวจ (Police) - 3 words
            "แจ้งความ" -> validateReportGesture(landmarks, fingerStates)
            "หาย" -> validateLostGesture(landmarks, fingerStates)

            // สนามบิน (Airport) - 3 words
            "เครื่องบิน" -> validateAirplaneGesture(landmarks, fingerStates)
            "บัตรประชาชน" -> validateIDCardGesture(landmarks, fingerStates)
            "ห้องน้ำ" -> validateToiletGesture(landmarks, fingerStates)
            else -> true // Allow other words through
        }
    }

    // ========================================================================
    // GESTURE-SPECIFIC VALIDATION FUNCTIONS
    // ========================================================================

    /**
     * Validate "เครื่องบิน" (Airplane) gesture
     * Characteristics: Hand flat, palm down, fingers spread like wings
     */
    private fun validateAirplaneGesture(landmarks: HandLandmarkData, fingerStates: IntArray): Boolean {
        if (landmarks.landmarks.size < 21) return true

        // fingerStates: [Thumb, Index, Middle, Ring, Pinky]
        val pinky = fingerStates[4]

        Log.d(TAG, "   ✈️ Airplane: pinky=$pinky")

        // Airplane: thumb, index, pinky extended (3 wing fingers)
        // Middle and ring should be curled (tucked in)
        val wingFingersExtended =  pinky >= 1 // At least 2 of 3 wing fingers



        if (!wingFingersExtended) {
            Log.d(TAG, "   ❌ Airplane:  pinky should be extended")
            return false
        }


        // Check hand position - airplane is at mid-level
        val wristY = landmarks.landmarks[0].y
        if (wristY < 0.4f) {
            Log.d(TAG, "   ❌ Airplane: hand too high (wristY=$wristY), might be ปวดหัว")
            return false
        }

        return true
    }

    /**
     * Validate "ปวดหัว" (Headache) gesture
     * Characteristics: Hand near head/temple, index finger pointing
     */
    private fun validateHeadacheGesture(landmarks: HandLandmarkData, fingerStates: IntArray): Boolean {
        if (landmarks.landmarks.size < 21) return true
        
        // Headache: hand should be in upper portion of frame
        val wristY = landmarks.landmarks[0].y
        if (wristY > 0.45f) {
            Log.d(TAG, "   ปวดหัว: hand too low (wristY=$wristY)")
            return false
        }

        // Headache: fingertips together (few extended fingers)
        // Count extended fingers (excluding thumb)
        val extendedCount = if (fingerStates.size >= 5) {
            fingerStates.slice(1..4).sum()  // index, middle, ring, pinky
        } else 0

        // Should have 0-1 fingers extended (all tips together)
        if (extendedCount > 1) {
            Log.d(TAG, "   ปวดหัว: too many fingers extended (count=$extendedCount)")
            return false
        }

        return true
    }

    /**
     * Validate "แจ้งความ" (Report) gesture
     */
    private fun validateReportGesture(landmarks: HandLandmarkData, fingerStates: IntArray): Boolean {
        // Report is a single-hand gesture
        if (landmarks.landmarks.size > 21) {
            // If 2 hands detected, this might not be แจ้งความ
            Log.d(TAG, "   แจ้งความ: detected ${landmarks.landmarks.size / 21} hands, expected 1")
            return false
        }

        // Hand should be at face level (mouth to nose area, roughly Y 0.3-0.6)
        val wristY = landmarks.landmarks[0].y
        val handAtFaceLevel = wristY > 0.65f
        if (handAtFaceLevel) {
            Log.d(TAG, "แจ้งความ hand not at face level (wristY=$wristY)")
            return false
        }

        // Report: index finger should be extended
        if (fingerStates[1] != 1) {
            Log.d(TAG, "   แจ้งความ: index finger not extended")
            return false
        }
        return true
    }

    /**
     * Validate "หาย" (Lost) gesture
     */
    private fun validateLostGesture(landmarks: HandLandmarkData, fingerStates: IntArray): Boolean {
        // Must have 2 hands
        if (landmarks.landmarks.size < 42) {
            Log.v(TAG, "   หาย: need 2 hands, got ${landmarks.landmarks.size / 21}")
            return false
        }

        val hand1WristY = landmarks.landmarks[0].y
        val hand2WristY = landmarks.landmarks[21].y
        val hand1WristX = landmarks.landmarks[0].x
        val hand2WristX = landmarks.landmarks[21].x

        val verticalDistance = abs(hand1WristY - hand2WristY)
        val horizontalDistance = abs(hand1WristX - hand2WristX)

        // Key characteristics of "หาย" (Lost):
        // 1. Some horizontal separation (hands spread apart side-by-side)
        val hasHorizontalSeparation = horizontalDistance > 0.10f


        // 2. NOT vertically stacked (distinguish from ช่วย)
        val notVerticallyStacked = verticalDistance < 0.4f

        // 4. Both hands have fingers open (at least 3 fingers extended on each hand)
        val hand1FingersOpen = fingerStates.take(5).sum()
        val hand2FingersOpen = if (fingerStates.size >= 10) fingerStates.slice(5..9).sum() else 0
        val bothHandsOpen = hand1FingersOpen >= 3 && hand2FingersOpen >= 3

        // 5. Distinguish from "บัตรประชาชน" (ID card):
        //    - "หาย" is at chest level (Y around 0.3-0.7)
        //    - "บัตรประชาชน" might be lower
        val handsAtChestLevel = hand1WristY > 0.25f && hand1WristY < 0.75f &&
                hand2WristY > 0.25f && hand2WristY < 0.75f

        //val result = bothHandsOpen && handsAtChestLevel && hasHorizontalSeparation && notVerticallyStacked

        val result = handsAtChestLevel && notVerticallyStacked
        Log.v(TAG, "   หาย: result=$result (hSep=$hasHorizontalSeparation, " +
                "notVerticallyStacked=$notVerticallyStacked, " +
                "bothOpen=$bothHandsOpen, chestLevel=$handsAtChestLevel, " +
                "hDist=$horizontalDistance, vDist=$verticalDistance)")

        return result

    }
    /**
     * Validate "ช่วย" (Help) gesture
     * Characteristics: Two hands, one above the other, palms open
     */
    private fun validateHelpGesture(landmarks: HandLandmarkData, fingerStates: IntArray): Boolean {
        Log.d(TAG, "🆘 ช่วย: Checking help gesture...")
        if (landmarks.landmarks.size < 42) {
            Log.d(TAG, "   ช่วย: need 2 hands, got ${landmarks.landmarks.size / 21}")
            return false
        }

        // Check vertical alignment (one hand above the other)
        val hand1WristY = landmarks.landmarks[0].y
        val hand2WristY = landmarks.landmarks[21].y
        val hand1WristX = landmarks.landmarks[0].x
        val hand2WristX = landmarks.landmarks[21].x

        // Help: hands must be in upper position (less than 0.65) of frame (not at sides)
        val handsHighEnough = hand1WristY < 0.6f && hand2WristY < 0.6f

        val verticalDistance = abs(hand1WristY - hand2WristY)
        val horizontalDistance = abs(hand1WristX - hand2WristX)

        val hasVerticalSeparation = verticalDistance > 0.12f
        val isMoreVerticalThanHorizontal = verticalDistance >= horizontalDistance * 0.6f
        val handsAreStacked = horizontalDistance < 0.3f
        val result = handsHighEnough && hasVerticalSeparation && isMoreVerticalThanHorizontal && handsAreStacked

        Log.d(TAG, "🆘 ช่วย: result=$result, handsHighEnough=$handsHighEnough " +
                "hasVerticalSeparation=$hasVerticalSeparation " +
                "isMoreVerticalThanHorizontal=$isMoreVerticalThanHorizontal " +
                "handsAreStacked=$handsAreStacked " +
                "(h1Y=${String.format("%.2f", hand1WristY)}, h2Y=${String.format("%.2f", hand2WristY)}, vDist=${String.format("%.2f", verticalDistance)}, hDist=${String.format("%.2f", horizontalDistance)})")
        return result

    }

    /**
     * Validate "เจ็บคอ" (Sore Throat) gesture
     * Characteristics: Hand at throat level, fingers touching neck
     */
    private fun validateSoreThroatGesture(landmarks: HandLandmarkData, fingerStates: IntArray): Boolean {
        if (landmarks.landmarks.size < 21) return true

        val hand1WristY = landmarks.landmarks[0].y
        val hand2WristY = landmarks.landmarks[21].y
        val verticalDistance = abs(hand1WristY - hand2WristY)

        // Sore throat : hands must be very high up (near neck/face), not chest level
        val handsVeryHighUp = hand1WristY < 0.3f && hand2WristY < 0.3f
        val handsSameLevel = verticalDistance < 0.15f
        val result = handsVeryHighUp && handsSameLevel
        Log.v(TAG, "   result=$result, handsVeryHighUp=$handsVeryHighUp, handsSameLevel=$handsSameLevel")
        return result
    }

    /**
     * Validate "ห้องน้ำ" (Toilet) gesture
     * Characteristics: hand at mid-to-lower level (waist/chest)
     */
    private fun validateToiletGesture(landmarks: HandLandmarkData, fingerStates: IntArray): Boolean {
        if (landmarks.landmarks.size < 21) return true

        // Toilet: hand at mid-to-lower level (waist/chest area)
        val wristY = landmarks.landmarks[0].y
        val handAtMidLevel = wristY > 0.60f && wristY < 0.85f

        if (!handAtMidLevel) {
            Log.v(TAG, "ห้องน้ำ: hand not at mid-level (wristY=$wristY)")
            return false
        }
        return true
    }

    /**
     * Validate "บัตรประชาชน" (ID Card) gesture
     * Characteristics: Two hands, horizontally apart (side by side)
     */
    private fun validateIDCardGesture(landmarks: HandLandmarkData, fingerStates: IntArray): Boolean {
        if (landmarks.landmarks.size < 42) {
            Log.v(TAG, "   บัตรประชาชน: need 2 hands, got ${landmarks.landmarks.size / 21}")
            return false
        }
        
        // Check horizontal alignment (hands side by side)
        val hand1WristX = landmarks.landmarks[0].x
        val hand2WristX = landmarks.landmarks[21].x
        val horizontalDistance = abs(hand1WristX - hand2WristX)
        
        val hand1WristY = landmarks.landmarks[0].y
        val hand2WristY = landmarks.landmarks[21].y
        val verticalDistance = abs(hand1WristY - hand2WristY)
        
        // For ID card, horizontal distance should be greater than vertical
        if (horizontalDistance < verticalDistance * 1.5f) {
            Log.v(TAG, "   บัตรประชาชน: hands not horizontally aligned (hDist=$horizontalDistance, vDist=$verticalDistance)")
            return false
        }
        
        return true
    }

    // ========================================================================
    // CONSECUTIVE DETECTION LOGIC
    // ========================================================================

    /**
     * Handle consecutive detection to prevent false positives
     * Requires detecting the same word multiple times in a row
     */
    private fun handleConsecutiveDetection(word: String) {
        synchronized(detectionLock) {
            if (word == lastDetectedWord) {
                consecutiveCount++
            } else {
                consecutiveCount = 1
                lastDetectedWord = word
            }

            val requiredDetections = if (captureMode == MODE_SINGLE_FRAME) 1 else REQUIRED_CONSECUTIVE_DETECTIONS
            Log.v(TAG, "📊 Consecutive '$word': $consecutiveCount/$requiredDetections (mode=${if (captureMode ==
                +MODE_SINGLE_FRAME) "SINGLE_FRAME" else "CONTINUOUS"})")
            if (consecutiveCount >= requiredDetections) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastAnnounce = currentTime - lastAnnouncedTime

                // Check if we can announce (different word OR cooldown expired)
                val isDifferentWord = word != lastAnnouncedWord
                val cooldownExpired = timeSinceLastAnnounce > ANNOUNCE_COOLDOWN

                if (isDifferentWord || cooldownExpired) {
                    Log.i(TAG, "🎯 RECOGNIZED AND ANNOUNCING: $word")
                    onResult(word)
                    lastAnnouncedWord = word
                    lastAnnouncedTime = currentTime
                    stopDetection()  // Stop detection after successful recognition
                } else {
                    Log.d(
                        TAG,
                        "⏸️ Blocked: '$word' (same word, cooldown: ${timeSinceLastAnnounce}ms)"
                    )
                }
                consecutiveCount = 0
                lastDetectedWord = ""
            }

        }
    }

    /**
     * Reset consecutive detection counters
     */
    private fun resetConsecutiveCount() {
        synchronized(detectionLock) {
            if (consecutiveCount > 0) {
                Log.v(TAG, "Resetting consecutive count (was: $consecutiveCount)")
            }
            consecutiveCount = 0
            lastDetectedWord = ""
            // Note: Don't reset lastAnnouncedWord here to prevent re-announcing same word
        }
    }

    // ========================================================================
    // CAMERAX ANALYZER INTERFACE
    // ========================================================================

    /**
     * CameraX Analyzer interface implementation
     * Called for each camera frame
     */
    override fun analyze(image: ImageProxy) {
        // Log frame reception (periodically, not every frame)
        frameCount++
        if (frameCount % 60L == 0L) {
            Log.d(TAG, "📹 Frame received #$frameCount, isDetectionActive=$isDetectionActive")
        }

        val currentTime = System.currentTimeMillis()

        // Frame rate limiting
        if (captureMode == MODE_CONTINUOUS &&
            currentTime - lastInferenceTime < INFERENCE_INTERVAL_MS) {
            image.close()
            return
        }
        lastInferenceTime = currentTime

        // Skip if detection is not active
        if (!isDetectionActive) {
            image.close()
            return
        }

        try {
            // Set lock immediately for single-frame mode
            if (captureMode == MODE_SINGLE_FRAME && shouldCaptureFrame && !isProcessingCapture) {
                isProcessingCapture = true
                shouldCaptureFrame = false

                // Safety timeout - reset if processResults() never called
                mainHandler.postDelayed({
                    if (isProcessingCapture) {
                        Log.d(TAG, "Capture processing timeout, resetting lock")
                        isProcessingCapture = false
                    }
                }, PROCESSING_TIMEOUT_MS)

                Log.d(TAG, "📸 Single-frame capture Triggered - next result will be processed")
            }

            // Convert to bitmap and rotate if needed
            val bitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees

            // Rotate bitmap to match preview orientation
            val rotatedBitmap = if (rotation != 0) {
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotation.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            val mpImage = BitmapImageBuilder(rotatedBitmap).build()

            // Log rotation info for debugging
            // Log.d(TAG, "Image rotation: $rotation, original size=${bitmap.width}x${bitmap.height}, rotated size=${rotatedBitmap.width}x${rotatedBitmap.height}")

            handLandmarker?.detectAsync(mpImage, currentTime)

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame: ${e.message}")

            // Reset lock on error
            if (captureMode == MODE_SINGLE_FRAME) {
                isProcessingCapture = false
            }
        } finally {
            image.close()
        }
    }

    // ================================================================
    // SINGLE-FRAME CAPTURE MDOE
    // =================================================================

    /**
     * Set capture mode (continuous or single-frame)
     */
    fun setCaptureMode(mode: Int) {
        captureMode = mode
        shouldCaptureFrame = false
        isProcessingCapture = false
        Log.d(TAG, "Capture mode set to:${if (mode == MODE_SINGLE_FRAME) "SINGLE FRAME" else "CONTINUOUS"}")
        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Trigger capture of a single frame
     * Call this from CameraActivity during countdown
     */
    fun captureSingleFrame() {
        shouldCaptureFrame = true
        Log.d(TAG, "Single frame capture enabled")
    }
    /**
     * Clean up resources
     */
    fun cleanup() {
        handLandmarker?.close()
        handLandmarker = null
        Log.d(TAG, "Analyzer cleaned up")
    }
}

// ============================================================================
// HAND FEATURE EXTRACTOR
// ============================================================================

/**
 * Extracts features from hand landmarks for improved gesture recognition
 * 
 * MediaPipe Hand Landmarks (21 points per hand):
 * 0: WRIST
 * 1-4: THUMB (CMC, MCP, IP, TIP)
 * 5-8: INDEX FINGER (MCP, PIP, DIP, TIP)
 * 9-12: MIDDLE FINGER (MCP, PIP, DIP, TIP)
 * 13-16: RING FINGER (MCP, PIP, DIP, TIP)
 * 17-20: PINKY (MCP, PIP, DIP, TIP)
 */
class HandFeatureExtractor {

    companion object {
        private const val TAG = "HandFeatureExtractor"
        
        // MediaPipe hand connections for angle calculation
        private val FINGER_CONNECTIONS = listOf(
            // Thumb: 3 angles
            Triple(0, 1, 2), Triple(1, 2, 3), Triple(2, 3, 4),
            // Index: 3 angles
            Triple(0, 5, 6), Triple(5, 6, 7), Triple(6, 7, 8),
            // Middle: 3 angles
            Triple(0, 9, 10), Triple(9, 10, 11), Triple(10, 11, 12),
            // Ring: 3 angles
            Triple(0, 13, 14), Triple(13, 14, 15), Triple(14, 15, 16),
            // Pinky: 3 angles
            Triple(0, 17, 18), Triple(17, 18, 19), Triple(18, 19, 20)
        )

        // Fingertip landmark indices
        private val FINGERTIP_INDICES = listOf(4, 8, 12, 16, 20)
    }

    /**
     * Extract angle-based features from hand landmarks
     * 
     * WHY ANGLES?
     * - Angles are invariant to scale and position
     * - They capture the "shape" of the hand pose
     * - Two hands making the same sign will have similar angles
     *   regardless of where they are in the frame
     */
    fun extractAngleFeatures(landmarks: HandLandmarkData): FloatArray {
        if (landmarks.landmarks.size < 21) return FloatArray(0)

        val angles = mutableListOf<Float>()
        val numLandmarksPerHand = 21
        val numHands = landmarks.landmarks.size / numLandmarksPerHand

        for (handIdx in 0 until numHands) {
            val startIdx = handIdx * numLandmarksPerHand

            for ((i1, i2, i3) in FINGER_CONNECTIONS) {
                val idx1 = startIdx + i1
                val idx2 = startIdx + i2
                val idx3 = startIdx + i3

                if (idx3 < landmarks.landmarks.size) {
                    val angle = calculateAngle(
                        landmarks.landmarks[idx1],
                        landmarks.landmarks[idx2],
                        landmarks.landmarks[idx3]
                    )
                    angles.add(angle)
                }
            }

            // Add inter-finger angles (between adjacent fingers)
            for (i in 0 until 4) {
                val tip1Idx = startIdx + FINGERTIP_INDICES[i]
                val tip2Idx = startIdx + FINGERTIP_INDICES[i + 1]
                val wristIdx = startIdx

                if (tip2Idx < landmarks.landmarks.size) {
                    val angle = calculateAngle(
                        landmarks.landmarks[tip1Idx],
                        landmarks.landmarks[wristIdx],
                        landmarks.landmarks[tip2Idx]
                    )
                    angles.add(angle)
                }
            }
        }

        return angles.toFloatArray()
    }

    /**
     * Calculate angle between three points using dot product
     * The angle is measured at point p2 (the middle point)
     */
    private fun calculateAngle(p1: Point3D, p2: Point3D, p3: Point3D): Float {
        // Vector from p2 to p1
        val v1x = p1.x - p2.x
        val v1y = p1.y - p2.y
        val v1z = p1.z - p2.z

        // Vector from p2 to p3
        val v2x = p3.x - p2.x
        val v2y = p3.y - p2.y
        val v2z = p3.z - p2.z

        // Magnitudes
        val mag1 = sqrt(v1x * v1x + v1y * v1y + v1z * v1z)
        val mag2 = sqrt(v2x * v2x + v2y * v2y + v2z * v2z)

        if (mag1 < 0.0001f || mag2 < 0.0001f) return 0f

        // Dot product
        val dot = v1x * v2x + v1y * v2y + v1z * v2z
        val cosAngle = (dot / (mag1 * mag2)).coerceIn(-1f, 1f)

        return acos(cosAngle)  // Returns angle in radians (0 to π)
    }

    /**
     * Detect which fingers are extended (up) vs curled (down)
     * Returns array of 0s and 1s for each finger (5 per hand)
     * 
     * Logic:
     * - A finger is "up" if its tip is higher (smaller y) than its PIP joint
     * - For thumb, we check x-coordinate instead
     */
    fun detectFingerStates(landmarks: HandLandmarkData): IntArray {
        if (landmarks.landmarks.size < 21) return IntArray(0)

        val states = mutableListOf<Int>()
        val numLandmarksPerHand = 21
        val numHands = landmarks.landmarks.size / numLandmarksPerHand

        for (handIdx in 0 until numHands) {
            val startIdx = handIdx * numLandmarksPerHand

            // Thumb: Compare tip.x with IP joint.x (indices 4 and 3)
            val thumbTip = landmarks.landmarks[startIdx + 4]
            val thumbIP = landmarks.landmarks[startIdx + 3]
            states.add(if (abs(thumbTip.x) > abs(thumbIP.x)) 1 else 0)

            // Other fingers: Compare tip.y with PIP joint.y
            val fingerPIPIndices = listOf(6, 10, 14, 18)  // PIP joints
            val fingerTipIndices = listOf(8, 12, 16, 20)  // Tips

            for (i in fingerPIPIndices.indices) {
                val tipIdx = startIdx + fingerTipIndices[i]
                val pipIdx = startIdx + fingerPIPIndices[i]

                if (tipIdx < landmarks.landmarks.size && pipIdx < landmarks.landmarks.size) {
                    val tip = landmarks.landmarks[tipIdx]
                    val pip = landmarks.landmarks[pipIdx]
                    // In screen coordinates, smaller y = higher position
                    states.add(if (tip.y < pip.y) 1 else 0)
                }
            }
        }

        return states.toIntArray()
    }

    /**
     * Normalize hand landmarks to be invariant to position and scale
     * 
     * Process:
     * 1. Translate: Move wrist to origin (0,0,0)
     * 2. Scale: Normalize so max distance from wrist = 1.0
     */
    fun normalizeLandmarks(landmarks: HandLandmarkData): HandLandmarkData {
        if (landmarks.landmarks.isEmpty()) return landmarks

        val numLandmarksPerHand = 21
        val numHands = landmarks.landmarks.size / numLandmarksPerHand
        val normalizedPoints = mutableListOf<Point3D>()

        // Process each hand separately
        for (handIdx in 0 until numHands) {
            val startIdx = handIdx * numLandmarksPerHand
            val endIdx = minOf(startIdx + numLandmarksPerHand, landmarks.landmarks.size)

            if (endIdx - startIdx < numLandmarksPerHand) continue

            // Get wrist position for this hand
            val wrist = landmarks.landmarks[startIdx]

            // Step 1: Translate all points so wrist is at origin
            val translated = (startIdx until endIdx).map { i ->
                Point3D(
                    landmarks.landmarks[i].x - wrist.x,
                    landmarks.landmarks[i].y - wrist.y,
                    landmarks.landmarks[i].z - wrist.z
                )
            }

            // Step 2: Find max distance from wrist (for scaling)
            val maxDistance = translated.maxOfOrNull { 
                sqrt(it.x * it.x + it.y * it.y + it.z * it.z) 
            } ?: 1.0f

            // Step 3: Scale all points so max distance = 1.0
            val scaled = if (maxDistance > 0.001f) {
                translated.map { 
                    Point3D(it.x / maxDistance, it.y / maxDistance, it.z / maxDistance) 
                }
            } else {
                translated
            }

            normalizedPoints.addAll(scaled)
        }

        return HandLandmarkData(normalizedPoints)
    }
}

// ============================================================================
// DEBUG ANALYZER (for testing)
// ============================================================================

/**
 * Debug version of the analyzer with additional logging
 * Use this for troubleshooting recognition issues
 */
class DebugSignLanguageAnalyzer(
    private val context: Context,
    private val videoProcessor: VideoProcessor,
    private val onResult: (String) -> Unit,
    private val onDebug: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val analyzer = SignLanguageAnalyzer(context, videoProcessor) { result ->
        onDebug("✅ Final Result: $result")
        onResult(result)
    }

    override fun analyze(imageProxy: ImageProxy) {
        analyzer.analyze(imageProxy)
    }

    fun startDetection() = analyzer.startDetection()
    fun stopDetection() = analyzer.stopDetection()
    fun cleanup() = analyzer.cleanup()
}
