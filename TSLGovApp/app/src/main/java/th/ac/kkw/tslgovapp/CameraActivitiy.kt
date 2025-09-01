package th.ac.kkw.tslgovapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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

        // เริ่มต้น Text-to-Speech
        textToSpeech = TextToSpeech(this, this)

        // เริ่มต้น Camera Executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // ตรวจสอบสิทธิ์กล้อง
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
            cameraProvider = cameraProviderFuture.get()

            // สร้าง Preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // สร้าง Image Analyzer สำหรับตรวจจับภาษามือ
            imageAnalyzer = ImageAnalysis.Builder().build().also {
                it.setAnalyzer(cameraExecutor, SignLanguageAnalyzer { result ->
                    runOnUiThread {
                        updateResult(result)
                    }
                })
            }

            // เลือกกล้องหน้า (เหมาะสำหรับภาษามือ)
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // ยกเลิกการใช้งานที่มีอยู่ก่อน
                cameraProvider?.unbindAll()

                // ผูก use cases กับ lifecycle
                cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
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
        } else {
            btnStartStop.text = "🎯 เริ่มตรวจจับ"
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            resultText.text = "ทำภาษามือเพื่อเริ่มการแปล"
            largeResultText.text = "ยังไม่มีการตรวจจับ"
        }
    }

    private fun updateResult(result: String) {
        if (isDetecting && result.isNotEmpty()) {
            lastRecognizedWord = result
            resultText.text = "ตรวจพบ: $result"
            largeResultText.text = result

            // เล่นเสียงพูดทันที
            textToSpeech?.speak(result, TextToSpeech.QUEUE_FLUSH, null, null)

            // แสดงข้อความยืนยันสั้นๆ
            Toast.makeText(this, "แปลเป็น: $result", Toast.LENGTH_SHORT).show()
        }
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
                // ใช้ภาษาอังกฤษแทน
                textToSpeech?.setLanguage(Locale.ENGLISH)
            }

            // ตั้งค่าเสียงให้ชัดเจน
            textToSpeech?.setSpeechRate(0.8f) // พูดช้าหน่อยให้ชัดเจน
            textToSpeech?.setPitch(1.0f) // โทนเสียงปกติ
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

// คลาสจำลองสำหรับวิเคราะห์ภาษามือ - รองรับคำศัพท์ในสถานที่ราชการ
class SignLanguageAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {

    // คำศัพท์ทั้ง 9 คำที่รองรับ (ตามเอกสาร)
    private val governmentWords = listOf(
        // โรงพยาบาล
        "เจ็บ", "ปวด", "ช่วยด้วย",
        // สถานีตำรวจ
        "ของหาย", "บัตรประชาชน", "แจ้งความ",
        // สถานีรถไฟ
        "ตั๋วรถไฟ", "หลงทาง", "ห้องน้ำ"
    )

    private var lastTime = 0L
    private var wordIndex = 0

    override fun analyze(image: androidx.camera.core.ImageProxy) {
        val currentTime = System.currentTimeMillis()

        // จำลองการตรวจจับทุก 3 วินาที
        if (currentTime - lastTime >= 3000) {
            lastTime = currentTime
            val result = governmentWords[wordIndex % governmentWords.size]
            wordIndex++
            onResult(result)
        }

        image.close()
    }
}