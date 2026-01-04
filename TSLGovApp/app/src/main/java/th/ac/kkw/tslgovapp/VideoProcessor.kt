package th.ac.kkw.tslgovapp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlin.math.*
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.framework.image.BitmapImageBuilder
import th.ac.kkw.tslgovapp.model.HandLandmarkData
import th.ac.kkw.tslgovapp.model.Point3D
import th.ac.kkw.tslgovapp.model.RecognitionResult
import java.io.*


class VideoProcessor(private val context: Context) {

    // 🔧 แยก Template เป็นสองประเภท
    private val singleHandTemplates = mutableMapOf<String, HandLandmarkData>()
    private val doubleHandTemplates = mutableMapOf<String, HandLandmarkData>()
    private val featureExtractor = HandFeatureExtractor()
    companion object {
        private const val TAG = "VideoProcessor"
        private const val MODEL_FILE = "hand_landmarker.task" // ✅ Correct path
    }

    private var handLandmarker: HandLandmarker? = null
    private var isInitialized = false

    // ⭐ เปลี่ยนจากเก็บแค่ HandLandmarkData เป็นเก็บทั้ง Template และจำนวนมือ
    private data class SignTemplate(
        val landmarks: HandLandmarkData,
        val numHands: Int,  // เพิ่มข้อมูลจำนวนมือที่ใช้
        val sourceVideo: String = ""
    ) : java.io.Serializable

    private val signTemplates = mutableMapOf<String,
            MutableList<SignTemplate>>()

    init {
        Logger.saveLogcatFor(context, VideoProcessor::class)
        setupMediaPipe()

    }

