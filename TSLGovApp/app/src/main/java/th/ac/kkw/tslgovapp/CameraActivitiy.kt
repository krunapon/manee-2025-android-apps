package th.ac.kkw.tslgovapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var largeResultText: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnRepeatSound: Button
    private lateinit var btnBack: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var cameraExecutor: ExecutorService

    private var textToSpeech: TextToSpeech? = null
    private var isDetecting = false
    private var lastRecognizedWord = ""

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val TAG = "CameraActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        initViews()
        setupClickListeners()

        // Initialize Text-to-Speech
        textToSpeech = TextToSpeech(this, this)

        // Initialize Camera Executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Check camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun initViews() {
        previewView = findViewById(R.id.preview_view)
        resultText = findViewById(R.id.result_text)
        largeResultText = findViewById(R.id.large_result_text)
        btnStartStop = findViewById(R.id.btn_start_stop)
        btnRepeatSound = findViewById(R.id.btn_repeat_sound)
        btnBack = findViewById(R.id.btn_back)
    }

    private fun setupClickListeners() {
        btnStartStop.setOnClickListener {
            toggleDetection()
        }

        btnRepeatSound.setOnClickListener {
            repeatLastSound()
        }

        btnBack.setOnClickListener {
            finish()
        }

        // Add long press on preview for testing
        previewView.setOnLongClickListener {
            if (isDetecting) {
                testGestureDetection()
                Toast.makeText(this, "🧪 Test gesture triggered", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Start detection first", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "กรุณาอนุญาตการใช้กล้องเพื่อใช้งานระบบ", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // Create Preview
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // Create enhanced Image Analyzer with explicit types
                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(previewView.display.rotation)
                    .build()
                    .also { analysis: ImageAnalysis ->
                        analysis.setAnalyzer(
                            cameraExecutor,
                            DebugSignLanguageAnalyzer(
                                onResult = { result: String ->
                                    runOnUiThread {
                                        updateResult(result)
                                    }
                                },
                                onDebug = { debugInfo: String ->
                                    runOnUiThread {
                                        Log.d("CameraActivity", debugInfo)
                                    }
                                }
                            )
                        )
                    }

                // Use front camera (better for sign language)
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                // Unbind use cases before rebinding
                cameraProvider?.unbindAll()

                // Bind use cases to camera
                cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

                Log.d(TAG, "Camera started successfully")

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_LONG).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleDetection() {
        isDetecting = !isDetecting
        if (isDetecting) {
            btnStartStop.text = "⏹️ หยุดตรวจจับ"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            resultText.text = "กำลังตรวจจับภาษามือ..."
            largeResultText.text = "พร้อมรับภาษามือ"
            Log.d(TAG, "🎯 Detection started")
        } else {
            btnStartStop.text = "🎯 เริ่มตรวจจับ"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            resultText.text = "ทำภาษามือเพื่อเริ่มการแปล"
            largeResultText.text = "ยังไม่มีการตรวจจับ"
            largeResultText.setBackgroundColor(Color.TRANSPARENT)
            Log.d(TAG, "⏹️ Detection stopped")
        }
    }

    private fun updateResult(result: String) {
        if (isDetecting && result.isNotEmpty()) {
            lastRecognizedWord = result
            resultText.text = "ตรวจพบ: $result"
            largeResultText.text = result

            // Play sound immediately
            textToSpeech?.speak(result, TextToSpeech.QUEUE_FLUSH, null, null)

            // Visual feedback
            largeResultText.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_green_light)
            )

            // Reset background after 1 second using Handler
            Handler(Looper.getMainLooper()).postDelayed({
                largeResultText.setBackgroundColor(Color.TRANSPARENT)
            }, 1000)

            Log.d(TAG, "✅ Sign detected and announced: $result")
        }
    }

    private fun testGestureDetection() {
        val testWords = listOf("เจ็บ", "ปวด", "ช่วยด้วย")
        val randomWord = testWords.random()
        updateResult(randomWord)
    }

    private fun repeatLastSound() {
        if (lastRecognizedWord.isNotEmpty()) {
            textToSpeech?.speak(lastRecognizedWord, TextToSpeech.QUEUE_FLUSH, null, null)
            Toast.makeText(this, "เล่นซ้ำ: $lastRecognizedWord", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "ยังไม่มีคำที่แปลได้", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale("th", "TH"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "ไม่รองรับภาษาไทย")
                // Use English instead
                textToSpeech?.setLanguage(Locale.ENGLISH)
            }

            // Set voice settings for clarity
            textToSpeech?.setSpeechRate(0.8f) // Speak slower for clarity
            textToSpeech?.setPitch(1.0f) // Normal pitch
        } else {
            Log.e(TAG, "เริ่มต้น TextToSpeech ไม่สำเร็จ")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}