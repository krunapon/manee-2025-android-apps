package th.ac.kkw.intentdemo

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.marginBottom


class MainActivity : AppCompatActivity() {
    val ALL_WORDS = listOf(
        SignWord("ช่วย",        "ขอความช่วยเหลือ", "โรงพยาบาล",  2),
        SignWord("ห้องน้ำ",    "ห้องสุขา",         "สนามบิน",    1),
        SignWord("เครื่องบิน", "อากาศยาน",         "สนามบิน",    1)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val container = findViewById<LinearLayout>(R.id.buttonContainer)
        for (signWord in ALL_WORDS) {
            var btn = Button(this)
            btn.text = signWord.word          // แสดงชื่อคำบนปุ่ม
            btn.textSize = 32f

            btn.setOnClickListener {
                val intent = Intent(this, DetailActivity::class.java)
                intent.putExtra("WORD",    signWord.word)
                intent.putExtra("MEANING",  signWord.meaning)
                intent.putExtra("CATEGORY", signWord.category)
                intent.putExtra("NUM_HANDS", signWord.numHands)
                startActivity(intent)
            }
            container.addView(btn)
        }

        val btnNext = findViewById<Button>(R.id.btnNext)
        btnNext.setOnClickListener {
            val intent = Intent(this, SecondActivity::class.java)
            startActivity(intent)
        }
        val btnAbout = findViewById<Button>(R.id.btnAbout)
        btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        // บล็อกใหม่ - ปุ่มเปิดกล้องตรวจมือ
        val btnCamera = findViewById<Button>(R.id.btnCamera)
        btnCamera.setOnClickListener {
            startActivity(Intent(this, LandmarkActivity::class.java))
        }

    }
}
