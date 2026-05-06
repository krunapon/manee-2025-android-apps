package th.ac.kkw.intentdemo

// Android core
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

// CameraX
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.ContentValues.TAG
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech.LANG_MISSING_DATA
import android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
import android.util.Log


// MediaPipe Vision
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.core.RunningMode

// Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import java.util.Locale


class LandmarkActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private var tts: TextToSpeech? = null    // reuse Module 4!
    private var currentFinger: String = "-"

    private lateinit var tvFinger: TextView        // ✅ For displaying finger detection
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_landmark)

        // Initialize views
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        tvFinger = findViewById(R.id.tvFinger)  // ✅ Add this

        tts = TextToSpeech(this, this)

        // ✅ Setup MediaPipe
        setupHandLandmarker()

        findViewById<Button>(R.id.btnSpeak).setOnClickListener {
            tts?.speak(currentFinger, TextToSpeech.QUEUE_FLUSH, null, null)
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)

    }


    private fun analyzeFrame(image: ImageProxy) {
        val bitmap = image.toBitmap()
        val mpImage = BitmapImageBuilder(bitmap).build()
        handLandmarker?.detectAsync(mpImage, System.currentTimeMillis())
        image.close()
    }

    private fun onResult(result: HandLandmarkerResult) {
        val hands = result.landmarks()
        if (hands.isEmpty()) {
            runOnUiThread { tvFinger.text = "ไม่พบมือ"; overlay.clear() }
            return
        }

        val landmarks = hands[0]
        currentFinger = detectFinger(landmarks)
        runOnUiThread {
            tvFinger.text = "นิ้ว: $currentFinger"
            overlay.setLandmarks(landmarks)
        }
    }

    private fun detectFinger(landmarks: List<NormalizedLandmark>): String {
        val indexExtended = landmarks[8].y() < landmarks[6].y()
        val middleExtended = landmarks[12].y() < landmarks[10].y()
        val ringExtended = landmarks[16].y() < landmarks[14].y()
        val pinkyExtended = landmarks[20].y() < landmarks[18].y()

        return when {
            indexExtended && !middleExtended && !ringExtended && !pinkyExtended -> "ชี้"
            middleExtended && indexExtended -> "สอง"
            middleExtended && !indexExtended -> "กลาง"
            ringExtended -> "นาง"
            pinkyExtended -> "ก้อย"
            else -> "กำ"
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()

                // 1) Preview — แสดงภาพในจอ
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                // 2) ImageAnalysis — ส่ง frame ให้ MediaPipe
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer.setAnalyzer(executor) { image -> analyzeFrame(image) }

                // Create CameraSelector for front camera
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, analyzer)
            }, ContextCompat.getMainExecutor(this))
    }

    private var handLandmarker: HandLandmarker? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(
                Locale.forLanguageTag("th-TH")
            )
            if (result == LANG_MISSING_DATA ||
                result == LANG_NOT_SUPPORTED
            ) {
                tts?.language = Locale.ENGLISH
            }
        } else {
            Log.e(TAG, "เริ่มต้น TTS ไม่สำเร็จ")
        }
    }


    private fun setupHandLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setResultListener { result, _ -> onResult(result) }
            .build()

        handLandmarker =
            HandLandmarker.createFromOptions(this, options)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }
}





