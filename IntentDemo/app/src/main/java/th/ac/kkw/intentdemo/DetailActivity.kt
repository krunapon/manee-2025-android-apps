package th.ac.kkw.intentdemo

import android.content.ContentValues.TAG
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.TextView
import java.util.Locale
import android.speech.tts.TextToSpeech.LANG_MISSING_DATA
import android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
import android.util.Log
import android.media.AudioManager

class DetailActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var wordToSpeak: String? = null  // Add this to store the word
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(
                Locale.forLanguageTag("th-TH")
            )
            if (result == LANG_MISSING_DATA ||
                result == LANG_NOT_SUPPORTED) {
                tts?.language = Locale.ENGLISH
            }
            tts?.speak(wordToSpeak, TextToSpeech.QUEUE_FLUSH,
                null, null)
        } else {
            Log.e(TAG, "เริ่มต้น TTS ไม่สำเร็จ")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        // สร้างออบเจ็กต์ TextToSpeech
        tts = TextToSpeech(this, this)

        // รับข้อมูลจาก Intent
        val word     = intent.getStringExtra("WORD")
        val meaning  = intent.getStringExtra("MEANING")
        val category = intent.getStringExtra("CATEGORY")
        val numHands = intent.getIntExtra("NUM_HANDS", 0)

        // แสดงผลใน TextView
        findViewById<TextView>(R.id.tvWord).text     = "คำ: $word"
        findViewById<TextView>(R.id.tvMeaning).text  = "ความหมาย: $meaning"
        findViewById<TextView>(R.id.tvCategory).text = "หมวด: $category"
        findViewById<TextView>(R.id.tvHands).text    = "จำนวนมือ: $numHands"

        // Set the word to be spoken
        wordToSpeak = word;
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
    }

}