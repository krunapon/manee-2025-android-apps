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
    private lateinit var videoProcessor: VideoProcessor

    private var isSpeakingCooldown = false // Flag บอกว่ากำลังอยู่ในช่วง Cooldown หรือไม่
    private val cooldownHandler = Handler(Looper.getMainLooper()) // ตัวหน่วงเวลา
    private val SPEAKING_COOLDOWN_DELAY = 2500L // ระยะเวลา Cooldown (2.5 วินาที) ลองปรับค่านี้ได้

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

    /**
     * โหลด Template ของคำศัพท์ภาษามือทั้งหมดที่กำหนดไว้ในโครงการ
     * การทำงานทั้งหมดจะอยู่ใน Background Thread เพื่อป้องกันไม่ให้แอปค้าง
     */
    private fun loadSignLanguageTemplates() {
        // ใช้ lifecycleScope.launch(Dispatchers.IO) เพื่อทำงานใน Background Thread
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting to load sign language templates...")

            // หมวดโรงพยาบาล
            videoProcessor.createTemplateFromVideos("เจ็บคอ", listOf(Uri.parse("android.resource://$packageName/${R.raw.neck_ache}")), 2)
            videoProcessor.createTemplateFromVideos("ปวดหัว", listOf(Uri.parse("android.resource://$packageName/${R.raw.head_ache_main}")), 1)
            videoProcessor.createTemplateFromVideos("ช่วย", listOf(Uri.parse("android.resource://$packageName/${R.raw.help_master}")), 2)
            /*videoProcessor.createTemplateFromVideos("ช่วย", listOf(
                Uri.parse("android.resource://$packageName/${R.raw.help_master}"),
                Uri.parse("android.resource://$packageName/${R.raw.help_main}"),
                Uri.parse("android.resource://$packageName/${R.raw.help_test1}"),
                Uri.parse("android.resource://$packageName/${R.raw.help_test2}"),
                Uri.parse("android.resource://$packageName/${R.raw.help_test3}")
            )) */

            // หมวดสถานีตำรวจ
            videoProcessor.createTemplateFromVideos("หาย", listOf(Uri.parse("android.resource://$packageName/${R.raw.lost}")),2)
            videoProcessor.createTemplateFromVideos("บัตรประชาชน", listOf(Uri.parse("android.resource://$packageName/${R.raw.id_card}")), 2)
            videoProcessor.createTemplateFromVideos("แจ้งความ", listOf(Uri.parse("android.resource://$packageName/${R.raw.report_main}")), 1)

            // หมวดสนามบิน/ขนส่ง
            videoProcessor.createTemplateFromVideos("หนังสือเดินทาง", listOf(Uri.parse("android.resource://$packageName/${R.raw.passport}")), 2)
            videoProcessor.createTemplateFromVideos("เครื่องบิน", listOf(Uri.parse("android.resource://$packageName/${R.raw.airplane_tom}")),1)
            videoProcessor.createTemplateFromVideos("ห้องน้ำ", listOf(Uri.parse("android.resource://$packageName/${R.raw.toilet_ta}")), 1)
            // เมื่อโหลดเสร็จ สามารถแจ้งเตือนผู้ใช้ได้ (ต้องกลับมาที่ Main Thread)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@CameraActivity, "ระบบพร้อมใช้งาน", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "All templates loaded successfully.")
            }
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
           /* if (currentVideoFile != null && currentVideoFile!!.exists()) {
                openVideo()
            } else {
                repeatLastSound()
            } */
            takeScreenshot()
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
        currentCameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        btnSwitchCamera.text = if (isFrontCamera) "กล้องหลัง" else "กล้องหน้า"
        startCamera()
    }

    // ... (ฟังก์ชันอื่นๆ ทั้งหมดเหมือนเดิม ไม่มีการเปลี่ยนแปลง) ...

    private fun hasBothCameras(): Boolean {
        val cameraProvider = this.cameraProvider ?: return false
        return try {
            cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) &&
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        } catch (e: Exception) { false }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera()
            else {
                Toast.makeText(this, "กรุณาอนุญาตการใช้กล้องและไมโครโฟน", Toast.LENGTH_SHORT).show()
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

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(previewView.display.rotation)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(
                            cameraExecutor,
                            DebugSignLanguageAnalyzer(
                                context = this,
                                videoProcessor = videoProcessor,
                                onResult = { result ->
                                    runOnUiThread {
                                        updateResult(result)
                                    }
                                },
                                onDebug = { debugInfo ->
                                    Log.d(TAG, debugInfo)
                                }
                            )
                        )
                    }

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, currentCameraSelector, preview, imageAnalyzer, videoCapture)

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // In CameraActivity.kt

    private fun toggleDetectionAndRecording() {
        if (isDetecting) {
            stopRecording()
            isDetecting = false
            btnStartStop.text = "เริ่มตรวจจับ"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            resultText.text = "ทำภาษามือเพื่อเริ่มการแปล"
            largeResultText.text = "ยังไม่มีการตรวจจับ"
            largeResultText.setBackgroundColor(Color.TRANSPARENT)
            // [MODIFIED] Set button text to "Save Image"
            btnRepeatSound.text = "บันทึกภาพ"
            btnSwitchCamera.isEnabled = true
        } else {
            startRecording()
            isDetecting = true
            btnStartStop.text = "หยุดตรวจจับ"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            resultText.text = "กำลังตรวจจับภาษามือ..."
            largeResultText.text = "พร้อมรับภาษามือ"
            // [MODIFIED] Set button text to "Save Image"
            btnRepeatSound.text = "บันทึกภาพ"
            btnSwitchCamera.isEnabled = false
        }
    }

    private fun createVideoFile(name: String): File {
        val fileName = "TSL_${if (isFrontCamera) "front" else "back"}_${name}.mp4"
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "TSL_SignLanguage").apply { mkdirs() }
        }
        return File(dir, fileName)
    }

    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return
        btnStartStop.isEnabled = false
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
        currentVideoFile = createVideoFile(name)
        val outputOptions = FileOutputOptions.Builder(currentVideoFile!!).build()

        recording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .apply { if (ContextCompat.checkSelfPermission(this@CameraActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when(recordEvent) {
                    is VideoRecordEvent.Start -> {
                        btnStartStop.isEnabled = true
                        Toast.makeText(this, "เริ่มบันทึกวิดีโอ", Toast.LENGTH_SHORT).show()
                    }
                    is VideoRecordEvent.Finalize -> {
                        btnStartStop.isEnabled = true
                        if (!recordEvent.hasError()) {
                            val msg = "บันทึกวิดีโอสำเร็จในโฟลเดอร์ Downloads/Movies"
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "Video recording error: ${recordEvent.error}")
                        }
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
                    val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setDataAndType(uri, "video/mp4")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "ไม่สามารถเปิดวิดีโอได้", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateResult(result: String) {
        // ✅ เพิ่ม Log เพื่อดูว่าได้รับค่าอะไรมาจริงๆ
        Log.d(TAG, "🎯 updateResult() received: '$result'")
        Log.d(TAG, "🎯 lastRecognizedWord: '$lastRecognizedWord'")
        Log.d(TAG, "🎯 isSpeakingCooldown: $isSpeakingCooldown")

        if (isSpeakingCooldown) {
            Log.d(TAG, "⏭️ Skipping due to cooldown")
            return
        }
        // เพิ่ม Debug Log
        Log.d(TAG, "🎤 Best: updateResult called: result='$result', last='$lastRecognizedWord'")

        if (isDetecting && result.isNotEmpty() && result != lastRecognizedWord) {
            lastRecognizedWord = result
            resultText.text = "ตรวจพบ: $result"
            largeResultText.text = result
            textToSpeech?.speak(result, TextToSpeech.QUEUE_FLUSH, null, null)

            // *** เริ่มนับ Cooldown ทันทีหลังจากสั่งพูด ***
            isSpeakingCooldown = true
            cooldownHandler.postDelayed({
                isSpeakingCooldown = false
                lastRecognizedWord = "" // รีเซ็ตคำล่าสุดหลัง Cooldown เพื่อให้พูดคำเดิมซ้ำได้ถ้าต้องการ
            }, SPEAKING_COOLDOWN_DELAY)

            largeResultText.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            Handler(Looper.getMainLooper()).postDelayed({ largeResultText.setBackgroundColor(Color.TRANSPARENT) }, 1000)
        }
    }

    private fun testGestureDetection() {
        val testWords = listOf("เจ็บคอ", "บัตรประชาชน", "ห้องน้ำ")
        updateResult(testWords.random())
    }

    private fun repeatLastSound() {
        if (lastRecognizedWord.isNotEmpty()) {
            textToSpeech?.speak(lastRecognizedWord, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            Toast.makeText(this, "ยังไม่มีคำที่แปลได้", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale("th", "TH"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                textToSpeech?.language = Locale.ENGLISH
            }
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
        cooldownHandler.removeCallbacksAndMessages(null)
    }

    // [NEW FUNCTION] Function to capture the PreviewView as a screenshot
    private fun takeScreenshot() {
        // 1. Create a bitmap from the PreviewView
        val bitmap = previewView.bitmap ?: return

        // 2. Create a unique filename using a timestamp
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val filename = "TSL_Screenshot_$name.jpg"

        // 3. Save the image to the public Pictures directory
        // This handles both new (Android 10+) and old Android versions
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10 and above (Scoped Storage)
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri).use { outputStream ->
                        if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95,
                                outputStream!!
                            )) {
                            throw java.io.IOException("Failed to save bitmap.")
                        }
                    }
                }
            } else {
                // For older Android versions
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val imageFile = File(picturesDir, filename)
                java.io.FileOutputStream(imageFile).use { outputStream ->
                    if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                        throw java.io.IOException("Failed to save bitmap.")
                    }
                }
            }
            Toast.makeText(this, "บันทึกภาพสำเร็จในโฟลเดอร์ Pictures", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot", e)
            Toast.makeText(this, "เกิดข้อผิดพลาดในการบันทึกภาพ", Toast.LENGTH_SHORT).show()
        }
    }
}