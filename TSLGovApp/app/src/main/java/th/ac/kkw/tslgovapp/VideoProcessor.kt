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

class VideoProcessor(private val context: Context) {

    private val signTemplates = mutableMapOf<String, HandLandmarkData>()

    companion object {
        private const val TAG = "VideoProcessor"
        private const val MODEL_FILE = "hand_landmarker.task" // ✅ Correct path
    }

    private var handLandmarker: HandLandmarker? = null
    private var isInitialized = false

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
                .setMinTrackingConfidence(0.5f)
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

    fun createTemplateFromVideos(label: String, videoUris: List<Uri>) {
        if (handLandmarker == null) {
            Log.e("VideoProcessor", "HandLandmarker not initialized.")
            return
        }

        val allLandmarksFromAllVideos = mutableListOf<List<Point3D>>()

        for (videoUri in videoUris) {
            try {
                // Convert Uri to temporary file path
                val videoPath = getTempFileFromUri(context, videoUri)
                if (videoPath != null) {
                    // Extract frames from video
                    val frames = extractKeyFramesFromVideo(videoPath)

                    // Process each frame
                    for (bitmap in frames) {
                        val mpImage = BitmapImageBuilder(bitmap).build()
                        val result = handLandmarker?.detect(mpImage)

                        if (result != null && result.landmarks().isNotEmpty()) {
                            val landmarks = result.landmarks().first().map { lm ->
                                Point3D(lm.x().toFloat(), lm.y().toFloat(), lm.z().toFloat())
                            }
                            allLandmarksFromAllVideos.add(landmarks)
                        }
                    }
                    Log.d("VideoProcessor", "Processed video: $videoUri")
                }
            } catch (e: Exception) {
                Log.e("VideoProcessor", "Error processing video file: $videoUri", e)
            }
        }

        if (allLandmarksFromAllVideos.isNotEmpty()) {
            val averageLandmarks = calculateAverageLandmarks(allLandmarksFromAllVideos)
            signTemplates[label] = averageLandmarks
            Log.i("VideoProcessor", "Template for '$label' created successfully from ${videoUris.size} videos.")
        } else {
            Log.w("VideoProcessor", "Could not create template for '$label'. No landmarks detected.")
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

    // ========== โค้ดที่เพิ่มเข้ามาใหม่ ==========

    /**
     * จดจำท่าทางโดยเปรียบเทียบ Landmark ปัจจุบันกับ Template ทั้งหมดที่มี
     * @return ชื่อคำศัพท์ที่ตรงที่สุด หรือ null ถ้าไม่ตรงกับคำใดเลย
     */
    fun recognizeSign(currentGestureLandmarks: HandLandmarkData): RecognitionResult? {
        if (signTemplates.isEmpty()) {
            return null
        }

        var bestMatchLabel: String? = null
        var minDistance = Float.MAX_VALUE

        for ((label, template) in signTemplates) {
            val distance = calculateEuclideanDistance(currentGestureLandmarks, template)
            if (distance < minDistance) {
                minDistance = distance
                bestMatchLabel = label
            }
        }

        // ถ้าเจอคำที่ใกล้เคียงที่สุด
        if (bestMatchLabel != null) {
            // แปลงค่า distance เป็น confidence (ค่าความเหมือน)
            val confidence = (1.0f - minDistance.coerceAtMost(1.0f))

            Log.d("VideoProcessor", "Match found: $bestMatchLabel, Distance: $minDistance, Confidence: ${confidence * 100}%")

            // สร้างและคืนค่า Object RecognitionResult ตามที่คุณออกแบบไว้
            return RecognitionResult(
                word = bestMatchLabel,
                confidence = confidence,
                distance = minDistance
            )
        }

        return null // ถ้าไม่เจอคำที่ตรงกันเลย
    }

    // In VideoProcessor.kt

    /**
     * คำนวณ Euclidean Distance เฉลี่ยต่อ Landmark (RMSE) ระหว่าง Landmark สองชุด
     */
    private fun calculateEuclideanDistance(current: HandLandmarkData, template: HandLandmarkData): Float {
        if (current.landmarks.size != template.landmarks.size) return Float.MAX_VALUE

        val numLandmarks = current.landmarks.size
        if (numLandmarks == 0) return Float.MAX_VALUE // ป้องกันการหารด้วยศูนย์

        var sumOfSquaredDistances = 0.0f
        for (i in current.landmarks.indices) {
            val dx = current.landmarks[i].x - template.landmarks[i].x
            val dy = current.landmarks[i].y - template.landmarks[i].y
            val dz = current.landmarks[i].z - template.landmarks[i].z
            sumOfSquaredDistances += dx * dx + dy * dy + dz * dz
        }

        // *** นี่คือส่วนที่แก้ไข ***
        // เราจะหาค่าเฉลี่ยก่อนถอดสแควร์รูท
        val meanSquaredError = sumOfSquaredDistances / numLandmarks
        return sqrt(meanSquaredError) // คืนค่าเป็น Root Mean Square Error
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