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
    private var isTemplatesLoaded = false
    private var isDetecting = false
    private var lastRecognizedWord = ""
    private var currentVideoFile: File? = null
    private var isFrontCamera = true
    private var currentCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private lateinit var videoProcessor: VideoProcessor
    private var signLanguageAnalyzer: SignLanguageAnalyzer? = null  // Add this line

    private var isSpeakingCooldown = false // Flag บอกว่ากำลังอยู่ในช่วง Cooldown หรือไม่
    private val cooldownHandler = Handler(Looper.getMainLooper()) // ตัวหน่วงเวลา
    private val SPEAKING_COOLDOWN_DELAY = 2500L // ระยะเวลา Cooldown (2.5 วินาที) ลองปรับค่านี้ได้

    // For countdown features
    private var isCountdownMode = false // true = countdown mode, false = continuous mode
    private val countdownHandler = Handler(Looper.getMainLooper())
    private var countdownValue = 0
    private val MODE_CONTINUOUS = 0
    private val MODE_SINGLE_FRAME = 1

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
        Log.d(TAG, "🎬 onCreate started")
        setContentView(R.layout.activity_camera)
        Log.d(TAG, "🎬 Layout set")

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

    private fun loadSignLanguageTemplates() {
        lifecycleScope.launch(Dispatchers.IO) {
            val loadedFromCache = videoProcessor.loadTemplatesFromCache()
            if (loadedFromCache) {
                withContext(Dispatchers.Main) {
                    isTemplatesLoaded = true
                    btnStartStop.isEnabled = true
                    Toast.makeText(this@CameraActivity, "ระบบพร้อมใช้งาน (โหลดจากแคช)", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "All templates loaded from cache successfully")
                }
            } else {
                // No cache - laod from videos (slow)
                Log.d(TAG, "No cache found, loading templates from videos...")
                loadTemplatesFromVideos()
            }
        }
    }
    /**
     * โหลด Template ของคำศัพท์ภาษามือทั้งหมดที่กำหนดไว้ในโครงการ
     * การทำงานทั้งหมดจะอยู่ใน Background Thread เพื่อป้องกันไม่ให้แอปค้าง
     */
    private fun loadTemplatesFromVideos() {
        // ใช้ lifecycleScope.launch(Dispatchers.IO) เพื่อทำงานใน Background Thread
        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting to load sign language templates...")

            // หมวดโรงพยาบาล
            videoProcessor.createTemplateFromVideos("เจ็บคอ", listOf(Uri.parse("android.resource://$packageName/${R.raw.neck_ache}")), 2)
            videoProcessor.createTemplateFromVideos("ปวดหัว", listOf(
                Uri.parse("android.resource://$packageName/${R.raw.head_ache_main}"),  // Commented out - may be different camera
                Uri.parse("android.resource://$packageName/${R.raw.head_ache_test1}"),
                Uri.parse("android.resource://$packageName/${R.raw.head_ache_test2}"),
                Uri.parse("android.resource://$packageName/${R.raw.head_ache_test3}"),
                Uri.parse("android.resource://$packageName/${R.raw.head_ache_test4}"),
                ), 1)
           // videoProcessor.createTemplateFromVideos("ช่วย", listOf(Uri.parse("android.resource://$packageName/${R.raw.help_main}")), 2)
            videoProcessor.createTemplateFromVideos("ช่วย", listOf(
              //  Uri.parse("android.resource://$packageName/${R.raw.help_main}"),
                Uri.parse("android.resource://$packageName/${R.raw.help_test1}"),
                Uri.parse("android.resource://$packageName/${R.raw.help_test2}"),
                Uri.parse("android.resource://$packageName/${R.raw.help_test3}"),
                Uri.parse("android.resource://$packageName/${R.raw.help_test4}")),2
            )
            // หมวดสถานีตำรวจ
            videoProcessor.createTemplateFromVideos("หาย", listOf(
                Uri.parse("android.resource://$packageName/${R.raw.lost}"),
                Uri.parse("android.resource://$packageName/${R.raw.lost_test1}"),
            //    Uri.parse("android.resource://$packageName/${R.raw.lost_test2}"),
                Uri.parse("android.resource://$packageName/${R.raw.lost_test3}"),
             //   Uri.parse("android.resource://$packageName/${R.raw.lost_test4}"),
            ),2)
            videoProcessor.createTemplateFromVideos("บัตรประชาชน", listOf(Uri.parse("android.resource://$packageName/${R.raw.id_card}")), 2)
            videoProcessor.createTemplateFromVideos("แจ้งความ", listOf(
                Uri.parse("android.resource://$packageName/${R.raw.report_main}"),
                Uri.parse("android.resource://$packageName/${R.raw.report_test1}"),
                Uri.parse("android.resource://$packageName/${R.raw.report_test2}"),
                Uri.parse("android.resource://$packageName/${R.raw.report_test3}"),
                Uri.parse("android.resource://$packageName/${R.raw.report_test4}"),
                ), 1)

            // หมวดสนามบิน/ขนส่ง
            videoProcessor.createTemplateFromVideos("หนังสือเดินทาง", listOf(Uri.parse("android.resource://$packageName/${R.raw.passport}")), 2)
            videoProcessor.createTemplateFromVideos("เครื่องบิน", listOf(
                Uri.parse("android.resource://$packageName/${R.raw.airplane_main}"),
                Uri.parse("android.resource://$packageName/${R.raw.airplane_test1}"),
                Uri.parse("android.resource://$packageName/${R.raw.airplane_test2}"),
                Uri.parse("android.resource://$packageName/${R.raw.airplane_test3}"),
                Uri.parse("android.resource://$packageName/${R.raw.airplane_test4}"),
            ), 1)
            videoProcessor.createTemplateFromVideos("ห้องน้ำ", listOf(
                Uri.parse("android.resource://$packageName/${R.raw.toilet_main}"),
                Uri.parse("android.resource://$packageName/${R.raw.toilet_test1}"),
                Uri.parse("android.resource://$packageName/${R.raw.toilet_test2}"),
                Uri.parse("android.resource://$packageName/${R.raw.toilet_test3}"),
                Uri.parse("android.resource://$packageName/${R.raw.toilet_test4}"),
                ), 1)

            // Save to cache for next time
            videoProcessor.saveTemplatesToCache()

            // เมื่อโหลดเสร็จ สามารถแจ้งเตือนผู้ใช้ได้ (ต้องกลับมาที่ Main Thread)
            withContext(Dispatchers.Main) {
                isTemplatesLoaded = true
                btnStartStop.isEnabled = true
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
        btnStartStop.isEnabled = false
        btnRepeatSound = findViewById(R.id.btn_repeat_sound)
        btnBack = findViewById(R.id.btn_back)
        btnSwitchCamera = findViewById(R.id.btn_switch_camera)
    }

    private fun setupClickListeners() {
        btnStartStop.setOnClickListener {
            toggleDetectionAndRecording()
            true
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
            toggleCoundownMode()
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

                // Use portrait resolution (width x height for portrait mode)
                val targetResolution = android.util.Size(720, 1280) // Portrait: width=720, height=1280

                val preview = Preview.Builder()
                    .setTargetResolution(targetResolution)
                    .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Ensure preview shows full image (not cropped)
                previewView.scaleType = androidx.camera.view.PreviewView.ScaleType.FIT_CENTER

                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(previewView.display.rotation)
                    .setTargetResolution(targetResolution) // CRITICAL: Use same resolution as preview
                    .build()
                    .also { analysis ->
                            signLanguageAnalyzer  = SignLanguageAnalyzer(
                                context = this,
                                videoProcessor = videoProcessor,
                                onResult = { result ->
                                    runOnUiThread {
                                        updateResult(result)
                                    }
                                }
                            )
                            // Set camera type for coordinate mirroring
                            signLanguageAnalyzer?.isFrontCamera = isFrontCamera
                            // Set initial capture mode based on isCountdownMode
                            signLanguageAnalyzer?.setCaptureMode(if (isCountdownMode) MODE_SINGLE_FRAME else MODE_CONTINUOUS)
                            analysis.setAnalyzer(cameraExecutor, signLanguageAnalyzer!!)
                    }

                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                    .build()
                videoCapture = VideoCapture.withOutput(recorder)

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, currentCameraSelector, preview, imageAnalyzer, videoCapture)

                // Log target resolution for debugging
                Log.d(TAG, "Camera started with target resolution: ${targetResolution.width}x${targetResolution.height}")

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // In CameraActivity.kt

    private fun toggleDetectionAndRecording() {
        if (!isTemplatesLoaded) {
            Toast.makeText(this, "กำลังโหลดข้อมูล กรุณารอสักครู่...",
                Toast.LENGTH_SHORT).show()
            return
        }
       // stop dtection and cancel any ongoing countdown
        if (isDetecting) {
            stopRecording()
            isDetecting = false
            signLanguageAnalyzer?.stopDetection()

            // cancel countdown if running
            countdownHandler.removeCallbacksAndMessages(null)
            countdownValue = 0

            btnStartStop.text = "เริ่มตรวจจับ"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            resultText.text = "ทำภาษามือเพื่อเริ่มการแปล"
            largeResultText.text = "ยังไม่มีการตรวจจับ"
            largeResultText.setBackgroundColor(Color.TRANSPARENT)

            btnRepeatSound.text = "บันทึกภาพ"
            btnSwitchCamera.isEnabled = true
        } else {
            startRecording()
            isDetecting = true
            btnStartStop.text = "หยุดตรวจจับ"
            signLanguageAnalyzer?.startDetection()
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
            if (isCountdownMode) {
                startCountdownCapture()
            } else {
                resultText.text = "กำลังตรวจจับภาษามือ..."
                largeResultText.text = "พร้อมรับภาษามือ"
            }

            btnRepeatSound.text = "บันทึกภาพ"
            btnSwitchCamera.isEnabled = false
        }
    }

    private fun startCountdownCapture() {
        countdownValue = 3
        resultText.text = "3"
        largeResultText.text = "เตรียมทำท่า..."
        countdownHandler.post(object: Runnable {
            override fun run() {
                when (countdownValue) {
                    3 -> {
                        resultText.text = "3"
                        countdownValue = 2
                        countdownHandler.postDelayed(this, 1000)
                    }
                    2 -> {
                        resultText.text = "2"
                        countdownValue = 1
                        countdownHandler.postDelayed(this, 1000)
                    }
                    1 -> {
                        resultText.text = "1"
                        countdownValue = 0
                        countdownHandler.postDelayed(this, 1000)
                    }
                    0 -> {
                        resultText.text = "ทำท่า!!!"
                        largeResultText.text = "ทำภาษามือ"
                        signLanguageAnalyzer?.captureSingleFrame()
                        countdownValue = -1 // Done
                    }
                }
            }
        })
    }

    private fun toggleCoundownMode() {
        isCountdownMode = !isCountdownMode
        val modeName = if (isCountdownMode) "โหมดนับถอยหลัง (3-2-1)" else "โหมดตรวจจับต่อเนื่อง"
        Toast.makeText(this, modeName, Toast.LENGTH_SHORT).show()
        if (isCountdownMode) {
            signLanguageAnalyzer?.setCaptureMode(MODE_SINGLE_FRAME)
        } else {
            signLanguageAnalyzer?.setCaptureMode(MODE_CONTINUOUS)
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
                            // Video saved successfully
                           // val msg = "บันทึกวิดีโอสำเร็จในโฟลเดอร์ Downloads/Movies"
                           // Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
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