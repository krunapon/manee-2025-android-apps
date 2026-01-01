package th.ac.kkw.tslgovapp

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.util.*
import android.os.Looper

class LearnActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var videoView: VideoView
    private lateinit var btnTranslate: Button
    private lateinit var btnOtherWord: Button
    private lateinit var btnBack: Button
    private lateinit var btnReplay: Button
    private lateinit var tvWordTitle: TextView
    private lateinit var tvWordMeaning: TextView
    private lateinit var textToSpeech: TextToSpeech

    private var currentWordIndex = 0
    private var isTtsReady = false


    // ข้อมูลคำศัพท์ภาษามือตามที่ระบุในเอกสาร
    private val signLanguageWords = listOf(
        SignWord(
            word = "บัตรประชาชน",
            meaning = "บัตรประจำตัวประชาชน",
            videoFileName = "id_card.mp4",
            category = "สถานีตำรวจ"
        ),
        // หมวดสถานีตำรวจ
        SignWord(
            word = "หาย",
            meaning = "สิ่งของหายไป",
            videoFileName = "lost.mp4",
            category = "สถานีตำรวจ"
        ),
        SignWord(
            word = "แจ้งความ",
            meaning = "การแจ้งความดำเนินคดี",
            videoFileName = "report_main.mp4",
            category = "สถานีตำรวจ"
        ),
        SignWord(
            word = "เจ็บคอ",
            meaning = "เจ็บคอ",
            videoFileName = "neck_ache.mp4",
            category = "โรงพยาบาล"
        ),
        SignWord(
            word = "ปวดหัว",
            meaning = "ปวดหัว",
            videoFileName = "head_ache_main.mp4",
            category = "โรงพยาบาล"
        ),
        SignWord(
            word = "ช่วย",
            meaning = "ช่วย",
            videoFileName = "help_main.mp4",
            category = "โรงพยาบาล"
        ),
        SignWord(
            word = "หนังสือเดินทาง",
            meaning = "หนังสือเดินทาง",
            videoFileName = "passport.mp4",
            category = "สนามบิน"
        ),
        SignWord(
            word = "เครื่องบิน",
            meaning = "เครื่องบิน",
            videoFileName = "airplane.mp4",
            category = "สนามบิน"
        ),
        SignWord(
            word = "ห้องน้ำ",
            meaning = "ห้องน้ำ",
            videoFileName = "toilet_main.mp4",
            category = "สนามบิน"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_learn)

        initializeViews()
        initializeTextToSpeech()
        setupVideoView()


        btnTranslate.setOnClickListener {
            translateCurrentWord()
        }

        btnOtherWord.setOnClickListener {
            moveToNextWord()
        }

        btnBack.setOnClickListener {
            finish()
        }

        btnReplay.setOnClickListener {
            // เล่นซ้ำวิดีโอ
            loadCurrentWord()
        }
    }

    private fun initializeViews() {
        videoView = findViewById(R.id.videoView)
        btnTranslate = findViewById(R.id.btnTranslate)
        btnOtherWord = findViewById(R.id.btnOtherWord)
        tvWordMeaning = findViewById(R.id.tvWordMeaning)
        btnBack = findViewById(R.id.btnBack)
        btnReplay = findViewById(R.id.btnReplay)
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this, this)
    }

    private fun setupVideoView() {
        videoView.setOnPreparedListener { mediaPlayer ->
            // เล่นวิดีโอซ้ำ
            mediaPlayer.isLooping = true
            autoSpeakCurrentWord()
            videoView.start()
        }

        videoView.setOnErrorListener { _, what, extra ->
            Log.e("LearnActivity", "Video error: what=$what, extra=$extra")
            Toast.makeText(this, "ไม่สามารถเล่นวิดีโอได้", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun loadCurrentWord() {
        val currentWord = signLanguageWords[currentWordIndex]

        // แสดงชื่อคำและหมวดหมู่
        //tvWordTitle.text = "${currentWord.word} (${currentWord.category})"

        // ซ่อนความหมายไว้ก่อน
        tvWordMeaning.text = "กดปุ่ม 'แปล' เพื่อดูความหมาย"

        // โหลดวิดีโอจาก raw folder
        try {
            val videoResourceId = resources.getIdentifier(
                currentWord.videoFileName.substringBeforeLast("."),
                "raw",
                packageName
            )

            if (videoResourceId != 0) {
                val uri = Uri.parse("android.resource://$packageName/$videoResourceId")
                videoView.setVideoURI(uri)
            } else {
                // ถ้าไม่พบไฟล์วิดีโอ ใช้วิดีโอ demo
                loadDemoVideo()
            }
        } catch (e: Exception) {
            Log.e("LearnActivity", "Error loading video", e)
            loadDemoVideo()
        }
    }

    private fun loadDemoVideo() {
        // ใช้วิดีโอ demo เมื่อไม่พบไฟล์ต้นฉบับ
        Toast.makeText(this, "ใช้วิดีโอตัวอย่างแทน", Toast.LENGTH_SHORT).show()
        // คุณสามารถใส่วิดีโอ demo ใน raw folder
        val demoVideoId = resources.getIdentifier("demo_sign", "raw", packageName)
        if (demoVideoId != 0) {
            val uri = Uri.parse("android.resource://$packageName/$demoVideoId")
            videoView.setVideoURI(uri)
        }
    }

    private fun autoSpeakCurrentWord() {
        val currentWord = signLanguageWords[currentWordIndex]
        // พูดเสียงภาษาไทย
        //speakText(currentWord.word + " หมายถึง " + currentWord.meaning)
        Log.d("TTS_DEBUG", "autoSpeakCurrentWord: Called for word '${currentWord.word}'. isTtsReady = $isTtsReady")
        if (isTtsReady) {
            // *** เพิ่มการหน่วงเวลาเล็กน้อย (250ms) ก่อนสั่งพูด ***
            // เพื่อให้แน่ใจว่า TTS Engine โหลดภาษาไทยพร้อมใช้งานจริงๆ
            // แก้ปัญหา Race Condition ของ TTS Initialization
            Log.d("TTS_DEBUG", "autoSpeakCurrentWord: Posting Handler with 300ms delay...")
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d("TTS_DEBUG", "autoSpeakCurrentWord: Handler delay is over. Calling speakText().")
                speakText(currentWord.word)
            }, 300) // หน่วงเวลา 0.3

        }
    }

    private fun translateCurrentWord() {
        val currentWord = signLanguageWords[currentWordIndex]

        // แสดงความหมาย
        tvWordMeaning.text = "ความหมาย: ${currentWord.meaning}"

        speakText(currentWord.word)

        // แสดง Toast แจ้งเตือน
        Toast.makeText(this, "กำลังแปล: ${currentWord.word}", Toast.LENGTH_SHORT).show()
    }

    private fun moveToNextWord() {
        currentWordIndex = (currentWordIndex + 1) % signLanguageWords.size
        loadCurrentWord()

        val currentWord = signLanguageWords[currentWordIndex]
        Toast.makeText(this, "เปลี่ยนไปคำ: ${currentWord.word}", Toast.LENGTH_SHORT).show()
    }

    private fun speakText(text: String) {
        Log.d("TTS_DEBUG", "speakText: Attempting to speak '$text'")
        val isInit = ::textToSpeech.isInitialized
        val isNotSpeaking = textToSpeech.isSpeaking.not()
        Log.d("TTS_DEBUG", "speakText: Pre-condition check -> isInitialized: $isInit, isNotSpeaking: $isNotSpeaking")
        if (::textToSpeech.isInitialized) {
            Log.d("TTS_DEBUG", "speakText: >>> CONDITIONS PASSED. EXECUTING SPEAK <<<")
            // ใช้ความเร็วพูดที่เหมาะสม (0.8-1.0 ตามที่ระบุในเอกสาร)
            textToSpeech.setSpeechRate(0.9f)
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        } else {
            Log.d("TTS_DEBUG", "speakText: --- CONDITIONS FAILED. SKIPPING SPEAK ---")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // ตั้งค่าภาษาไทยตามที่ระบุในเอกสาร (th-TH)
            val result = textToSpeech.setLanguage(Locale("th", "TH"))

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("LearnActivity", "Thai language not supported")
                Toast.makeText(this, "ไม่รองรับการพูดภาษาไทย", Toast.LENGTH_SHORT).show()
                // ใช้ภาษาอังกฤษแทน
                textToSpeech.setLanguage(Locale.US)
            } else {
                Log.d("LearnActivity", "TextToSpeech initialized successfully")
            }

            isTtsReady = true
            /* เรียกโหลดคำศัพท์ครั้งแรกตรงนี้ เพราะเรารู้แล้วว่า TTS พร้อมใช้งาน */
            loadCurrentWord()
        } else {
            Log.e("LearnActivity", "TextToSpeech initialization failed")
            Toast.makeText(this, "ไม่สามารถเริ่มระบบพูดได้", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // เล่นวิดีโอต่อ ถ้ามันไม่ได้เล่นอยู่ และมีวิดีโอโหลดไว้แล้ว (duration > 0)
        if (!videoView.isPlaying && videoView.duration > 0) {
            videoView.start()
        }
    }

    override fun onPause() {
        super.onPause()
        // หยุดวิดีโอเมื่อออกจากหน้า
        if (videoView.isPlaying) {
            videoView.pause()
        }

        // หยุดการพูด
        if (::textToSpeech.isInitialized && textToSpeech.isSpeaking) {
            textToSpeech.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // ปิด TextToSpeech
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }

        // ปิด VideoView
        videoView.stopPlayback()
    }

    // Data class สำหรับเก็บข้อมูลคำศัพท์
    data class SignWord(
        val word: String,
        val meaning: String,
        val videoFileName: String,
        val category: String
    )
}