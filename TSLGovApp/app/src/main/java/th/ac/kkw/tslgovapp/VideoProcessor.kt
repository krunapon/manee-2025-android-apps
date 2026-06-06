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

            val totalTemplates = signTemplates.values.sumOf { it.size }
            Log.d(
                TAG,
                "Templates loaded from cache (${signTemplates.size} words, $totalTemplates templates)"
            )

            // Debug: Print toilet, airplane, and headache template analysis
            debugPrintToiletTemplates()
            debugPrintAirplaneTemplates()
            debugPrintHeadacheTemplates()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load templates from cache: ${e.message}")
            false
        }
    }

    /**
     * Debug function to print finger states from toilet templates
     * This helps understand what the actual toilet gesture looks like
     */
    private fun debugPrintToiletTemplates() {
        val toiletTemplates = signTemplates["ห้องน้ำ"]
        if (toiletTemplates == null || toiletTemplates.isEmpty()) {
            Log.d(TAG, "🚽 No toilet templates found")
            return
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "🚽 TOILET TEMPLATES ANALYSIS (${toiletTemplates.size} templates)")
        Log.d(TAG, "========================================")

        for ((index, template) in toiletTemplates.withIndex()) {
            val landmarks = template.landmarks.landmarks
            if (landmarks.size < 21) continue

            // MediaPipe hand landmark indices:
            // 4: Thumb tip, 8: Index tip, 12: Middle tip, 16: Ring tip, 20: Pinky tip
            // 3: Thumb IP, 6: Index PIP, 10: Middle PIP, 14: Ring PIP, 18: Pinky PIP

            val thumbTip = landmarks[4]
            val indexTip = landmarks[8]
            val middleTip = landmarks[12]
            val ringTip = landmarks[16]
            val pinkyTip = landmarks[20]

            val thumbIP = landmarks[3]
            val indexPIP = landmarks[6]
            val middlePIP = landmarks[10]
            val ringPIP = landmarks[14]
            val pinkyPIP = landmarks[18]

            val wrist = landmarks[0]

            // Calculate finger extension (tip.y < pip.y means extended in screen coords)
            val thumbExtended = abs(thumbTip.x) > abs(thumbIP.x)
            val indexExtended = indexTip.y < indexPIP.y
            val middleExtended = middleTip.y < middlePIP.y
            val ringExtended = ringTip.y < ringPIP.y
            val pinkyExtended = pinkyTip.y < pinkyPIP.y

            val extendedCount = listOf(
                thumbExtended,
                indexExtended,
                middleExtended,
                ringExtended,
                pinkyExtended
            ).count { it }

            Log.d(TAG, "Template #$index:")
            Log.d(
                TAG,
                "  Wrist: X=${String.format("%.3f", wrist.x)}, Y=${String.format("%.3f", wrist.y)}"
            )
            Log.d(
                TAG,
                "  Finger states: thumb=${if (thumbExtended) 1 else 0}, index=${if (indexExtended) 1 else 0}, middle=${if (middleExtended) 1 else 0}, ring=${if (ringExtended) 1 else 0}, pinky=${if (pinkyExtended) 1 else 0} (extended=$extendedCount/5)"
            )
            Log.d(
                TAG,
                "  Tip Y positions: index=${
                    String.format(
                        "%.3f",
                        indexTip.y
                    )
                }, middle=${String.format("%.3f", middleTip.y)}, ring=${
                    String.format(
                        "%.3f",
                        ringTip.y
                    )
                }, pinky=${String.format("%.3f", pinkyTip.y)}"
            )
            Log.d(
                TAG,
                "  PIP Y positions: index=${
                    String.format(
                        "%.3f",
                        indexPIP.y
                    )
                }, middle=${String.format("%.3f", middlePIP.y)}, ring=${
                    String.format(
                        "%.3f",
                        ringPIP.y
                    )
                }, pinky=${String.format("%.3f", pinkyPIP.y)}"
            )
        }
        Log.d(TAG, "========================================")
    }

    /**
     * Debug function to print finger extension ratios from airplane templates
     * This helps understand what the actual airplane gesture looks like
     */
    private fun debugPrintAirplaneTemplates() {
        Log.d(TAG, "🔍 debugPrintAirplaneTemplates() called")
        Log.d(TAG, "🔍 Available gesture keys: ${signTemplates.keys.joinToString()}")

        val airplaneTemplates = signTemplates["เครื่องบิน"]
        if (airplaneTemplates == null) {
            Log.d(TAG, "✈️ Airplane templates key not found in signTemplates")
            Log.d(TAG, "✈️ Available keys: ${signTemplates.keys.joinToString()}")
            return
        }
        if (airplaneTemplates.isEmpty()) {
            Log.d(TAG, "✈️ Airplane templates array is empty")
            return
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "✈️ AIRPLANE TEMPLATES ANALYSIS (${airplaneTemplates.size} templates)")
        Log.d(TAG, "========================================")

        for ((index, template) in airplaneTemplates.withIndex()) {
            val landmarks = template.landmarks.landmarks
            if (landmarks.size < 21) continue

            val wrist = landmarks[0]
            val middleTip = landmarks[12]
            val middleMCP = landmarks[9]
            val ringTip = landmarks[16]
            val ringMCP = landmarks[13]

            // Calculate hand size
            val handSize = sqrt(
                (middleTip.x - wrist.x).pow(2) +
                        (middleTip.y - wrist.y).pow(2)
            )

            // Calculate extension ratios
            val middleTipMCP = sqrt(
                (middleTip.x - middleMCP.x).pow(2) +
                        (middleTip.y - middleMCP.y).pow(2)
            )
            val ringTipMCP = sqrt(
                (ringTip.x - ringMCP.x).pow(2) +
                        (ringTip.y - ringMCP.y).pow(2)
            )

            val middleExtensionRatio = middleTipMCP / handSize
            val ringExtensionRatio = ringTipMCP / handSize

            Log.d(TAG, "Template #$index:")
            Log.d(
                TAG,
                "  Wrist: X=${String.format("%.3f", wrist.x)}, Y=${String.format("%.3f", wrist.y)}"
            )
            Log.d(
                TAG,
                "  Middle extension ratio: ${
                    String.format(
                        "%.3f",
                        middleExtensionRatio
                    )
                } (${if (middleExtensionRatio < 0.20f) "CURLED" else "EXTENDED"})"
            )
            Log.d(
                TAG,
                "  Ring extension ratio: ${
                    String.format(
                        "%.3f",
                        ringExtensionRatio
                    )
                } (${if (ringExtensionRatio < 0.20f) "CURLED" else "EXTENDED"})"
            )
            Log.d(TAG, "  Hand size: ${String.format("%.3f", handSize)}")
        }
        Log.d(TAG, "========================================")
    }

    /**
     * Debug function to analyze headache templates
     * This helps understand what the actual headache gesture looks like
     */
    private fun debugPrintHeadacheTemplates() {
        val headacheTemplates = signTemplates["ปวดหัว"]
        if (headacheTemplates == null) {
            Log.d(TAG, "🤕 Headache templates key not found in signTemplates")
            return
        }
        if (headacheTemplates.isEmpty()) {
            Log.d(TAG, "🤕 Headache templates array is empty")
            return
        }

        Log.d(TAG, "========================================")
        Log.d(TAG, "🤕 HEADACHE TEMPLATES ANALYSIS (${headacheTemplates.size} templates)")
        Log.d(TAG, "========================================")

        for ((index, template) in headacheTemplates.withIndex()) {
            val landmarks = template.landmarks.landmarks
            if (landmarks.size < 21) continue

            val wrist = landmarks[0]
            val indexTip = landmarks[8]
            val middleTip = landmarks[12]
            val ringTip = landmarks[16]
            val pinkyTip = landmarks[20]
            val indexMCP = landmarks[5]
            val middleMCP = landmarks[9]
            val ringMCP = landmarks[13]
            val pinkyMCP = landmarks[17]

            // Calculate finger extensions
            val indexExtension = sqrt(
                (indexTip.x - indexMCP.x).pow(2) +
                        (indexTip.y - indexMCP.y).pow(2)
            )
            val middleExtension = sqrt(
                (middleTip.x - middleMCP.x).pow(2) +
                        (middleTip.y - middleMCP.y).pow(2)
            )
            val ringExtension = sqrt(
                (ringTip.x - ringMCP.x).pow(2) +
                        (ringTip.y - ringMCP.y).pow(2)
            )
            val pinkyExtension = sqrt(
                (pinkyTip.x - pinkyMCP.x).pow(2) +
                        (pinkyTip.y - pinkyMCP.y).pow(2)
            )

            // Calculate finger spread between middle and pinky
            val fingerSpread = kotlin.math.abs(pinkyTip.y - middleTip.y)

            // Count extended fingers (tip above MCP for upward pointing hand)
            val indexExtended = if (indexTip.y < indexMCP.y) 1 else 0
            val middleExtended = if (middleTip.y < middleMCP.y) 1 else 0
            val ringExtended = if (ringTip.y < ringMCP.y) 1 else 0
            val pinkyExtended = if (pinkyTip.y < pinkyMCP.y) 1 else 0
            val extendedCount = indexExtended + middleExtended + ringExtended + pinkyExtended

            Log.d(TAG, "Template #$index:")
            Log.d(TAG, "  Source video: ${template.sourceVideo}")
            Log.d(
                TAG,
                "  Wrist: X=${String.format("%.3f", wrist.x)}, Y=${String.format("%.3f", wrist.y)}"
            )
            Log.d(
                TAG,
                "  Tip Y: index=${String.format("%.3f", indexTip.y)}, middle=${
                    String.format(
                        "%.3f",
                        middleTip.y
                    )
                }, ring=${String.format("%.3f", ringTip.y)}, pinky=${
                    String.format(
                        "%.3f",
                        pinkyTip.y
                    )
                }"
            )
            Log.d(
                TAG,
                "  MCP Y: index=${String.format("%.3f", indexMCP.y)}, middle=${
                    String.format(
                        "%.3f",
                        middleMCP.y
                    )
                }, ring=${String.format("%.3f", ringMCP.y)}, pinky=${
                    String.format(
                        "%.3f",
                        pinkyMCP.y
                    )
                }"
            )
            Log.d(
                TAG,
                "  Extended count: $extendedCount/4 (index=$indexExtended, middle=$middleExtended, ring=$ringExtended, pinky=$pinkyExtended)"
            )
            Log.d(TAG, "  Finger spread (middle-pinky): ${String.format("%.3f", fingerSpread)}")
            Log.d(
                TAG,
                "  Extension lengths: index=${
                    String.format(
                        "%.3f",
                        indexExtension
                    )
                }, middle=${String.format("%.3f", middleExtension)}, ring=${
                    String.format(
                        "%.3f",
                        ringExtension
                    )
                }, pinky=${String.format("%.3f", pinkyExtension)}"
            )
        }
        Log.d(TAG, "========================================")
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

        val hand0Score =
            hand0Wrist.y() + (hand0Wrist.z() * 0.5f) + kotlin.math.abs(hand0Wrist.x() - 0.5f) * 0.3f
        val hand1Score =
            hand1Wrist.y() + (hand1Wrist.z() * 0.5f) + kotlin.math.abs(hand1Wrist.x() - 0.5f) * 0.3f

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
                Log.d(TAG, "📁 '$label': Temp file path = $videoPath")
                if (videoPath != null) {
                    val frames = extractKeyFramesFromVideo(videoPath)
                    Log.d(TAG, "🎬 '$label': Extracted ${frames.size} frames from video")
                    val framesLandmarks = mutableListOf<List<Point3D>>()

                    // Debug: check first frame properties
                    if (frames.isNotEmpty()) {
                        val sample = frames[0]
                        val pixel = sample.getPixel(sample.width / 2, sample.height / 2)
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        Log.d(
                            TAG, "🖼️ '$label' frame[0]: ${sample.width}x${sample.height}, " +
                                    "center pixel RGB=($r,$g,$b)"
                        )
                        // Save first frame to Downloads for visual inspection
                        try {
                            val debugFile = java.io.File(
                                android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS
                                ),
                                "debug_${label}_${System.currentTimeMillis()}.jpg"
                            )
                            java.io.FileOutputStream(debugFile).use { fos ->
                                sample.compress(
                                    android.graphics.Bitmap.CompressFormat.JPEG,
                                    90,
                                    fos
                                )
                            }
                            Log.d(TAG, "🖼️ Debug frame saved to: ${debugFile.absolutePath}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save debug frame", e)
                        }
                    }

                    for (bitmap in frames) {
                        val mpImage = BitmapImageBuilder(bitmap).build()
                        val result = handLandmarker?.detect(mpImage)

                        if (result != null && result.landmarks().isNotEmpty()) {
                            // ⭐ ตรวจสอบจำนวนมือที่ตรวจพบ
                            val detectedHands = result.landmarks().size


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
                        Log.i(TAG, "🎉 '$label': Template created successfully")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error processing video for '$label': $videoUri", e)
            }
        }
        Log.i(TAG, "📦 '$label' now has ${signTemplates[label]?.size ?: 0} templates")
    }

    private fun checkGestureCharacteristics(
        landmarks: HandLandmarkData,
        word: String
    ): Boolean {
        // Only check hand count - let template matching handle the rest
        val config = SignLanguageConfig.getWordByName(word)
        val requiredHands = config?.numHands ?: 1
        val actualHands = landmarks.landmarks.size / 21
        Log.d(
            TAG, "🔍 Checking hand count: $word required=$requiredHands, actual=$actualHands" +
                    "landmarks size = ${landmarks.landmarks.size}"
        )
        // Basic hand count check
        if (actualHands < requiredHands) {
            return false
        }
        var wristX = 0f
        var wristY = 0f
        var wrist2X = 0f
        var wrist2Y = 0f
        // Log wrist position for debugging
        if (actualHands >= 1) {
            wristX = landmarks.landmarks[0].x
            wristY = landmarks.landmarks[0].y
            Log.d(
                TAG,
                "   👋 Hand 1 wrist: X=${String.format("%.3f", wristX)}, Y=${
                    String.format(
                        "%.3f",
                        wristY
                    )
                }"
            )
        }
        if (actualHands >= 2) {
            wrist2X = landmarks.landmarks[21].x
            wrist2Y = landmarks.landmarks[21].y
            Log.d(
                TAG,
                "   👋 Hand 2 wrist: X=${String.format("%.3f", wrist2X)}, Y=${
                    String.format(
                        "%.3f",
                        wrist2Y
                    )
                }"
            )
        }

        val fingerStates = featureExtractor.detectFingerStates(landmarks)
        val indexTipY = landmarks.landmarks[8].y // Index finger tip
        val middleTipY = landmarks.landmarks[12].y // Middle finger tip
        val ringTipY = landmarks.landmarks[16].y // Ring finger tip
        val pinkyTipY = landmarks.landmarks[20].y // Pinky finger tip

        Log.d(TAG, "Finger states: $fingerStates")
        Log.d(
            TAG,
            "indexTipY=$indexTipY, middleTipY=$middleTipY, ringTipY=$ringTipY, pinkyTipY=$pinkyTipY"
        )
        // Additional checks for 2-hand signs to distinguish them
        if (actualHands >= 2 && landmarks.landmarks.size >= 42 && requiredHands >= 2) {
            val leftWristX = landmarks.landmarks[0].x   // Left hand wrist
            val rightWristX = landmarks.landmarks[21].x // Right hand wrist
            val leftWristY = landmarks.landmarks[0].y
            val rightWristY = landmarks.landmarks[21].y


            val horizontalDistance = kotlin.math.abs(leftWristX - rightWristX)
            val verticalDistance = kotlin.math.abs(leftWristY - rightWristY)


            Log.d(
                TAG, "   👐 Two-hand check for '$word': horizontalDist=${
                    String.format(
                        "%.3f",
                        horizontalDistance
                    )
                }, verticalDist=${String.format("%.3f", verticalDistance)}"
            )

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
                    val hand2FingersOpen =
                        if (fingerStates.size >= 10) fingerStates.slice(5..9).sum() else 0
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

                    Log.d(
                        TAG, "   หาย: result=$result" +
                                "chestLevel=$handsAtChestLevel, bothHandsOpen=$bothHandsOpen " +
                                "hasHorizontalSeparation=$hasHorizontalSeparation" +
                                "notVerticallyStacked=$notVerticallyStacked" +
                                "hDist=$horizontalDistance, vDist=$verticalDistance)"
                    )

                    return true


                }

                "บัตรประชาชน" -> {
                    // ID Card: hands wide apart, thumbs NOT spread (distinguishes from passport)
                    val leftThumbX = landmarks.landmarks[4].x
                    val rightThumbX = landmarks.landmarks[25].x
                    val thumbHorizontalDistance = kotlin.math.abs(leftThumbX - rightThumbX)
                    Log.d(TAG, "      บัตรประชาชน: hDist=$horizontalDistance, thumbDist=$thumbHorizontalDistance")
                    return horizontalDistance > 0.45f && thumbHorizontalDistance < 0.35f
                }

                "ช่วย" -> {
                    val handsHighEnough = leftWristY < 0.7f && rightWristY < 0.7f
                    val handsAreStacked = horizontalDistance < 0.3f
                    Log.d(
                        TAG, "handsHighEnough=$handsHighEnough" +
                                "handsAreStacked=$handsAreStacked"
                    )
                    return horizontalDistance < 0.35f
                }

                "เจ็บคอ" -> {
                    // Neck ache: hands near neck (high position) and at same level (small vertical distance)
                    val handsHighUp = leftWristY < 0.4f && rightWristY < 0.4f
                    val handsSameLevel = verticalDistance < 0.15f
                    val result = handsHighUp && handsSameLevel
                    Log.d(
                        TAG,
                        "      เจ็บคอ: result=$result (highUp=$handsHighUp,  sameLevel=$handsSameLevel, leftY=${
                            String.format(
                                "%.3f",
                                leftWristY
                            )
                        }, rightY=${
                            String.format(
                                "%.3f",
                                rightWristY
                            )
                        })"
                    )
                    return true
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
                    Log.d(
                        TAG,
                        "      หนังสือเดินทาง: result=$result (thumbsApart=$thumbsWideApart, thumbDist=${
                            String.format(
                                "%.3f",
                                thumbHorizontalDistance
                            )
                        }, sameLevel=$handsAtSameLevel)"
                    )
                    return thumbsWideApart
                }

            }
        } else if (actualHands == 1 && landmarks.landmarks.size >= 21) {
            // fingerStates: [Thumb, Index, Middle, Ring, Pinky]
            val thumb = fingerStates[0]
            val index = fingerStates[1]
            val middle = fingerStates[2]
            val ring = fingerStates[3]
            val pinky = fingerStates[4]
            Log.d(TAG, "thumb=$thumb, index=$index, middle=$middle, ring=$ring, pinky=$pinky")
            when (word) {
                "ปวดหัว" -> {
                    // TRULY SCALE-INVARIANT: Fingertip clustering analysis
                    // Headache: fingertips are tightly clustered together (all touching at forehead)
                    // Toilet: fingers are extended outward (spread apart)
                    // This works regardless of user height, hand size, or camera distance

                    if (wristY > 0.70f) {
                        Log.d(TAG, " ❌ ปวดหัว: hand too low, not near forehead (wristY=${wristY}")
                        return false
                    }

                    // Headache = curled fingers (fist); Sick = extend fingers (open palm)
                    val nonThumbExtended = index + middle + ring + pinky
                    if (nonThumbExtended >= 2) {
                        Log.d(TAG, "❌ ปวดหัว: fingers should be curled, nonThumbExtended=$nonThumbExtended")
                        return false
                    }
                    val wrist = landmarks.landmarks[0]
                    val indexTip = landmarks.landmarks[8]
                    val middleTip = landmarks.landmarks[12]
                    val ringTip = landmarks.landmarks[16]
                    val pinkyTip = landmarks.landmarks[20]

                    // Calculate hand size for normalization (distance from wrist to middle fingertip)
                    val handSize = sqrt(
                        (middleTip.x - wrist.x).pow(2) +
                                (middleTip.y - wrist.y).pow(2)
                    )

                    // 1. Calculate centroid of all 4 fingertips
                    val fingertipCentroidX =
                        (indexTip.x + middleTip.x + ringTip.x + pinkyTip.x) / 4.0
                    val fingertipCentroidY =
                        (indexTip.y + middleTip.y + ringTip.y + pinkyTip.y) / 4.0

                    // 2. Calculate distance from each fingertip to centroid
                    val indexDistToCentroid = sqrt(
                        (indexTip.x - fingertipCentroidX).pow(2) +
                                (indexTip.y - fingertipCentroidY).pow(2)
                    )
                    val middleDistToCentroid = sqrt(
                        (middleTip.x - fingertipCentroidX).pow(2) +
                                (middleTip.y - fingertipCentroidY).pow(2)
                    )
                    val ringDistToCentroid = sqrt(
                        (ringTip.x - fingertipCentroidX).pow(2) +
                                (ringTip.y - fingertipCentroidY).pow(2)
                    )
                    val pinkyDistToCentroid = sqrt(
                        (pinkyTip.x - fingertipCentroidX).pow(2) +
                                (pinkyTip.y - fingertipCentroidY).pow(2)
                    )

                    // 3. Calculate average cluster spread (normalized by hand size)
                    val avgClusterSpread =
                        (indexDistToCentroid + middleDistToCentroid + ringDistToCentroid + pinkyDistToCentroid) / 4.0
                    val clusterSpreadRatio = (avgClusterSpread / handSize).toFloat()

                    // 4. Headache: fingertips clustered tightly (small ratio)
                    //    Toilet: fingers extended outward (larger ratio)
                    Log.d(
                        TAG,
                        "   ปวดหัว: clusterSpreadRatio=$clusterSpreadRatio (handSize=$handSize, avgClusterSpread=$avgClusterSpread)"
                    )

                    Log.d(
                        TAG,
                        "   🤕 ปวดหัว: clusterSpreadRatio=$clusterSpreadRatio (threshold=0.12)"
                    )


                    if (clusterSpreadRatio > 0.50f) {
                        Log.d(TAG, "   ❌ ปวดหัว: fingertips too spread out, clusterRatio is $clusterSpreadRatio")
                        return false
                    }

                    // 5. Additional check: reject if index finger is pointing up (that's report, not headache)
                    val indexPointingUp = indexTip.y < wrist.y - 0.12f
                    if (indexPointingUp) {
                        Log.v(TAG, "   ❌ ปวดหัว: index finger pointing up, might be report")
                        return false
                    }

                    Log.d(TAG, "   ✅ ปวดหัว: fingertips tightly clustered")
                    return true
                }

                "เครื่องบิน" -> {
                    if (wristY < 0.25) {
                        return false
                    }
                    // Airplane: Pinky/index/thumb are extended as "wings", middle/ring are LESS extended
                    // KEY DISTINCTION FROM TOILET: Wing fingers (pinky, index) extend MORE than middle/ring
                    // Toilet: ALL fingers have similar extension

                    // Calculate hand size (wrist to middle fingertip distance)
                    val wrist = landmarks.landmarks[0]
                    val middleTip = landmarks.landmarks[12]
                    val indexTip = landmarks.landmarks[8]
                    val pinkyTip = landmarks.landmarks[20]
                    val handSize = sqrt(
                        (middleTip.x - wrist.x).pow(2) +
                                (middleTip.y - wrist.y).pow(2)
                    )

                    // Calculate extension ratios for all fingers
                    val middleTipMCP = sqrt(
                        (middleTip.x - landmarks.landmarks[9].x).pow(2) +
                                (middleTip.y - landmarks.landmarks[9].y).pow(2)
                    )
                    val ringTipMCP = sqrt(
                        (landmarks.landmarks[16].x - landmarks.landmarks[13].x).pow(2) +
                                (landmarks.landmarks[16].y - landmarks.landmarks[13].y).pow(2)
                    )
                    val indexTipMCP = sqrt(
                        (indexTip.x - landmarks.landmarks[5].x).pow(2) +
                                (indexTip.y - landmarks.landmarks[5].y).pow(2)
                    )
                    val pinkyTipMCP = sqrt(
                        (pinkyTip.x - landmarks.landmarks[17].x).pow(2) +
                                (pinkyTip.y - landmarks.landmarks[17].y).pow(2)
                    )

                    val middleExtensionRatio = middleTipMCP / handSize
                    val ringExtensionRatio = ringTipMCP / handSize
                    val indexExtensionRatio = indexTipMCP / handSize
                    val pinkyExtensionRatio = pinkyTipMCP / handSize

                    // Airplane: Wing fingers (pinky, index) should be MORE extended than middle/ring
                    val pinkyMoreExtendedThanMiddle =
                        pinkyExtensionRatio > middleExtensionRatio + 0.08f
                    val indexMoreExtendedThanMiddle =
                        indexExtensionRatio > middleExtensionRatio + 0.08f

                    val wingsExtended = pinkyMoreExtendedThanMiddle || indexMoreExtendedThanMiddle

                    if (!wingsExtended) {
                        Log.d(
                            TAG,
                            "   ❌ Airplane: wing fingers should be more extended than middle/ring (indexRatio=$indexExtensionRatio, middleRatio=$middleExtensionRatio, pinkyRatio=$pinkyExtensionRatio)"
                        )
                        return false
                    }

                    Log.d(
                        TAG,
                        "   ✅ Airplane: wing fingers extended more than middle/ring (indexRatio=$indexExtensionRatio, middleRatio=$middleExtensionRatio, pinkyRatio=$pinkyExtensionRatio)"
                    )
                    return true
                }

                "ห้องน้ำ" -> {
                    // Toilet: Open hand gesture at waist level
                    // KEY DISTINCTION: Toilet is made at WAIST (high Y), not at HEAD (low Y like headache)

                    val wrist = landmarks.landmarks[0]
                    val wristY = wrist.y

                    // Check 1: Hand must be low enough (waist level, not forehead level)
                    // The real toilet gesture should be at waist level (Y ≈ 0.40–0.90).
                    // Headache: wrist Y ~0.2-0.3 (high up)
                    // Toilet: wrist Y ~0.4+ (lower down)
                    val handLowEnough = wristY > 0.40f && wristY < 0.90f

                    if (!handLowEnough) {
                        Log.v(
                            TAG,
                            "ห้องน้ำ: hand too high, looks like headache gesture (wristY=$wristY)"
                        )
                        return false
                    }

                    // Check 2: Middle and ring fingers are extended (not curled like airplane)
                    // Use ratio: extension length / hand size
                    val middleTip = landmarks.landmarks[12]
                    val handSize = sqrt(
                        (middleTip.x - wrist.x).pow(2) +
                                (middleTip.y - wrist.y).pow(2)
                    )

                    val middleTipMCP = sqrt(
                        (middleTip.x - landmarks.landmarks[9].x).pow(2) +
                                (middleTip.y - landmarks.landmarks[9].y).pow(2)
                    )
                    val ringTipMCP = sqrt(
                        (landmarks.landmarks[16].x - landmarks.landmarks[13].x).pow(2) +
                                (landmarks.landmarks[16].y - landmarks.landmarks[13].y).pow(2)
                    )

                    val middleExtensionRatio = middleTipMCP / handSize
                    val ringExtensionRatio = ringTipMCP / handSize

                    // For toilet: fingers should be extended (> 35% of hand size)
                    // For airplane: middle/ring are curled (< 25% of hand size)
                    val fingersExtended = middleExtensionRatio > 0.25f && ringExtensionRatio > 0.25f
                    if (!fingersExtended) {
                        Log.v(
                            TAG,
                            "ห้องน้ำ: fingers not extended (middleRatio=$middleExtensionRatio, ringRatio=$ringExtensionRatio, handSize=$handSize)"
                        )
                        return false
                    }

                    // Reject if looks like airplane (pinky/index much more extended than middle/ring)
                    val indexTipMCP = sqrt(
                        (landmarks.landmarks[8].x - landmarks.landmarks[5].x).pow(2) +
                                (landmarks.landmarks[8].y - landmarks.landmarks[5].y).pow(2)
                    )
                    val pinkyTipMCP = sqrt(
                        (landmarks.landmarks[20].x - landmarks.landmarks[17].x).pow(2) +
                                (landmarks.landmarks[20].y - landmarks.landmarks[17].y).pow(2)
                    )
                    val indexExtensionRatio = indexTipMCP / handSize
                    val pinkyExtensionRatio = pinkyTipMCP / handSize
                    if (pinkyExtensionRatio > middleExtensionRatio + 0.08f &&
                        indexExtensionRatio > middleExtensionRatio + 0.08f) {
                        Log.d(TAG, "   ห้องน้ำ: looks like airplane (indexRatio=$indexExtensionRatio, middleRatio=$middleExtensionRatio, pinkyRatio=$pinkyExtensionRatio)")
                        return false
                    }


                    Log.v(
                        TAG,
                        " ✅ ห้องน้ำ: valid toilet gesture (wristY=$wristY, middleRatio=$middleExtensionRatio, ringRatio=$ringExtensionRatio)"
                    )
                    return true
                }

                "แจ้งความ" -> {
                    // Report: index finger should point up (key distinguishing feature from headache)
                    // Use RELATIVE measurements (not absolute Y position) to work regardless of user height

                    // Check 1: Index finger tip is significantly higher than wrist (pointing up)
                    val wristY = landmarks.landmarks[0].y
                    val indexTipY = landmarks.landmarks[8].y
                    val indexVerticalExtension = wristY - indexTipY
                    val indexPointingUp = indexVerticalExtension > 0.08f // Must be pointing up

                    // Check 2: Index finger tip is the highest (or tied for highest) among fingers
                    val middleTipY = landmarks.landmarks[12].y
                    val ringTipY = landmarks.landmarks[16].y
                    val pinkyTipY = landmarks.landmarks[20].y
                    val indexIsHighest = indexTipY <= minOf(middleTipY, ringTipY, pinkyTipY) + 0.02f

                    // Check 3: Index finger tip is significantly higher than middle finger (distinguish from open hand)
                    val indexHigherThanMiddle = middleTipY - indexTipY > 0.03f

                    val result = indexIsHighest && indexPointingUp && indexHigherThanMiddle

                    Log.d(
                        TAG, "แจ้งความ indexIsHighest=$indexIsHighest, " +
                                "indexPointingUp=$indexPointingUp, indexHigherThanMiddle=$indexHigherThanMiddle, " +
                                "wristY=$wristY, indexTipY=$indexTipY"
                    )
                    return result
                }

                "ไม่สบาย" -> {
                    // 🩺 ไม่สบาย: มือเดียว ฝ่ามือเปิด แตะที่หน้าผาก (เช็คว่ามีไข้)
                    //
                    // จุดต่างจากท่าใกล้เคียง:
                    //   - ปวดหัว: นิ้วทั้งหมดรวม "กระจุก" กันแตะหน้าผาก   → fingertip cluster แคบ
                    //   - แจ้งความ: ชี้นิ้วเดียว (index)                  → นิ้วเดียวยืด
                    //   - ไม่สบาย: ฝ่ามือ "แบ" แตะหน้าผาก                 → นิ้วยืดหลายนิ้ว และกระจาย

                    val wrist = landmarks.landmarks[0]
                    val wristY = wrist.y

                    // 1) มือต้องอยู่ระดับสูง (ใกล้หน้าผาก)
                    val handHighEnough = wristY < 0.55f
                    if (!handHighEnough) {
                        Log.d(TAG, "   ไม่สบาย: hand not high enough (wristY=$wristY)")
                        return false
                    }

                    // 2) นิ้วต้องยืดอย่างน้อย 2 นิ้ว (ฝ่ามือเปิด ไม่ใช่กำมือเหมือนปวดหัว)
                    val nonThumbExtended = index + middle + ring + pinky
                    if (nonThumbExtended < 2) {
                        Log.d(TAG, "   ไม่สบาย: only $nonThumbExtended/4 fingers extended (need ≥2)")
                        return false
                    }

                        // 3) ต้องไม่ใช่ "ปวดหัว" — เช็ค fingertip cluster ว่ากระจาย ไม่กระจุก
                    val indexTip = landmarks.landmarks[8]
                    val middleTip = landmarks.landmarks[12]
                    val ringTip = landmarks.landmarks[16]
                    val pinkyTip = landmarks.landmarks[20]
                    val handSize = sqrt(
                        (middleTip.x - wrist.x).pow(2) +
                                (middleTip.y - wrist.y).pow(2)
                    )
                    val cx = (indexTip.x + middleTip.x + ringTip.x + pinkyTip.x) / 4.0
                    val cy = (indexTip.y + middleTip.y + ringTip.y + pinkyTip.y) / 4.0
                    val avgSpread = (
                            sqrt((indexTip.x - cx).pow(2) + (indexTip.y - cy).pow(2)) +
                                    sqrt((middleTip.x - cx).pow(2) + (middleTip.y - cy).pow(2)) +
                                    sqrt((ringTip.x - cx).pow(2) + (ringTip.y - cy).pow(2)) +
                                    sqrt((pinkyTip.x - cx).pow(2) + (pinkyTip.y - cy).pow(2))
                            ) / 4.0
                    val spreadRatio = (avgSpread / handSize).toFloat()

                    // Around line 820 - after handHighEnough check
                    Log.d(TAG, "   🩺 ไม่สบาย: wristY=$wristY, handHighEnough=$handHighEnough")


                    // Around line 850 - after cluster spread check
                    Log.d(TAG, "   🩺 ไม่สบาย: spreadRatio=$spreadRatio (threshold=0.10)")

                    if (spreadRatio < 0.10f) {
                        Log.d(
                            TAG,
                            "   ไม่สบาย: fingers too clustered, looks like ปวดหัว (spreadRatio=$spreadRatio)"
                        )
                        return false
                    }

                    return true
                }
            }
        }
        Log.d(TAG, "   ❌ '$word': no matching gesture check for $actualHands hands")
        return false
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
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(videoPath)

            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLong() ?: 0

            // Get video rotation — front camera videos often have 90° or 270° metadata
            val rotationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rotation = rotationStr?.toIntOrNull() ?: 0

            val interval = if (duration < 5000) 100L else 500L
            for (time in 0 until duration step interval) {
                val bitmap = retriever.getFrameAtTime(time * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    val rotated = if (rotation != 0) {
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(rotation.toFloat())
                        android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } else {
                        bitmap
                    }
                    frames.add(rotated)
                }
            }

            retriever.release()
            Log.d(TAG, "extractKeyFrames: $videoPath rotation=$rotation, ${frames.size} frames")
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
        val actualHands = currentGestureLandmarks.landmarks.size / 21
        val numDetectedHands = minOf(numDetectedHands, actualHands)
        Log.d(TAG, "========================================")
        Log.d(TAG, "🔍 Detected hands: $numDetectedHands")


        var bestMatchLabel: String? = null
        var minDistance = Float.MAX_VALUE
        var bestConfidence: Float = 0f

        // Iterate through all signs
        for ((label, templates) in signTemplates) {
            // Check hand out compability
            val requiredHands = templates.firstOrNull()?.numHands ?: 1
            if (requiredHands != numDetectedHands) {
                Log.d(TAG, "   ❌ '$label' mismatched num hands: needs $requiredHands hands, got $numDetectedHands")
                continue
            }

            // Check gesture characteristics and match against templates
            var bestDistanceForSign = Float.MAX_VALUE

            if (requiredHands < numDetectedHands) {
                // Sign needs fewer hands than detected — only use the ACTIVE hand
                // to prevent resting hands from causing false matches (e.g. tall person's
                // resting hand at Y≈0.80 matching toilet when they're making headache gesture)
                var bestHandIdx = 0
                var bestScore = Float.MAX_VALUE
                for (handIdx in 0 until numDetectedHands) {
                    val wrist = currentGestureLandmarks.landmarks[handIdx * 21]
                    // Lower score = more active (higher position, more centered)
                    val score = wrist.y + kotlin.math.abs(wrist.x - 0.5f) * 0.3f
                    if (score < bestScore) {
                        bestScore = score
                        bestHandIdx = handIdx
                    }
                }

                val start = bestHandIdx * 21
                val end = minOf(start + 21, currentGestureLandmarks.landmarks.size)
                if (end - start < 21) continue

                val singleHandLandmarks = HandLandmarkData(
                    currentGestureLandmarks.landmarks.subList(start, end)
                )

                Log.d(TAG, "   🖐️ Single-hand sign '$label': using active hand #$bestHandIdx (wristY=${String.format("%.3f", singleHandLandmarks.landmarks[0].y)})")

                if (!checkGestureCharacteristics(singleHandLandmarks, label)) {
                    Log.d(TAG, "   ❌ '$label' skipped: active hand didn't pass characteristics check")
                    continue
                }

                val normalizedCurrent = normalizeHandLandmarks(singleHandLandmarks)
                for (template in templates) {
                    val normalizedTemplate = normalizeHandLandmarks(template.landmarks)
                    if (normalizedCurrent.landmarks.size != normalizedTemplate.landmarks.size) continue
                    val distance = calculateEuclideanDistance(normalizedCurrent, normalizedTemplate)
                    if (distance < bestDistanceForSign) {
                        bestDistanceForSign = distance
                    }
                }
            } else {
                // Normal case: exact hand count match
                if (!checkGestureCharacteristics(currentGestureLandmarks, label)) {
                    Log.d(TAG, "   ❌ '$label' skipped: gesture characteristics don't match")
                    continue
                }

                val requiredLandmarks = requiredHands * 21
                val currentToCompare = if (currentGestureLandmarks.landmarks.size > requiredLandmarks) {
                    HandLandmarkData(currentGestureLandmarks.landmarks.take(requiredLandmarks))
                } else {
                    currentGestureLandmarks
                }
                val normalizedCurrentForCompare = normalizeHandLandmarks(currentToCompare)

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
            }

            Log.d(TAG, "   '$label': best distance = ${String.format("%.4f", bestDistanceForSign)} (from ${templates.size} templates)")

            // Compare with overall best
            if (bestDistanceForSign < minDistance) {
                minDistance = bestDistanceForSign
                bestMatchLabel = label

                val maxDistance = if (requiredHands == 2) 3.5f else 3.0f
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