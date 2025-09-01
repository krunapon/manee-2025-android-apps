package th.ac.kkw.tslgovapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
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
    private lateinit var btnSwitchCamera: Button // NEW: Camera switch button

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService

    private var textToSpeech: TextToSpeech? = null
    private var isDetecting = false
    private var lastRecognizedWord = ""
    private var currentVideoFile: File? = null

    // NEW: Camera selector tracking
    private var isFrontCamera = true
    private var currentCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

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
        btnSwitchCamera = findViewById(R.id.btn_switch_camera) // NEW: Initialize switch button
    }

    private fun setupClickListeners() {
        btnStartStop.setOnClickListener {
            toggleDetectionAndRecording()
        }

        btnRepeatSound.setOnClickListener {
            if (currentVideoFile != null && currentVideoFile!!.exists()) {
                openVideo()
            } else {
                repeatLastSound()
            }
        }

        btnBack.setOnClickListener {
            finish()
        }

        // NEW: Camera switch button click listener
        btnSwitchCamera.setOnClickListener {
            switchCamera()
        }

        // Add long press on preview for testing
        previewView.setOnLongClickListener {
            if (isDetecting) {
                testGestureDetection()
                Toast.makeText(this, "Test gesture triggered", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Start detection first", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    // NEW: Camera switching function
    private fun switchCamera() {
        // Stop current detection if running
        if (isDetecting) {
            Toast.makeText(this, "กรุณาหยุดการตรวจจับก่อนเปลี่ยนกล้อง", Toast.LENGTH_SHORT).show()
            return
        }

        // Toggle camera selector
        isFrontCamera = !isFrontCamera
        currentCameraSelector = if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Update button text
        btnSwitchCamera.text = if (isFrontCamera) "กล้องหลัง" else "กล้องหน้า"

        // Show toast message
        val cameraType = if (isFrontCamera) "กล้องหน้า" else "กล้องหลัง"
        Toast.makeText(this, "เปลี่ยนเป็น$cameraType", Toast.LENGTH_SHORT).show()

        // Restart camera with new selector
        startCamera()

        Log.d(TAG, "Switched to ${if (isFrontCamera) "front" else "back"} camera")
    }

    // NEW: Check if device has both cameras
    private fun hasBothCameras(): Boolean {
        val cameraProvider = this.cameraProvider ?: return false
        return try {
            cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) &&
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking camera availability", e)
            false
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
                Toast.makeText(this, "กรุณาอนุญาตการใช้กล้องและไมโครโฟนเพื่อใช้งานระบบ", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // NEW: Update switch button visibility based on camera availability
                if (hasBothCameras()) {
                    btnSwitchCamera.visibility = Button.VISIBLE
                    btnSwitchCamera.text = if (isFrontCamera) "กล้องหลัง" else "กล้องหน้า"
                } else {
                    btnSwitchCamera.visibility = Button.GONE
                }

                // Create Preview
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // Create Image Analyzer
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
                                        // updateResult(result) - commented out for testing
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

                // Create VideoCapture
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                        )
                    )
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                // NEW: Use current camera selector instead of fixed front camera
                val cameraSelector = currentCameraSelector

                // Unbind use cases before rebinding
                cameraProvider?.unbindAll()

                // Bind use cases to camera
                cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer, videoCapture
                )

                Log.d(TAG, "Camera started successfully with ${if (isFrontCamera) "front" else "back"} camera")

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                Toast.makeText(this, "Failed to start camera: ${exc.message}", Toast.LENGTH_LONG).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleDetectionAndRecording() {
        if (isDetecting) {
            // Stop detection and recording
            stopRecording()
            isDetecting = false
            btnStartStop.text = "เริ่มตรวจจับ"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            resultText.text = "ทำภาษามือเพื่อเริ่มการแปล"
            largeResultText.text = "ยังไม่มีการตรวจจับ"
            largeResultText.setBackgroundColor(Color.TRANSPARENT)

            // Update repeat button text
            btnRepeatSound.text = "เปิดวิดีโอ"

            // NEW: Enable camera switch when not detecting
            btnSwitchCamera.isEnabled = true

            Log.d(TAG, "Detection and recording stopped")
        } else {
            // Start detection and recording
            startRecording()
            isDetecting = true
            btnStartStop.text = "หยุดตรวจจับ"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            resultText.text = "กำลังตรวจจับภาษามือ..."
            largeResultText.text = "พร้อมรับภาษามือ"

            // Update repeat button text
            btnRepeatSound.text = "เล่นซ้ำ"

            // NEW: Disable camera switch during detection/recording
            btnSwitchCamera.isEnabled = false

            Log.d(TAG, "Detection and recording started")
        }
    }

    // FIXED: Create video file in accessible Downloads directory
    private fun createVideoFile(name: String): File {
        // NEW: Include camera type in filename
        val cameraType = if (isFrontCamera) "front" else "back"
        val fileName = "TSL_${cameraType}_${name}.mp4"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ - Use Downloads folder
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
        } else {
            // Android 9 and below - Use Movies folder
            val moviesDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "TSL_SignLanguage"
            )
            moviesDir.mkdirs()
            File(moviesDir, fileName)
        }
    }

    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return

        btnStartStop.isEnabled = false

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())

        // FIXED: Use accessible Downloads directory instead of app private directory
        val videoFile = createVideoFile(name)
        currentVideoFile = videoFile

        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        recording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .apply {
                if (ContextCompat.checkSelfPermission(this@CameraActivity, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        btnStartStop.isEnabled = true
                        val cameraType = if (isFrontCamera) "กล้องหน้า" else "กล้องหลัง"
                        Toast.makeText(this, "เริ่มบันทึกวิดีโอ ($cameraType)", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "Video recording started with ${if (isFrontCamera) "front" else "back"} camera")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val savedUri = recordEvent.outputResults.outputUri

                            // Show accessible path information
                            val accessPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                "Downloads folder"
                            } else {
                                "Movies/TSL_SignLanguage folder"
                            }

                            val msg = "บันทึกวิดีโอสำเร็จ!\nดูได้ใน: $accessPath\nชื่อไฟล์: ${videoFile.name}"
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                            resultText.text = "บันทึกแล้ว: ${videoFile.name}"

                            Log.d(TAG, "Video saved to: ${videoFile.absolutePath}")
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video recording error: ${recordEvent.error}")
                            Toast.makeText(this, "การบันทึกวิดีโอล้มเหลว", Toast.LENGTH_SHORT).show()
                        }
                        btnStartStop.isEnabled = true
                    }
                }
            }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
    }

    // Rest of your existing methods remain the same...
    private fun openVideo() {
        currentVideoFile?.let { file ->
            if (file.exists()) {
                try {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        FileProvider.getUriForFile(
                            this,
                            "${applicationContext.packageName}.fileprovider",
                            file
                        )
                    } else {
                        Uri.fromFile(file)
                    }

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }

                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                        Toast.makeText(this, "เปิดวิดีโอ: ${file.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        openFileManager()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Cannot open video: ${e.message}")
                    Toast.makeText(this, "ไม่สามารถเปิดวิดีโอได้", Toast.LENGTH_SHORT).show()
                    showFileLocation()
                }
            } else {
                Toast.makeText(this, "ไม่พบไฟล์วิดีโอ", Toast.LENGTH_SHORT).show()
            }
        } ?: Toast.makeText(this, "ยังไม่มีวิดีโอที่บันทึกไว้", Toast.LENGTH_SHORT).show()
    }

    private fun openFileManager() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                        "*/*"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMovies%2FTSL_SignLanguage"),
                        "*/*"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }

            startActivity(intent)
            Toast.makeText(this, "เปิด File Manager แล้ว", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open file manager: ${e.message}")
            showFileLocation()
        }
    }

    private fun showFileLocation() {
        val locationMsg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "เปิด Files app → Downloads → หาไฟล์ TSL_xxx.mp4"
        } else {
            "เปิด Files app → Movies → TSL_SignLanguage"
        }
        Toast.makeText(this, "หาไฟล์ได้ที่: $locationMsg", Toast.LENGTH_LONG).show()
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

            Log.d(TAG, "Sign detected and announced: $result")
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
        recording?.stop()
    }
}