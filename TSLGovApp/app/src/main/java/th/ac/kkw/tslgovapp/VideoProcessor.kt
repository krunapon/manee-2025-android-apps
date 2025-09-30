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
                            if (detectedHands == numHands) {
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
        if (landmarks.landmarks.size < 21) return true

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

                // มือซ้าย (0-20) และมือขวา (21-41)
                val leftWrist = landmarks.landmarks[0]
                val rightWrist = landmarks.landmarks[21]

                val leftIndexTip = landmarks.landmarks[8]
                val rightIndexTip = landmarks.landmarks[29]

                // เงื่อนไข 1: มือทั้งสองอยู่ใกล้กัน (มาชนกัน)
                val handsClose = kotlin.math.abs(leftIndexTip.x - rightIndexTip.x) < 0.15f &&
                        kotlin.math.abs(leftIndexTip.y - rightIndexTip.y) < 0.15f

                // เงื่อนไข 2: มือทั้งสองอยู่ตรงกลางลำตัว (ไม่เอียงไปข้างใดข้างหนึ่ง)
                val handsInCenter = leftWrist.x > 0.3f && leftWrist.x < 0.7f &&
                        rightWrist.x > 0.3f && rightWrist.x < 0.7f

                // เงื่อนไข 3: มือทั้งสองยกขึ้นในระดับอก (ไม่ต่ำเกินไป)
                val handsRaised = leftWrist.y < 0.6f && rightWrist.y < 0.6f

                // เงื่อนไข 4: นิ้วชี้ทั้งสองยื่นออกมา (ไม่พับ)
                val leftIndexExtended = leftIndexTip.y < landmarks.landmarks[6].y // PIP joint
                val rightIndexExtended = rightIndexTip.y < landmarks.landmarks[27].y

                val result = handsClose && handsInCenter && handsRaised &&
                        leftIndexExtended && rightIndexExtended

                Log.d(TAG, "      🆘 ช่วย check:")
                Log.d(TAG, "         handsClose=$handsClose (distance=${String.format("%.3f", kotlin.math.abs(leftIndexTip.x - rightIndexTip.x))})")
                Log.d(TAG, "         handsInCenter=$handsInCenter")
                Log.d(TAG, "         handsRaised=$handsRaised")
                Log.d(TAG, "         leftIndexExtended=$leftIndexExtended")
                Log.d(TAG, "         rightIndexExtended=$rightIndexExtended")
                Log.d(TAG, "         RESULT=$result")

                return result
            }

            "เครื่องบิน" -> {

                // เงื่อนไข 3: นิ้วกลางพับลง
                val middleFolded = kotlin.math.abs(middleTip.x - wrist.x) <
                kotlin.math.abs(middlePip.x - wrist.x) + 0.05f

                // เงื่อนไข 4: นิ้วนางพับลง
                val ringFolded = kotlin.math.abs(ringTip.x - wrist.x) <
                kotlin.math.abs(ringPip.x - wrist.x) + 0.05f


                val result = middleFolded && ringFolded

                Log.d(TAG, "      เครื่องบิน check:")
                Log.d(TAG, "         middleFolded=$middleFolded")
                Log.d(TAG, "         ringFolded=$ringFolded")
                Log.d(TAG, "         RESULT=$result")

                return result
            }

            "แจ้งความ" -> {
                // เงื่อนไข 2: นิ้วกลางพับลง (ไม่ยื่น)
                val middleNotExtended = kotlin.math.abs(middleTip.x - wrist.x) <
                kotlin.math.abs(middlePip.x - wrist.x) + 0.05f

                // เงื่อนไข 3: นิ้วนางพับลง
                val ringNotExtended = kotlin.math.abs(ringTip.x - wrist.x) <
                kotlin.math.abs(ringPip.x - wrist.x) + 0.05f

                // เงื่อนไข 4: นิ้วก้อยพับลง (แยกจาก "เครื่องบิน")
                val pinkyNotExtended = kotlin.math.abs(pinkyTip.x - wrist.x) <
                kotlin.math.abs(pinkyPip.x - wrist.x) + 0.05f

                // เงื่อนไข 5: มือยกสูง (wrist.y น้อย)
                val handRaised = wrist.y < 0.4f

                val result =  middleNotExtended &&
                        ringNotExtended && pinkyNotExtended && handRaised

                Log.d(TAG, "      📝 แจ้งความ check:")
                Log.d(TAG, "         middleNotExtended=$middleNotExtended")
                Log.d(TAG, "         ringNotExtended=$ringNotExtended")
                Log.d(TAG, "         pinkyNotExtended=$pinkyNotExtended")
                Log.d(TAG, "         handRaised=$handRaised (wrist.y=${String.format("%.3f", wrist.y)})")
                Log.d(TAG, "         RESULT=$result")

                return result
            }

            "ปวดหัว" -> {
                // เงื่อนไข 1: มือยกสูง (ใกล้หัว)
                val handNearHead = wrist.y < 0.3f

                // เงื่อนไข 4: มือไม่แยกห่างจากหน้า (ระยะ z ใกล้)
                val handCloseToFace = kotlin.math.abs(indexTip.z - wrist.z) < 0.15f

                val result = handNearHead && handCloseToFace

                Log.d(TAG, "      🤕 ปวดหัว check:")
                Log.d(TAG, "         handNearHead=$handNearHead (wrist.y=${String.format("%.3f", wrist.y)})")
                Log.d(TAG, "         handCloseToFace=$handCloseToFace")
                Log.d(TAG, "         RESULT=$result")

                return result
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

        if (signTemplates.isEmpty()) {
            Log.w(TAG, "❌ No templates loaded")
            return null
        }

        // Debug 2: ตรวจสอบ numHands แต่ละ template
        signTemplates.forEach { (label, template) ->
            val handsMatch = template.numHands == numDetectedHands
            Log.d(TAG, "   Template '$label': needs ${template.numHands} hands, match=$handsMatch")
        }

        // Debug 3: ตรวจสอบ gesture characteristics
        signTemplates.keys.forEach { label ->
            val gesturePass = checkGestureCharacteristics(currentGestureLandmarks, label)
            Log.d(TAG, "   Gesture check '$label': pass=$gesturePass")
        }

        // กรอง candidates
        val candidateTemplates = signTemplates.filter { (label, template) ->
            val handsMatch = template.numHands == numDetectedHands
            val gesturePass = checkGestureCharacteristics(currentGestureLandmarks, label)

            // Debug 4: แสดงเหตุผลที่ถูกกรอง
            if (!handsMatch) {
                Log.d(TAG, "   ❌ '$label' filtered: numHands mismatch")
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
        var confidence: Float = 0f

        for ((label, template) in candidateTemplates) {
            // Normalize template ด้วย
            val normalizedTemplate = normalizeHandLandmarks(template.landmarks)

            val distance = calculateEuclideanDistance(
                normalizedCurrent,
                normalizedTemplate
            )

            // FIX 3: ใช้ Exponential Decay สำหรับ Confidence


            val maxDistance = 2.0f  // ระยะทางสูงสุดที่ยอมรับได้

            confidence = max(0.0f, (1.0f - distance / maxDistance) * 100)
            Log.v(TAG, "  checking template $label: dist=${String.format("%.4f", distance)}, conf=${String.format("%.1f%%", confidence)}")

            if (distance < minDistance) {
                minDistance = distance
                bestMatchLabel = label
            }
            Log.v(TAG, "checking template ${label},  best: ${bestMatchLabel}  minDistance=${String.format("%.1f%%", minDistance)}")
        }

        if (bestMatchLabel != null) {
            // ใช้ exponential decay แทน linear
            //val confidence = kotlin.math.exp(-minDistance * 5.0f)

            Log.d(TAG, "✅ Best: $bestMatchLabel (${String.format("%.1f%%", confidence)})")

            return RecognitionResult(
                word = bestMatchLabel,
                confidence = confidence,
                distance = minDistance
            )
        }

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
            sumOfSquaredDistances += dx * dx + dy * dy + dz * dz
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