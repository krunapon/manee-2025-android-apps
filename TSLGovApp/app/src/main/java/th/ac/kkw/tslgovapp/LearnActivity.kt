package th.ac.kkw.tslgovapp

import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.util.*

class LearnActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var videoView: VideoView
    private lateinit var btnReplay: Button
    private lateinit var btnOtherWord: Button
    private lateinit var btnBack: Button
    private lateinit var btnReplayVideo: Button
    private lateinit var tvWordTitle: TextView
    private lateinit var tvWordMeaning: TextView
    private lateinit var etSearchWord: EditText
    private lateinit var textToSpeech: TextToSpeech

    private var currentWordIndex = 0

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
            videoFileName = "report.mp4",
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
            videoFileName = "head_ache.mp4",
            category = "โรงพยาบาล"
        ),
        SignWord(
            word = "ช่วย",
            meaning = "ช่วย",
            videoFileName = "help.mp4",
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
            videoFileName = "toilet.mp4",
            category = "สนามบิน"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_learn)

        initializeViews()
        initializeTextToSpeech()
        setupVideoView()
        setupSearchFunctionality()
        loadCurrentWord()

        // เปลี่ยนจาก btnTranslate เป็น btnReplay สำหรับเล่นซ้ำ
        btnReplay.setOnClickListener {
            replayCurrentWord()
        }

        btnOtherWord.setOnClickListener {
            moveToNextWord()
        }

        btnBack.setOnClickListener {
            finish()
        }

        btnReplayVideo.setOnClickListener {
            // เล่นซ้ำวิดีโอเท่านั้น (ไม่พูด)
            playVideoOnly()
        }
    }

    private fun initializeViews() {
        videoView = findViewById(R.id.videoView)
        btnReplay = findViewById(R.id.btnTranslate) // เปลี่ยนจาก btnTranslate เป็น btnReplay
        btnOtherWord = findViewById(R.id.btnOtherWord)
        tvWordMeaning = findViewById(R.id.tvWordMeaning)
        tvWordTitle = findViewById(R.id.tvWordTitle)
        btnBack = findViewById(R.id.btnBack)
        btnReplayVideo = findViewById(R.id.btnReplay)
        etSearchWord = findViewById(R.id.etSearchWord) // EditText ใหม่สำหรับค้นหา
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this, this)
    }

    private fun setupVideoView() {
        videoView.setOnCompletionListener { mediaPlayer ->
            // เล่นวิดีโอซ้ำ
            mediaPlayer.isLooping = true
        }

        videoView.setOnErrorListener { _, what, extra ->
            Log.e("LearnActivity", "Video error: what=$what, extra=$extra")
            Toast.makeText(this, "ไม่สามารถเล่นวิดีโอได้", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun setupSearchFunctionality() {
        etSearchWord.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val searchText = s.toString().trim()
                if (searchText.isNotEmpty()) {
                    searchAndDisplayWord(searchText)
                }
            }
        })
    }

    private fun searchAndDisplayWord(searchText: String) {
        val foundWordIndex = signLanguageWords.indexOfFirst { word ->
            word.word.contains(searchText, ignoreCase = true) ||
                    word.meaning.contains(searchText, ignoreCase = true)
        }

        if (foundWordIndex != -1) {
            currentWordIndex = foundWordIndex
            loadCurrentWord()
            Toast.makeText(this, "พบคำ: ${signLanguageWords[currentWordIndex].word}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "ไม่พบคำที่ค้นหา: $searchText", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadCurrentWord() {
        val currentWord = signLanguageWords[currentWordIndex]

        // แสดงชื่อคำและหมวดหมู่
        tvWordTitle.text = "${currentWord.word} (${currentWord.category})"

        // แสดงความหมายทันทีเมื่อโหลดคำ
        tvWordMeaning.text = "ความหมาย: ${currentWord.meaning}"

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
                videoView.start()
            } else {
                // ถ้าไม่พบไฟล์วิดีโอ ใช้วิดีโอ demo
                loadDemoVideo()
            }
        } catch (e: Exception) {
            Log.e("LearnActivity", "Error loading video", e)
            loadDemoVideo()
        }

        // พูดเสียงภาษาไทยทันทีเมื่อโหลดคำ
        speakText(currentWord.word)
    }

    private fun loadDemoVideo() {
        // ใช้วิดีโอ demo เมื่อไม่พบไฟล์ต้นฉบับ
        Toast.makeText(this, "ใช้วิดีโอตัวอย่างแทน", Toast.LENGTH_SHORT).show()
        // คุณสามารถใส่วิดีโอ demo ใน raw folder
        val demoVideoId = resources.getIdentifier("demo_sign", "raw", packageName)
        if (demoVideoId != 0) {
            val uri = Uri.parse("android.resource://$packageName/$demoVideoId")
            videoView.setVideoURI(uri)
            videoView.start()
        }
    }

    private fun replayCurrentWord() {
        val currentWord = signLanguageWords[currentWordIndex]

        // เล่นซ้ำทั้งวิดีโอและเสียง
        playVideoOnly()
        speakText(currentWord.word)

        Toast.makeText(this, "เล่นซ้ำ: ${currentWord.word}", Toast.LENGTH_SHORT).show()
    }

    private fun playVideoOnly() {
        // เล่นวิดีโอซ้ำจากตำแหน่งเริ่มต้น
        videoView.seekTo(0)
        videoView.start()
    }

    private fun moveToNextWord() {
        currentWordIndex = (currentWordIndex + 1) % signLanguageWords.size
        loadCurrentWord()

        val currentWord = signLanguageWords[currentWordIndex]
        Toast.makeText(this, "เปลี่ยนไปคำ: ${currentWord.word}", Toast.LENGTH_SHORT).show()
    }

    private fun speakText(text: String) {
        if (::textToSpeech.isInitialized && textToSpeech.isSpeaking.not()) {
            // ใช้ความเร็วพูดที่เหมาะสม (0.8-1.0 ตามที่ระบุในเอกสาร)
            textToSpeech.setSpeechRate(0.9f)
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
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
        } else {
            Log.e("LearnActivity", "TextToSpeech initialization failed")
            Toast.makeText(this, "ไม่สามารถเริ่มระบบพูดได้", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // เล่นวิดีโอต่อเมื่อกลับมาที่หน้านี้
        if (videoView.canSeekForward()) {
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