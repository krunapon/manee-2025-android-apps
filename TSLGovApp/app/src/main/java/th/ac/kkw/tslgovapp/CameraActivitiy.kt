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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import th.ac.kkw.tslgovapp.VideoProcessor

class CameraActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var resultText: TextView
    private lateinit var largeResultText: TextView
    private lateinit var btnStartStop: Button
    private lateinit var btnRepeatSound: Button
    private lateinit var btnBack: Button
    private lateinit var btnSwitchCamera: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService

    private var textToSpeech: TextToSpeech? = null
    private var isDetecting = false
    private var lastRecognizedWord = ""
    private var currentVideoFile: File? = null

    private var isFrontCamera = true
    private var currentCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    // เพิ่ม VideoProcessor instance
    private lateinit var videoProcessor: VideoProcessor

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

    /**
     * โหลด Template ของคำศัพท์ภาษามือทั้งหมดที่กำหนดไว้ในโครงการ
     * การทำงานทั้งหมดจะอยู่ใน Background Thread เพื่อป้องกันไม่ให้แอปค้าง
     */
    private fun loadSignLanguageTemplates() {
        // ใช้ lifecycleScope.launch(Dispatchers.IO) เพื่อทำงานใน Background Thread
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting to load sign language templates...")

            // หมวดโรงพยาบาล [cite: 257]
           /* videoProcessor.createTemplateFromVideos("เจ็บคอ", listOf(Uri.parse("android.resource://$packageName/${R.raw.sore_throat_1}")))
            videoProcessor.createTemplateFromVideos("ปวดหัว", listOf(Uri.parse("android.resource://$packageName/${R.raw.headache_1}"))) */
            videoProcessor.createTemplateFromVideos("ช่วย", listOf(Uri.parse("android.resource://$packageName/${R.raw.help_main}"),
                Uri.parse("android.resource://$packageName/${R.raw.help_test1}"),
                Uri.parse("android.resource://$packageName/${R.raw.help_test2}"),
                Uri.parse("android.resource://$packageName/${R.raw.help_test3}")))

            // หมวดสถานีตำรวจ [cite: 261]
           // videoProcessor.createTemplateFromVideos("หาย", listOf(Uri.parse("android.resource://$packageName/${R.raw.lost_1}")))
          //  videoProcessor.createTemplateFromVideos("บัตรประชาชน", listOf(Uri.parse("android.resource://$packageName/${R.raw.id_card_1}")))
           // videoProcessor.createTemplateFromVideos("แจ้งความ", listOf(Uri.parse("android.resource://$packageName/${R.raw.report_1}")))

            // หมวดสนามบิน/ขนส่ง [cite: 265]
           /* videoProcessor.createTemplateFromVideos("หนังสือเดินทาง", listOf(Uri.parse("android.resource://$packageName/${R.raw.passport_1}")))
            videoProcessor.createTemplateFromVideos("เครื่องบิน", listOf(Uri.parse("android.resource://$packageName/${R.raw.airplane_1}")))
            videoProcessor.createTemplateFromVideos("ห้องน้ำ", listOf(Uri.parse("android.resource://$packageName/${R.raw.toilet_1}"))) */

            // เมื่อโหลดเสร็จ สามารถแจ้งเตือนผู้ใช้ได้ (ต้องกลับมาที่ Main Thread
            withContext(Dispatchers.Main) {
                Toast.makeText(this@CameraActivity, "ระบบพร้อมใช้งาน", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "All templates loaded successfully.")
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        // 1. สร้าง instance ของ VideoProcessor
        videoProcessor = VideoProcessor(this)

        // 2. เรียกฟังก์ชันเพื่อโหลด Template ทั้งหมดใน Background Thread
        loadSignLanguageTemplates()



        initViews()
        setupClickListeners()

        textToSpeech = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()

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
        btnSwitchCamera = findViewById(R.id.btn_switch_camera)
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

        btnSwitchCamera.setOnClickListener {
            switchCamera()
        }

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

    private fun switchCamera() {
        if (isDetecting) {
            Toast.makeText(this, "กรุณาหยุดการตรวจจับก่อนเปลี่ยนกล้อง", Toast.LENGTH_SHORT).show()
            return
        }

        isFrontCamera = !isFrontCamera
        currentCameraSelector = if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        btnSwitchCamera.text = if (isFrontCamera) "กล้องหลัง" else "กล้องหน้า"
        val cameraType = if (isFrontCamera) "กล้องหน้า" else "กล้องหลัง"
        Toast.makeText(this, "เปลี่ยนเป็น$cameraType", Toast.LENGTH_SHORT).show()
        startCamera()
        Log.d(TAG, "Switched to ${if (isFrontCamera) "front" else "back"} camera")
    }

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
                if (hasBothCameras()) {
                    btnSwitchCamera.visibility = Button.VISIBLE
                    btnSwitchCamera.text = if (isFrontCamera) "กล้องหลัง" else "กล้องหน้า"
                } else {
                    btnSwitchCamera.visibility = Button.GONE
                }

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // แก้ไขตรงนี้เพื่อส่ง VideoProcessor ไปให้ SignLanguageAnalyzer
                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(previewView.display.rotation)
                    .build()
                    .also { analysis: ImageAnalysis ->
                        analysis.setAnalyzer(
                            cameraExecutor,
                            DebugSignLanguageAnalyzer(
                                context = this,
                                videoProcessor = videoProcessor, // ส่ง instance ไป
                                onResult = { result: String ->
                                    runOnUiThread {
                                        updateResult(result)
                                    }
                                },
                                onDebug = { debugInfo: String ->
                                    runOnUiThread {
                                        Log.d(TAG, debugInfo)
                                    }
                                }
                            )
                        )
                    }

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                        )
                    )
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                val cameraSelector = currentCameraSelector

                cameraProvider?.unbindAll()

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
            stopRecording()
            isDetecting = false
            btnStartStop.text = "เริ่มตรวจจับ"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            resultText.text = "ทำภาษามือเพื่อเริ่มการแปล"
            largeResultText.text = "ยังไม่มีการตรวจจับ"
            largeResultText.setBackgroundColor(Color.TRANSPARENT)

            btnRepeatSound.text = "เปิดวิดีโอ"
            btnSwitchCamera.isEnabled = true
            Log.d(TAG, "Detection and recording stopped")
        } else {
            startRecording()
            isDetecting = true
            btnStartStop.text = "หยุดตรวจจับ"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            resultText.text = "กำลังตรวจจับภาษามือ..."
            largeResultText.text = "พร้อมรับภาษามือ"

            btnRepeatSound.text = "เล่นซ้ำ"
            btnSwitchCamera.isEnabled = false
            Log.d(TAG, "Detection and recording started")
        }
    }

    private fun createVideoFile(name: String): File {
        val cameraType = if (isFrontCamera) "front" else "back"
        val fileName = "TSL_${cameraType}_${name}.mp4"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
        } else {
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

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
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
                            val msg = "บันทึกวิดีโอสำเร็จ!\nดูได้ใน: ${videoFile.parentFile?.name}\nชื่อไฟล์: ${videoFile.name}"
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
            textToSpeech?.speak(result, TextToSpeech.QUEUE_FLUSH, null, null)
            largeResultText.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.holo_green_light)
            )
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
                textToSpeech?.setLanguage(Locale.ENGLISH)
            }
            textToSpeech?.setSpeechRate(0.8f)
            textToSpeech?.setPitch(1.0f)
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