    private fun setupMediaPipe() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_FILE)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.3f)
                .setMinHandPresenceConfidence(0.3f)
                .setMinTrackingConfidence(0.32f)
                .setRunningMode(RunningMode.IMAGE) // Change to IMAGE mode for frame processing
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            isInitialized = true
            Log.d("VideoProcessor", "MediaPipe HandLandmarker initialized.")
        } catch (e: Exception) {
            Log.e("VideoProcessor", "Error setting up MediaPipe", e)
            isInitialized = false
        }
    }


    /**
     * Save templates to cache file
     */
    fun saveTemplatesToCache() {
        try {
            val signTemplatesFile = File(context.cacheDir, "cached_sign_templates.dat")

            FileOutputStream(signTemplatesFile).use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(signTemplates)
                }
            }

            Log.d(TAG, "Templates cached successfully (${signTemplates.size} words)")
        } catch (e: Exception) {
            Log.e("VideoProcessor", "Failed to cache templates: ${e.message}")
        }
    }

    fun loadTemplatesFromCache(): Boolean {
        return try {
            val signTemplatesFile = File(context.cacheDir, "cached_sign_templates.dat")
            if (!signTemplatesFile.exists()) {
                Log.d(TAG, "No template cache found")
                return false
            }

            FileInputStream(signTemplatesFile).use { fis ->
                ObjectInputStream(fis).use { ois ->
                    val loaded = ois.readObject() as MutableMap<String, MutableList<SignTemplate>>
                    signTemplates.clear()
                    signTemplates.putAll(loaded)
                }
            }

            val totalTemplates = signTemplates.values.sumOf {it.size}
            Log.d(TAG, "Templates loaded from cache (${signTemplates.size} words, $totalTemplates templates)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load templates from cache: ${e.message}")
            false
        }
    }
    /**
     * Detects which hand is actively signing based on position and movement
     * Returns the index of the active hand (0 or 1)
     */
    private fun findActiveHand(hands: List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>>): Int {
        if (hands.size == 1) return 0

        val hand0Wrist = hands[0][0]
        val hand1Wrist = hands[1][0]

        // Active hand characteristics:
        // 1. Higher position (lower y value in normalized coordinates)
        // 2. More forward (lower z value - closer to camera)
        // 3. More centered (x closer to 0.5)

        val hand0Score = hand0Wrist.y() + (hand0Wrist.z() * 0.5f) + kotlin.math.abs(hand0Wrist.x() - 0.5f) * 0.3f
        val hand1Score = hand1Wrist.y() + (hand1Wrist.z() * 0.5f) + kotlin.math.abs(hand1Wrist.x() - 0.5f) * 0.3f

        return if (hand0Score < hand1Score) 0 else 1
    }

    // ============ FIX 1: เพิ่ม Hand Normalization ============

    /**
     * Normalize hand landmarks เพื่อให้ไม่สนใจตำแหน่งและขนาดของมือ
     * - Translate: ย้ายข้อมือมาที่จุด origin (0,0,0)
     * - Scale: ปรับขนาดมือให้เท่ากัน
     */
    private fun normalizeHandLandmarks(landmarks: HandLandmarkData): HandLandmarkData {
        if (landmarks.landmarks.isEmpty()) return landmarks

        // 1. หาศูนย์กลางของมือ (wrist - index 0)
        val wrist = landmarks.landmarks[0]

        // 2. Translate ทุกจุดให้ wrist เป็น origin
        val translated = landmarks.landmarks.map { point ->
            Point3D(
                x = point.x - wrist.x,
                y = point.y - wrist.y,
                z = point.z - wrist.z
            )
        }

        // 3. หาขนาดของมือ (max distance from wrist)
        val maxDistance = translated.maxOfOrNull { point ->
            kotlin.math.sqrt(point.x * point.x + point.y * point.y + point.z * point.z)
        } ?: 1.0f

        // 4. Scale ให้มือมีขนาดมาตรฐาน
        val normalized = if (maxDistance > 0.001f) {
            translated.map { point ->
                Point3D(
                    x = point.x / maxDistance,
                    y = point.y / maxDistance,
                    z = point.z / maxDistance
                )
            }
        } else {
            translated // ถ้า maxDistance เกือบศูนย์ ไม่ scale
        }

        return HandLandmarkData(normalized)
    }


    fun createTemplateFromVideos(
        label: String,
        videoUris: List<Uri>,
        numHands: Int = 1  // ⭐ เพิ่ม parameter นี้ (default = 1 มือ)
    ) {
        Log.d(TAG, "Creating templates for '$label' from ${videoUris.size} videos")
        if (handLandmarker == null) {
            Log.e(TAG, "HandLandmarker not initialized.")
            return
        }

        // Initialize list for this sign if not exists
        if (!signTemplates.containsKey(label)) {
            signTemplates[label] = mutableListOf()
        }

        for (videoUri in videoUris) {
            try {
                val videoPath = getTempFileFromUri(context, videoUri)
                if (videoPath != null) {
                    val frames = extractKeyFramesFromVideo(videoPath)
                    val framesLandmarks = mutableListOf<List<Point3D>>()

                    for (bitmap in frames) {
                        val mpImage = BitmapImageBuilder(bitmap).build()
                        val result = handLandmarker?.detect(mpImage)

                        if (result != null && result.landmarks().isNotEmpty()) {
                            // ⭐ ตรวจสอบจำนวนมือที่ตรวจพบ
                            val detectedHands = result.landmarks().size
                            Log.d(TAG, "checking template $label: $detectedHands hand(s)")

                            // Handle the case where we want 1 hand but detect 2
                            if (numHands == 1 && detectedHands >= 1) {
                                // For single hand, use the active hand
                                val activeHandIndex = if (detectedHands == 2)
                                    findActiveHand(result.landmarks()) else 0
                                val activeHand = result.landmarks()[activeHandIndex]

                                val handLandmarks = mutableListOf<Point3D>()
                                activeHand.forEach { lm ->
                                    handLandmarks.add(Point3D(lm.x(), lm.y(), lm.z()))
                                }
                                framesLandmarks.add(handLandmarks)

                            } else if (detectedHands == numHands) {
                                // For multi-hand, combine all hands (sorted by X)
                                val sortedHands = result.landmarks().sortedBy { it[0].x() }
                                val allHandsLandmarks = mutableListOf<Point3D>()
                                for (hand in sortedHands) {
                                    hand.forEach { lm ->
                                        allHandsLandmarks.add(Point3D(lm.x(), lm.y(), lm.z()))
                                    }
                                }
                                framesLandmarks.add(allHandsLandmarks)
                            }
                        }
                    }


                    // Create ONE template per video (averaged from its frames)
                    if (framesLandmarks.isNotEmpty()) {
                        val averagedLandmarks = calculateAverageLandmarks(framesLandmarks)
                        signTemplates[label]!!.add(
                            SignTemplate(
                                landmarks = averagedLandmarks,
                                numHands = numHands,
                                sourceVideo = videoUri.toString()
                            )
                        )
                        Log.i(
                            TAG,
                            "✅ Added template for '$label' from $videoUri (${signTemplates[label]!!.size} total)"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing video: $videoUri", e)
            }
        }
        Log.i(TAG, "📦 '$label' now has ${signTemplates[label]?.size ?: 0} templates")
    }

    private fun checkGestureCharacteristics(
        landmarks: HandLandmarkData,
        word: String
    ): Boolean {
        // Only check hand count - let template matching handle the rest
        val config =

            SignLanguageConfig.getWordByName(word)
        val requiredHands = config?.numHands ?: 1
        val actualHands = landmarks.landmarks.size / 21
        Log.d(TAG, "🔍 Checking hand count: $word required=$requiredHands, actual=$actualHands" +
                "landmarks size = ${landmarks.landmarks.size}")
        // Basic hand count check
        if (actualHands < requiredHands) {
            return false
        }

        // Log wrist position for debugging
        if (actualHands >= 1) {
            val wristX = landmarks.landmarks[0].x
            val wristY = landmarks.landmarks[0].y
            Log.d(TAG, "   👋 Hand 1 wrist: X=${String.format("%.3f", wristX)}, Y=${String.format("%.3f", wristY)}")
        }
        if (actualHands >= 2) {
            val wrist2X = landmarks.landmarks[21].x
            val wrist2Y = landmarks.landmarks[21].y
            Log.d(TAG, "   👋 Hand 2 wrist: X=${String.format("%.3f", wrist2X)}, Y=${String.format("%.3f", wrist2Y)}")
        }

        val fingerStates = featureExtractor.detectFingerStates(landmarks)
        val indexTipY = landmarks.landmarks[8].y // Index finger tip
        val middleTipY = landmarks.landmarks[12].y // Middle finger tip
        val ringTipY = landmarks.landmarks[16].y // Ring finger tip
        val pinkyTipY = landmarks.landmarks[20].y // Pinky finger tip

        Log.d(TAG, "Finger states: $fingerStates")
        Log.d(TAG, "indexTipY=$indexTipY, middleTipY=$middleTipY, ringTipY=$ringTipY, pinkyTipY=$pinkyTipY")
        // Additional checks for 2-hand signs to distinguish them
        if (actualHands >= 2 && landmarks.landmarks.size >= 42) {
            val leftWristX = landmarks.landmarks[0].x   // Left hand wrist
            val rightWristX = landmarks.landmarks[21].x // Right hand wrist
            val leftWristY = landmarks.landmarks[0].y
            val rightWristY = landmarks.landmarks[21].y


            val horizontalDistance = kotlin.math.abs(leftWristX - rightWristX)
            val verticalDistance = kotlin.math.abs(leftWristY - rightWristY)


            Log.d(TAG, "   👐 Two-hand check for '$word': horizontalDist=${String.format("%.3f",
                horizontalDistance)}, verticalDist=${String.format("%.3f", verticalDistance)}")

            when (word) {
                "หาย" -> {
                    // Key characteristics of "หาย" (Lost):
                    // 1. horizontal separation (hands spread apart side-by-side)
                    val hasHorizontalSeparation = horizontalDistance > 0.10f

                    // 2. Hands are close vertically (at similar height)
                    val handsSameLevel = verticalDistance < 0.20f

                    // 3. Horizontal dominates over vertical (side-by-side, not stacked)
                    val isMoreHorizontal = horizontalDistance > verticalDistance * 1.5f

                    val hand1FingersOpen = fingerStates.take(5).sum()
                    val hand2FingersOpen = if (fingerStates.size >= 10) fingerStates.slice(5..9).sum() else 0
                    val bothHandsOpen = hand1FingersOpen >= 3 && hand2FingersOpen >= 3

                    // 5. Distinguish from "บัตรประชาชน" (ID card):
                    //    - "หาย" is at chest level (Y around 0.3-0.7)
                    //    - "บัตรประชาชน" might be lower
                    val handsAtChestLevel = leftWristY > 0.25f && leftWristY < 0.75f &&
                            rightWristY > 0.25f && rightWristY < 0.75f
                    // 2. NOT vertically stacked (distinguish from ช่วย)
                    val notVerticallyStacked = verticalDistance < 0.4f
                    // val result = bothHandsOpen && handsAtChestLevel && hasHorizontalSeparation
                    val result = handsAtChestLevel && notVerticallyStacked

                    Log.d(TAG, "   หาย: result=$result" +
                            "chestLevel=$handsAtChestLevel, bothHandsOpen=$bothHandsOpen " +
                            "hasHorizontalSeparation=$hasHorizontalSeparation" +
                            "notVerticallyStacked=$notVerticallyStacked" +
                            "hDist=$horizontalDistance, vDist=$verticalDistance)")

                    return result


                }
                "บัตรประชาชน" -> {
                    // ID Card: hands are horizontally apart (side by side)
                    val isMoreHorizontal = horizontalDistance > verticalDistance * 1.8f
                    val handsAtWaist = leftWristY < 0.5 && rightWristY < 0.5
                    Log.d(TAG, "      บัตรประชาชน: isMoreHorizontal=$isMoreHorizontal")
                    return isMoreHorizontal && handsAtWaist

                }
                "ช่วย" -> {
                    val handsHighEnough = leftWristY < 0.7f && rightWristY < 0.7f
                    val handsAreStacked = horizontalDistance < 0.3f
                    Log.d(TAG, "handsHighEnough=$handsHighEnough" +
                    "handsAreStacked=$handsAreStacked")
                    return handsHighEnough && handsAreStacked
                }
                "เจ็บคอ" -> {
                    // Neck ache: hands near neck (high position) and at same level (small vertical ```1distance)
                    val handsHighUp = leftWristY < 0.4f && rightWristY < 0.4f
                    val handsSameLevel = verticalDistance < 0.15f
                    val result = handsHighUp && handsSameLevel
                    Log.d(TAG, "      เจ็บคอ: result=$result (highUp=$handsHighUp,  sameLevel=$handsSameLevel, leftY=${String.format("%.3f", leftWristY)}, rightY=${String.format("%.3f",
                        rightWristY)})")
                    return result
                }
                "หนังสือเดินทาง" -> {
                    // Passport: thumbs are more apart horizontally (like open book)
                    val leftThumbX = landmarks.landmarks[4].x   // Left hand thumb tip
                    val rightThumbX = landmarks.landmarks[25].x // Right hand thumb tip (21 + 4)
                    val thumbHorizontalDistance = kotlin.math.abs(leftThumbX - rightThumbX)
                    val handsAtSameLevel = verticalDistance < 0.25f
                    val thumbsWideApart = thumbHorizontalDistance > 0.35f
                    val handsAtWaist = leftWristY < 0.5 && rightWristY < 0.5
                    val result = thumbsWideApart && handsAtSameLevel && handsAtWaist
                    Log.d(TAG, "      หนังสือเดินทาง: result=$result (thumbsApart=$thumbsWideApart, thumbDist=${String.format("%.3f", thumbHorizontalDistance)}, sameLevel=$handsAtSameLevel)")
                    return result
                }

            }
        } else if (actualHands == 1 && landmarks.landmarks.size >= 21) {
            val wristX = landmarks.landmarks[0].x
            val wristY = landmarks.landmarks[0].y
            when (word) {
                "ปวดหัว" -> {
                    val wristY = landmarks.landmarks[0].y
                    if (wristY > 0.4f) {
                        Log.d(TAG, "   ปวดหัว: hand too low (wristY=$wristY)")
                        return false
                    }

                    // Reject if index finger is pointing up (that's report, not headache)
                    val indexTipY = landmarks.landmarks[8].y
                    val indexPointingUp = indexTipY < wristY - 0.12f
                    if (indexPointingUp) {
                        Log.v(TAG, "ปวดหัว: index finger is pointing up rejecting (wristY=$wristY, indexTipY=$indexTipY")
                        return false
                    }
                    return true
                }
                "เครื่องบิน" -> {
                    // fingerStates: [Thumb, Index, Middle, Ring, Pinky]
                    val thumb = fingerStates[0]
                    val index = fingerStates[1]
                    val middle = fingerStates[2]
                    val ring = fingerStates[3]
                    val pinky = fingerStates[4]

                    Log.d(TAG, "   ✈️ Airplane: thumb=$thumb, index=$index, middle=$middle, ring=$ring, pinky=$pinky")

                    // Airplane: thumb, index, pinky extended (3 wing fingers)
                    // Middle and ring should be curled (tucked in)
                    val wingFingersExtended = pinky >= 1


                    if (!wingFingersExtended) {
                        Log.d(TAG, "   ❌ Airplane: pinky should be extended")
                        return false
                    }

                    // Optional: Check if middle/ring are curled (lenient - allow if at least one is curled)
                    if (middle == 1 || ring == 1) {
                        Log.d(TAG, "   ⚠️ Airplane: middle/ring should ideally be curled")
                    }

                    // Check hand position - airplane is at mid-level
                    val wristY = landmarks.landmarks[0].y
                    if (wristY < 0.3f) {
                        Log.d(TAG, "   ❌ Airplane: hand too high (wristY=$wristY), might be ปวดหัว")
                        return false
                    }
                    if (wristY > 0.7f) {
                        Log.d(TAG, "   ❌ Airplane: hand too low (wristY=$wristY), might be ปวดหัว")
                        return false
                    }
                    Log.d(TAG, "   ✅ Airplane: wing fingers extended, hand at mid-level")
                    return true
                }
                "ห้องน้ำ" -> {
                    // Toilet: hand at mid-to-lower level (waist/chest)
                    // Not too high (different from ปวดหัว), not too low (hands at sides)
                    val wristY = landmarks.landmarks[0].y
                    val handsAtMidLevel = wristY > 0.6f && wristY < 0.85f
                    if (!handsAtMidLevel) {
                        Log.v(TAG, "ห้องน้ำ: hand not at mid-level (wristY=$wristY)")
                        return false
                    }
                    return true
                }
                "แจ้งความ" -> {
                    // Hand should be at face level (mouth to nose area, roughly Y 0.3-0.6)
                    val wristY = landmarks.landmarks[0].y
                    val handAtFaceLevel = wristY < 0.50f

                    // Report: index finger should point up (key distinguishing feature from headache)


                    // Check 1: Index figer tip is significantly higher than wrist (pointing up)
                    val indexVerticalExtension = wristY - indexTipY
                    val indexPointingUp = indexVerticalExtension > 0.08f // Must be pointing up

                    // Check 2: Index finger tip is the highest (or tied for hightest) among fingers
                    val indexIsHighest = indexTipY <= minOf(middleTipY, ringTipY, pinkyTipY) + 0.02f

                    // Check 3: Index finger tip is significantly higher than middle finger (distinguish from open hand)
                    val indexHigherThanMiddle = middleTipY - indexTipY > 0.03f


                    val result = indexIsHighest && handAtFaceLevel && indexPointingUp

                    Log.d(TAG, "แจ้งความ handAtFaceLevel=$handAtFaceLevel, indexIsHighest=$indexIsHighest," +
                            "indexPointingUp=$indexPointingUp, wristY=$wristY, indexTipY = $indexTipY)")
                    return result
                }
            }
        }

        return true

    }
    private fun getTempFileFromUri(context: Context, uri: Uri): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val tempFile = File(context.cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
                val outputStream = FileOutputStream(tempFile)
                inputStream.copyTo(outputStream)
                inputStream.close()
                outputStream.close()
                tempFile.absolutePath
            } else null
        } catch (e: Exception) {
            Log.e("VideoProcessor", "Error creating temp file from Uri", e)
            null
        }
    }

    private fun extractKeyFramesFromVideo(videoPath: String): List<Bitmap> {
        val frames = mutableListOf<Bitmap>()

        try {
            // Use MediaMetadataRetriever to extract frames
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(videoPath)

            // Get video duration
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLong() ?: 0

            // Extract frames at regular intervals (e.g., every 500ms)
            val interval = 500L // milliseconds
            for (time in 0 until duration step interval) {
                val bitmap = retriever.getFrameAtTime(time * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                bitmap?.let { frames.add(it) }
            }

            retriever.release()
        } catch (e: Exception) {
            Log.e("VideoProcessor", "Error extracting frames from video: $videoPath", e)
        }

        return frames
    }

    /**
     * ⭐ ปรับฟังก์ชันจดจำให้เปรียบเทียบเฉพาะคำที่มีจำนวนมือตรงกัน
     */
    // ============ FIX 2: ปรับปรุง Recognition ============

    fun recognizeSign(
        currentGestureLandmarks: HandLandmarkData,
        numDetectedHands: Int
    ): RecognitionResult? {
        if (signTemplates.isEmpty()) {
            Log.w(TAG, "No templates loaded")
            return null
        }
        Log.d(TAG, "========================================")
        Log.d(TAG, "🔍 Detected hands: $numDetectedHands")

        // Normalize current input
        val normalizedCurrent = normalizeHandLandmarks(currentGestureLandmarks)

        var bestMatchLabel: String? = null
        var minDistance = Float.MAX_VALUE
        var bestConfidence: Float = 0f

        // Iterate through all signs
        for ((label, templates) in signTemplates) {
            // Skip if hand count doesn't match
            val requiredHands = templates.firstOrNull()?.numHands ?: 1
            if (requiredHands != numDetectedHands) {
                Log.d(TAG, "   ❌ '$label' skipped: needs $requiredHands hands, got $numDetectedHands")
                continue
            }

            // Check gesture characteristics (hand position, alignment, etc.)
            if (!checkGestureCharacteristics(currentGestureLandmarks, label)) {
                Log.d(TAG, "   ❌ '$label' skipped: gesture characteristics don't match")
                continue
            }

            // Trim current landmarks to match template
            val requiredLandmarks = requiredHands * 21
            val currentToCompare = if (currentGestureLandmarks.landmarks.size > requiredLandmarks) {
                HandLandmarkData(currentGestureLandmarks.landmarks.take(requiredLandmarks))
            } else {
                currentGestureLandmarks
            }
            val normalizedCurrentForCompare = normalizeHandLandmarks(currentToCompare)

            // Find the BEST matching template for this sign
            var bestDistanceForSign = Float.MAX_VALUE
            for ((index, template) in templates.withIndex()) {
                if (normalizedCurrentForCompare.landmarks.size != template.landmarks.landmarks.size) {
                    continue
                }

                val normalizedTemplate = normalizeHandLandmarks(template.landmarks)
                val distance = calculateEuclideanDistance(normalizedCurrentForCompare, normalizedTemplate)

                if (distance < bestDistanceForSign) {
                    bestDistanceForSign = distance
                }
            }

            Log.d(TAG, "   '$label': best distance = ${String.format("%.4f", bestDistanceForSign)} (from ${templates.size} templates)")

            // Compare with overall best
            if (bestDistanceForSign < minDistance) {
                minDistance = bestDistanceForSign
                bestMatchLabel = label

                val maxDistance = if (requiredHands == 2) 3.5f else 2.0f
                bestConfidence = max(0.0f, (1.0f - minDistance / maxDistance) * 100)
            }
        }

        if (bestMatchLabel != null) {
            // Use different thresholds: 2-hand signs need higher confidence
            val requiredHands = signTemplates[bestMatchLabel]?.firstOrNull()?.numHands ?: 1
            val minConfidenceThreshold = if (requiredHands == 2) {
                50f  // Two-hand signs: stricter threshold to avoid false positive
            } else {
                40f  // Single-hand signs: increased to reduce false positive
            }

            Log.d(TAG, "🎯 Best: $bestMatchLabel (${String.format("%.1f%%", bestConfidence)}) threshold=$minConfidenceThreshold%")

            if (bestConfidence >= minConfidenceThreshold) {
                return RecognitionResult(
                    word = bestMatchLabel,
                    confidence = bestConfidence,
                    distance = minDistance
                )
            }
        }

        Log.d(TAG, "❌ No match found")
        return null
    }
    // In VideoProcessor.kt

    /**
     * คำนวณ Euclidean Distance เฉลี่ยต่อ Landmark (RMSE) ระหว่าง Landmark สองชุด
     */
    private fun calculateEuclideanDistance(current: HandLandmarkData, template: HandLandmarkData): Float {
        Log.v(TAG, "🔍 Distance calculation: current=${current.landmarks.size}, template=${template.landmarks.size}")

        if (current.landmarks.size != template.landmarks.size) {
            Log.w(TAG, "⚠️ Size mismatch! Returning MAX_VALUE")
            return Float.MAX_VALUE
        }

        val numLandmarks = current.landmarks.size
        if (numLandmarks == 0) return Float.MAX_VALUE

        var sumOfSquaredDistances = 0.0f
        for (i in current.landmarks.indices) {
            val dx = current.landmarks[i].x - template.landmarks[i].x
            val dy = current.landmarks[i].y - template.landmarks[i].y
            val dz = current.landmarks[i].z - template.landmarks[i].z
           // sumOfSquaredDistances += dx * dx + dy * dy + dz * dz
            // ✅ แบบใหม่: ลดน้ำหนัก Z ลง 50% (คูณ 0.5)
            // จะช่วยให้ท่า "ห้องน้ำ" และ "ช่วย" ตรวจจับง่ายขึ้นมากแม้ถือกล้องเอียง
            sumOfSquaredDistances += dx * dx + dy * dy + (dz * dz * 0.5f)
        }

        val meanSquaredError = sumOfSquaredDistances / numLandmarks
        val distance = sqrt(meanSquaredError)

        Log.v(TAG, "   Distance: $distance, MSE: $meanSquaredError")
        return distance
    }

    // ========== สิ้นสุดโค้ดที่เพิ่มเข้ามาใหม่ ==========

    private fun calculateAverageLandmarks(landmarksList: List<List<Point3D>>): HandLandmarkData {
        val numFrames = landmarksList.size
        val numLandmarks = landmarksList.firstOrNull()?.size ?: 0
        if (numFrames == 0 || numLandmarks == 0) return HandLandmarkData(emptyList())

        val sums = Array(numLandmarks) { Point3D(0f, 0f, 0f) }
        for (frameLandmarks in landmarksList) {
            if (frameLandmarks.size == numLandmarks) {
                for (i in 0 until numLandmarks) {
                    sums[i] = Point3D(
                        sums[i].x + frameLandmarks[i].x,
                        sums[i].y + frameLandmarks[i].y,
                        sums[i].z + frameLandmarks[i].z
                    )
                }
            }
        }

        val avgLandmarks = sums.map { sum ->
            Point3D(sum.x / numFrames, sum.y / numFrames, sum.z / numFrames)
        }
        return HandLandmarkData(avgLandmarks)
    }

    fun close() {
        handLandmarker?.close()
    }
}