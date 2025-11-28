package th.ac.kkw.tslgovapp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlin.math.sqrt
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import th.ac.kkw.tslgovapp.model.HandLandmarkData
import th.ac.kkw.tslgovapp.model.Point3D
import th.ac.kkw.tslgovapp.model.RecognitionResult
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max

class VideoProcessor(private val context: Context) {

    // 🔧 แยก Template เป็นสองประเภท
    private val singleHandTemplates = mutableMapOf<String, HandLandmarkData>()
    private val doubleHandTemplates = mutableMapOf<String, HandLandmarkData>()

    companion object {
        private const val TAG = "VideoProcessor"
        private const val MODEL_FILE = "hand_landmarker.task" // ✅ Correct path
    }

    private var handLandmarker: HandLandmarker? = null
    private var isInitialized = false

    // ⭐ เปลี่ยนจากเก็บแค่ HandLandmarkData เป็นเก็บทั้ง Template และจำนวนมือ
    private data class SignTemplate(
        val landmarks: HandLandmarkData,
        val numHands: Int  // เพิ่มข้อมูลจำนวนมือที่ใช้
    )

    private val signTemplates = mutableMapOf<String, SignTemplate>()

    init {
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
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.4f)
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
        Log.d(TAG, "creating template from videos $label")
        if (handLandmarker == null) {
            Log.e(TAG, "HandLandmarker not initialized.")
            return
        }

        val allLandmarksFromAllVideos = mutableListOf<List<Point3D>>()

        for (videoUri in videoUris) {
            try {
                val videoPath = getTempFileFromUri(context, videoUri)
                if (videoPath != null) {
                    val frames = extractKeyFramesFromVideo(videoPath)

                    for (bitmap in frames) {
                        val mpImage = BitmapImageBuilder(bitmap).build()
                        val result = handLandmarker?.detect(mpImage)

                        if (result != null && result.landmarks().isNotEmpty()) {
                            // ⭐ ตรวจสอบจำนวนมือที่ตรวจพบ
                            val detectedHands = result.landmarks().size
                            Log.d(TAG, "checking template $label: $detectedHands hand(s)")

                            // Handle the case where we want 1 hand but detect 2
                            if (numHands == 1 && detectedHands == 2) {
                                Log.d(TAG, "   Detected 2 hands but need 1 - finding active hand")
                                val activeHandIndex = findActiveHand(result.landmarks())
                                val activeHand = result.landmarks()[activeHandIndex]

                                val allHandsLandmarks = mutableListOf<Point3D>()
                                activeHand.forEach { lm ->
                                    allHandsLandmarks.add(Point3D(lm.x(), lm.y(), lm.z()))
                                }
                                allLandmarksFromAllVideos.add(allHandsLandmarks)
                                Log.d(TAG, "   ✅ Used hand $activeHandIndex as active signing hand")

                            } else if (detectedHands == numHands) {
                                // เก็บเฉพาะเฟรมที่มีจำนวนมือตรงกับที่กำหนด
                                val allHandsLandmarks = mutableListOf<Point3D>()

                                for (hand in result.landmarks()) {
                                    hand.forEach { lm ->
                                        allHandsLandmarks.add(
                                            Point3D(lm.x(), lm.y(), lm.z())
                                        )
                                    }
                                }

                                allLandmarksFromAllVideos.add(allHandsLandmarks)
                            } else {
                                Log.w(TAG, "Skipped frame: expected $numHands hands, got $detectedHands")
                            }
                        }
                    }
                    Log.d(TAG, "Processed video: $videoUri")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing video: $videoUri", e)
            }
        }

        if (allLandmarksFromAllVideos.isNotEmpty()) {
            val averageLandmarks = calculateAverageLandmarks(allLandmarksFromAllVideos)

            // ⭐ บันทึก Template พร้อมกับจำนวนมือ
            signTemplates[label] = SignTemplate(
                landmarks = averageLandmarks,
                numHands = numHands
            )

            Log.i(TAG, "✅ checking template '$label' created: $numHands hand(s), ${averageLandmarks.landmarks.size} landmarks")
        } else {
            Log.w(TAG, "❌ Could not create template for '$label': No valid frames")
        }
    }

