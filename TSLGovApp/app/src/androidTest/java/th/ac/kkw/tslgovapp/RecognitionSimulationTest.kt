package th.ac.kkw.tslgovapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Offline simulation of the live-camera recognition pipeline against the
 * bundled per-word demo videos (no physical camera / hand needed).
 *
 * Loads templates exactly like CameraActivitiy.loadTemplatesFromVideos(), then
 * for each word replays that word's own bundled videos frame-by-frame through
 * the REAL SignLanguageAnalyzer.processResults() (via reflection, since it's
 * private) so every production gate — VideoProcessor.checkGestureCharacteristics,
 * validateWithAngleFeatures, hand-count tracking, consecutive-detection — runs
 * exactly as it would from a live camera feed.
 *
 * Caveat: this is a self-consistency check, not held-out validation — the same
 * videos are used both to build the templates and to drive the simulated
 * "camera" frames, because that's what CameraActivitiy.kt itself does (main +
 * test videos are all merged into templates, none are held out).
 */
@RunWith(AndroidJUnit4::class)
class RecognitionSimulationTest {

    private val TAG = "SIM"

    private data class WordSpec(val word: String, val files: List<String>, val numHands: Int)

    // Mirrors CameraActivitiy.kt loadTemplatesFromVideos() exactly (file lists + numHands).
    private val words = listOf(
        WordSpec("เจ็บคอ", listOf("neck_ache_main", "neck_ache_test1", "neck_ache_test2", "neck_ache_test3", "neck_ache_test4"), 2),
        WordSpec("ปวดหัว", listOf("head_ache_main", "head_ache_test1"), 1),
        WordSpec("ช่วย", listOf("help_main", "help_test1", "help_test2", "help_test3", "help_test4"), 2),
        WordSpec("ไม่สบาย", listOf("sick_main", "sick_test1", "sick_test2"), 1),
        WordSpec("ปวดท้อง", listOf("stomachache_main", "stomachache_test1", "stomachache_test2"), 1),
        WordSpec("หาย", listOf("lost_main", "lost_test1", "lost_test2"), 2),
        WordSpec("บัตรประชาชน", listOf("id_card_main", "id_card_test1", "id_card_test2", "id_card_test3", "id_card_test4"), 2),
        WordSpec("แจ้งความ", listOf("report_main", "report_test1", "report_test2", "report_test3", "report_test4"), 1),
        WordSpec("หนังสือเดินทาง", listOf("passport_main", "passport_test1", "passport_test2", "passport_test3", "passport_test4"), 2),
        WordSpec("เครื่องบิน", listOf("airplane_main", "airplane_test1", "airplane_test2", "airplane_test3", "airplane_test4"), 1),
        WordSpec("ห้องน้ำ", listOf("toilet_main", "toilet_test1", "toilet_test2", "toilet_test3", "toilet_test4"), 1),
    )

    private var currentTargetWord: String = ""

    @Test
    fun simulateRecognitionForAllWords() {
        // Logger.saveLogcatFor (called from VideoProcessor/SignLanguageAnalyzer init) can
        // Toast on its error path, which needs a Looper on this thread.
        if (Looper.myLooper() == null) Looper.prepare()

        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val videoProcessor = VideoProcessor(context)

        Log.i(TAG, "=== Loading templates (mirrors CameraActivitiy.loadTemplatesFromVideos) ===")
        for (w in words) {
            val uris = w.files.map { name -> resourceUri(context, name) }
            videoProcessor.createTemplateFromVideos(w.word, uris, w.numHands)
        }

        val announced = mutableMapOf<String, MutableList<String>>()
        val analyzer = SignLanguageAnalyzer(context, videoProcessor) { result ->
            announced.getOrPut(currentTargetWord) { mutableListOf() }.add(result)
            Log.i(TAG, "onResult while simulating '$currentTargetWord': announced='$result'")
        }

        // Separate VIDEO-mode landmarker (own instance) just to produce timestamped
        // HandLandmarkerResult objects, using the same confidence thresholds the
        // analyzer's own live-camera landmarker uses.
        val simLandmarker = HandLandmarker.createFromOptions(
            context,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.VIDEO)
                .setMinHandDetectionConfidence(0.3f)
                .setMinTrackingConfidence(0.5f)
                .setNumHands(2)
                .build()
        )

        val processResultsMethod = SignLanguageAnalyzer::class.java
            .getDeclaredMethod("processResults", HandLandmarkerResult::class.java)
            .apply { isAccessible = true }
        val detectionStartTimeField = SignLanguageAnalyzer::class.java
            .getDeclaredField("detectionStartTime")
            .apply { isAccessible = true }

        for (w in words) {
            currentTargetWord = w.word
            Log.i(TAG, "########## SIMULATING '${w.word}' (numHands=${w.numHands}) ##########")
            analyzer.startDetection()
            var ts = detectionStartTimeField.getLong(analyzer)

            for (fileName in w.files) {
                val frames = extractFrames(context, fileName)
                Log.i(TAG, "  -- video '$fileName': ${frames.size} frames --")
                for (bitmap in frames) {
                    val mpImage = BitmapImageBuilder(bitmap).build()
                    val result = simLandmarker.detectForVideo(mpImage, ts)
                    ts += 100L
                    processResultsMethod.invoke(analyzer, result)
                    Thread.sleep(20)
                }
            }
            analyzer.stopDetection()
        }

        simLandmarker.close()

        Log.i(TAG, "=== SIMULATION SUMMARY ===")
        for (w in words) {
            val results = announced[w.word] ?: emptyList()
            val gotTarget = results.contains(w.word)
            val status = when {
                gotTarget -> "RECOGNIZED"
                results.isEmpty() -> "NEVER_ANNOUNCED"
                else -> "WRONG_WORD"
            }
            Log.i(TAG, "RESULT|${w.word}|$status|${results.joinToString(",")}")
        }
    }

    private fun resourceUri(context: Context, name: String): Uri {
        val resId = context.resources.getIdentifier(name, "raw", context.packageName)
        require(resId != 0) { "Missing raw resource: $name" }
        return Uri.parse("android.resource://${context.packageName}/$resId")
    }

    // Mirrors VideoProcessor's private getTempFileFromUri + extractKeyFramesFromVideo,
    // reimplemented here so no production code needs to change visibility for testing.
    private fun extractFrames(context: Context, rawName: String): List<Bitmap> {
        val uri = resourceUri(context, rawName)
        val frames = mutableListOf<Bitmap>()
        val tempFile = File(context.cacheDir, "sim_${rawName}_${System.currentTimeMillis()}.mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(tempFile.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val interval = if (duration < 5000) 100L else 500L
            var time = 0L
            while (time < duration) {
                val bitmap = retriever.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    val rotated = if (rotation != 0) {
                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } else bitmap
                    frames.add(rotated)
                }
                time += interval
            }
        } finally {
            retriever.release()
            tempFile.delete()
        }
        return frames
    }
}