    private fun checkGestureCharacteristics(
        landmarks: HandLandmarkData,
        word: String
    ): Boolean {
        // 1. เช็คพื้นฐาน: ถ้าจุดไม่ครบ 1 มือ (21 จุด) ให้ดีดออกทันที
        if (landmarks.landmarks.size < 21) return false

        val wrist = landmarks.landmarks[0]
        val thumbTip = landmarks.landmarks[4]
        val indexTip = landmarks.landmarks[8]
        val indexPip = landmarks.landmarks[6]
        val middleTip = landmarks.landmarks[12]
        val middlePip = landmarks.landmarks[10]
        val ringTip = landmarks.landmarks[16]
        val ringPip = landmarks.landmarks[14]
        val pinkyTip = landmarks.landmarks[20]
        val pinkyPip = landmarks.landmarks[18]

        when (word) {
            "ช่วย" -> {
                // ⭐ สำหรับท่าทาง 2 มือ ต้องมี landmarks 42 จุด (21 x 2)
                if (landmarks.landmarks.size < 42) {
                    Log.d(TAG, "      ❌ ช่วย: ต้องใช้ 2 มือ (found ${landmarks.landmarks.size/21} hands)")
                    return false
                }

                val leftIndexTip = landmarks.landmarks[8]
                val rightIndexTip = landmarks.landmarks[29]

                // เงื่อนไข 1: มือทั้งสองอยู่ใกล้กัน (มาชนกัน)
                /*val handsClose = kotlin.math.abs(leftIndexTip.x - rightIndexTip.x) < 0.15f &&
                        kotlin.math.abs(leftIndexTip.y - rightIndexTip.y) < 0.15f


                val result = handsClose

                Log.d(TAG, "      🆘 ช่วย check:")
                Log.d(TAG, "         handsClose=$handsClose (distance=${String.format("%.3f", kotlin.math.abs(leftIndexTip.x - rightIndexTip.x))})")
                Log.d(TAG, "         RESULT=$result")

                return result*/
                return true
            }

            "เครื่องบิน" -> {

               /* // ✅ เงื่อนไข 3: นิ้วกลางและนิ้วนางพับลง
                val middleFolded = middleTip.y > middlePip.y - 0.03f
                val ringFolded = ringTip.y > ringPip.y - 0.03f

                // ✅ เงื่อนไข 4: นิ้วชี้และนิ้วก้อยยื่นออก
                val indexExtended = indexTip.y < indexPip.y

                val result = middleFolded &&
                        ringFolded

                Log.d(TAG, "      ✈️ เครื่องบิน check:")
                Log.d(TAG, "         middleFolded=$middleFolded, ringFolded=$ringFolded")
                Log.d(TAG, "         RESULT=$result")

                return result */
                return true
            }

            "แจ้งความ" -> {
                // ตรวจสอบการพับนิ้ว
                /*val middleNotExtended = kotlin.math.abs(middleTip.y - wrist.y) <
                kotlin.math.abs(middlePip.y - wrist.y) + 0.05f
                val ringNotExtended = kotlin.math.abs(ringTip.y - wrist.y) <
                kotlin.math.abs(ringPip.y - wrist.y) + 0.05f

                // ตรวจสอบว่ามือยกขึ้น แต่ไม่ใกล้หน้า
                val handRaised = wrist.y < 0.45f

                // ตรวจสอบว่านิ้วชี้และนิ้วหัวแม่มือยื่นออก
                val thumbExtended = kotlin.math.abs(thumbTip.x - wrist.x) > 0.1f


                val result = middleNotExtended && ringNotExtended && handRaised && thumbExtended

                Log.d(TAG, "      📝 แจ้งความ: middle=$middleNotExtended, ring=$ringNotExtended, " +
                        "raised=$handRaised, thumb=$thumbExtended, " +
                        "→ $result")
                return result */
                return true
            }

            "ปวดหัว" -> {
                // ✅ เงื่อนไข 1: มือยกสูง (ใกล้หน้า)
                /*val handRaised = wrist.y < 0.35f  // เข้มงวดขึ้น

                // ✅ เงื่อนไข 2: มือใกล้หน้า (แกน Z)
                val handNearFace = kotlin.math.abs(indexTip.z - wrist.z) < 0.18f

                // ✅ เงื่อนไข 3: มือไม่เหยียดไปข้างหน้า
                val notForward = kotlin.math.abs(indexTip.z - wrist.z) < 0.2f

                // ✅ เงื่อนไข 4: นิ้วไม่ยื่นออกแบบเครื่องบิน (อย่างน้อย 2 นิ้วพับ)
                val fingersFolded = (middleTip.y > middlePip.y - 0.03f) ||
                        (ringTip.y > ringPip.y - 0.03f)

                val result = handRaised && handNearFace && notForward && fingersFolded

                Log.d(TAG, "      🤕 ปวดหัว check:")
                Log.d(TAG, "         raised=$handRaised (y=${String.format("%.3f", wrist.y)})")
                Log.d(TAG, "         nearFace=$handNearFace")
                Log.d(TAG, "         notForward=$notForward")
                Log.d(TAG, "         fingersFolded=$fingersFolded")
                Log.d(TAG, "         RESULT=$result")

                return result */
                return true

            }

            "เจ็บคอ" -> {
                // ต้องมี 42 landmarks (2 มือ)
                if (landmarks.landmarks.size < 42) {
                    Log.d(TAG, "      ❌ เจ็บคอ: ต้องใช้ 2 มือ")
                    return false
                }

                /*val leftWrist = landmarks.landmarks[0]
                val rightWrist = landmarks.landmarks[21]
                val leftIndexTip = landmarks.landmarks[8]
                val rightIndexTip = landmarks.landmarks[29]

                // เงื่อนไข: มือทั้งสองอยู่บริเวณคอ (y สูง, ใกล้กัน)
                val handsNearNeck = leftWrist.y < 0.4f && rightWrist.y < 0.4f
                val handsClose = kotlin.math.abs(leftIndexTip.x - rightIndexTip.x) < 0.3f

                val result = handsNearNeck && handsClose

                Log.d(TAG, "      🤕 เจ็บคอ check: nearNeck=$handsNearNeck, close=$handsClose → $result")
                return result */
                return true
            }

            "หาย" -> {
                // ต้องมี 42 landmarks (2 มือ)
                if (landmarks.landmarks.size < 42) {
                    Log.d(TAG, "      ❌ หาย: ต้องใช้ 2 มือ")
                    return false
                }

                val leftWrist = landmarks.landmarks[0]
                val rightWrist = landmarks.landmarks[21]

                // เงื่อนไข: มือแยกออกจากกัน (คล้ายท่าทาง "หาย")
                /*val handsSeparated = kotlin.math.abs(leftWrist.x - rightWrist.x) > 0.3f
                val bothHandsRaised = leftWrist.y < 0.6f && rightWrist.y < 0.6f

                val result = handsSeparated && bothHandsRaised

                Log.d(TAG, "      🔍 หาย check: separated=$handsSeparated, raised=$bothHandsRaised → $result")
                return result */
                return true
            }

            "บัตรประชาชน" -> {
                // ต้องมี 42 landmarks (2 มือ)
                if (landmarks.landmarks.size < 42) {
                    Log.d(TAG, "      ❌ บัตรประชาชน: ต้องใช้ 2 มือ")
                    return false
                }

                val leftThumb = landmarks.landmarks[4]
                val leftIndex = landmarks.landmarks[8]
                val rightThumb = landmarks.landmarks[25]
                val rightIndex = landmarks.landmarks[29]

                // เงื่อนไข: มือทั้งสองทำท่าทางถือบัตร (นิ้วชี้และหัวแม่มือใกล้กัน)
               /* val leftPinch = kotlin.math.abs(leftThumb.x - leftIndex.x) < 0.1f
                val rightPinch = kotlin.math.abs(rightThumb.x - rightIndex.x) < 0.1f
                val handsParallel = kotlin.math.abs(leftThumb.y - rightThumb.y) < 0.15f

                val result = leftPinch && rightPinch && handsParallel

                Log.d(TAG, "      🪪 บัตรประชาชน check: leftPinch=$leftPinch, rightPinch=$rightPinch → $result")
                return result */
                return true
            }

            "หนังสือเดินทาง" -> {
                // ต้องมี 42 landmarks (2 มือ)
                if (landmarks.landmarks.size < 42) {
                    Log.d(TAG, "      ❌ หนังสือเดินทาง: ต้องใช้ 2 มือ")
                    return false
                }

                // คล้าย "บัตรประชาชน" แต่มือแยกห่างมากกว่า
                val leftWrist = landmarks.landmarks[0]
                val rightWrist = landmarks.landmarks[21]

                val widerSpacing = kotlin.math.abs(leftWrist.x - rightWrist.x) > 0.25f
                val bothHandsCenter = leftWrist.y > 0.3f && rightWrist.y > 0.3f

                val result = widerSpacing && bothHandsCenter

                Log.d(TAG, "      📘 หนังสือเดินทาง check: wider=$widerSpacing, center=$bothHandsCenter → $result")
                // return result
                return true
            }

            "ห้องน้ำ" -> {
                if (landmarks.landmarks.size < 21) return false

                val wrist = landmarks.landmarks[0]
                val indexTip = landmarks.landmarks[8]
                val indexPip = landmarks.landmarks[6]
                val middleTip = landmarks.landmarks[12]
                val middlePip = landmarks.landmarks[10]

                // ✅ เงื่อนไข 1: นิ้วชี้ยื่นออกมากกว่านิ้วอื่น (ไม่จำเป็นต้องยื่นขึ้น)
                val indexMoreExtended = kotlin.math.abs(indexTip.y - wrist.y) >
                        kotlin.math.abs(middleTip.y - wrist.y) + 0.03f

                // ✅ เงื่อนไข 2: มืออยู่ใกล้เอว (ไม่ใกล้หน้า)
                val handNearWaist = wrist.y > 0.35f && wrist.y < 0.7f

                // ✅ เงื่อนไข 3: มือไม่หันไปด้านข้าง (ค่อนข้างตรง)
                val handCentered = kotlin.math.abs(wrist.x - 0.5f) < 0.3f

                val result = handCentered

                Log.d(TAG, "      🚻 ห้องน้ำ check:")
                Log.d(TAG, "         indexMoreExtended=$indexMoreExtended")
                Log.d(TAG, "         handNearWaist=$handNearWaist (y=${String.format("%.3f", wrist.y)})")
                Log.d(TAG, "         handCentered=$handCentered (x=${String.format("%.3f", wrist.x)})")
                Log.d(TAG, "         RESULT=$result")

                // return result
                return true
            }

            else -> return true
        }
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

        // กรองเฉพาะ Template ที่มีจำนวนมือตรงกัน
        // Debug 1: แสดง template ทั้งหมดที่มี
        Log.d(TAG, "========================================")
        Log.d(TAG, "🔍 ALL TEMPLATES: ${signTemplates.keys.joinToString(", ")}")
        Log.d(TAG, "🔍 Detected hands: $numDetectedHands")
        Log.d(TAG, "🔍 Current landmarks size: ${currentGestureLandmarks.landmarks.size}")

        if (signTemplates.isEmpty()) {
            Log.w(TAG, "❌ No templates loaded")
            return null
        }

        // Debug 2: ตรวจสอบ numHands แต่ละ template
        signTemplates.forEach { (label, template) ->
            val handsMatch = template.numHands <= numDetectedHands
            Log.d(TAG, "   Template '$label': needs ${template.numHands} hands, match=$handsMatch")
        }

        // Debug 3: ตรวจสอบ gesture characteristics
        signTemplates.keys.forEach { label ->
            val gesturePass = checkGestureCharacteristics(currentGestureLandmarks, label)
            Log.d(TAG, "   Gesture check '$label': pass=$gesturePass")
        }

        // กรอง candidates
        val candidateTemplates = signTemplates.filter { (label, template) ->
            val handsMatch = template.numHands <= numDetectedHands
            val gesturePass = checkGestureCharacteristics(currentGestureLandmarks, label)

            // Debug 4: แสดงเหตุผลที่ถูกกรอง
            if (!handsMatch) {
                Log.d(TAG, "   ❌ '$label' filtered: needs ${template.numHands} hands, got $numDetectedHands")
            }
            if (!gesturePass) {
                Log.d(TAG, "   ❌ '$label' filtered: gesture check failed")
            }
            if (handsMatch && gesturePass) {
                Log.d(TAG, "   ✅ '$label' passed filters")
            }

            handsMatch && gesturePass
        }

        // Debug 5: แสดง candidates หลังกรอง
        Log.d(TAG, "🎯 CANDIDATES: ${candidateTemplates.keys.joinToString(", ")}")

        if (candidateTemplates.isEmpty()) {
            Log.w(TAG, "❌ No candidates after filtering")
            return null
        }


        Log.d(TAG, "🔍 Comparing against ${candidateTemplates.size} templates ($numDetectedHands hand)")

        // Normalize input ก่อนเปรียบเทียบ
        val normalizedCurrent = normalizeHandLandmarks(currentGestureLandmarks)

        var bestMatchLabel: String? = null
        var minDistance = Float.MAX_VALUE
        var bestConfidence: Float = 0f

        for ((label, template) in candidateTemplates) {
            // ⭐ FIX: ตัด current landmarks ให้ตรงกับจำนวนมือของ template
            val requiredLandmarks = template.numHands * 21
            val currentLandmarksToCompare = if (currentGestureLandmarks.landmarks.size > requiredLandmarks) {
                HandLandmarkData(currentGestureLandmarks.landmarks.take(requiredLandmarks))
            } else {
                currentGestureLandmarks
            }

            Log.d(TAG, "📏 Comparing '$label':")
            Log.d(TAG, "   Template: ${template.landmarks.landmarks.size} landmarks (${template.numHands} hands)")
            Log.d(TAG, "   Current (trimmed): ${currentLandmarksToCompare.landmarks.size} landmarks")

            // ตรวจสอบขนาดก่อนเปรียบเทียบ
            if (currentLandmarksToCompare.landmarks.size != template.landmarks.landmarks.size) {
                Log.w(TAG, "   ⚠️ Size mismatch! Skipping '$label'")
                continue
            }

            // Normalize template ด้วย
            val normalizedCurrentForCompare = normalizeHandLandmarks(currentLandmarksToCompare)
            val normalizedTemplate = normalizeHandLandmarks(template.landmarks)

            Log.d(TAG, "📏 Comparing '$label': current=${normalizedCurrentForCompare.landmarks.size}, template=${normalizedTemplate.landmarks.size}")

            val distance = calculateEuclideanDistance(
                normalizedCurrentForCompare,
                normalizedTemplate
            )

            // สองมือมีความแปรปรวนสูงกว่า จึงใช้ threshold สูงกว่า
            val maxDistance = if (template.numHands == 2) 2.5f else 2.0f

            val confidence = max(0.0f, (1.0f - distance / maxDistance) * 100)
            Log.v(TAG, "  checking template $label: dist=${String.format("%.4f", distance)}, conf=${String.format("%.1f%%", confidence)}")

            if (distance < minDistance) {
                minDistance = distance
                bestMatchLabel = label
                bestConfidence = confidence
            }
            Log.v(TAG, "checking template ${label},  best: ${bestMatchLabel}  minDistance=${String.format("%.1f%%", minDistance)}")
        }

        if (bestMatchLabel != null) {
            // ✅ ลด threshold สำหรับมือเดียว
            val minConfidenceThreshold = if (signTemplates[bestMatchLabel]?.numHands == 2) {
                25f  // สองมือ threshold ต่ำกว่า
            } else {
                30f  // ✅ เดิม 60f → เปลี่ยนเป็น 40f
            }

            Log.d(
                TAG,
                "🎯 Checking confidence: ${
                    String.format(
                        "%.1f%%",
                        bestConfidence
                    )
                } vs threshold $minConfidenceThreshold%"
            )

            if (bestConfidence >= minConfidenceThreshold) {
                Log.d(TAG, "✅ Best: $bestMatchLabel (${String.format("%.1f%%", bestConfidence)})")
                return RecognitionResult(
                    word = bestMatchLabel,
                    confidence = bestConfidence,
                    distance = minDistance
                )
            } else {
                Log.d(
                    TAG,
                    "❌ Below threshold: ${
                        String.format(
                            "%.1f%%",
                            bestConfidence
                        )
                    } < ${minConfidenceThreshold}%"
                )
                return null
            }
        } else {
            Log.d(
                TAG,
                "❌ BestMatchLabel is null"
            )
            return null
        }
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