package th.ac.kkw.tslgovapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ใช้ layout พื้นฐานก่อน
        setContentView(android.R.layout.activity_list_item)

        // แสดงข้อความทดสอบ
        Toast.makeText(this, "แอปเริ่มทำงานแล้ว!", Toast.LENGTH_LONG).show()

        // ทดสอบเปิดกล้อง (ไม่ใช้ CardView ก่อน)
        setupBasicClickListeners()
    }

    private fun setupBasicClickListeners() {
        // ทดสอบเปิด CameraActivity หลังจาก 3 วินาที
        findViewById<android.view.View>(android.R.id.content).setOnClickListener {
            try {
                startActivity(Intent(this, CameraActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(this, "ยังไม่สามารถเปิดกล้องได้", Toast.LENGTH_SHORT).show()
            }
        }
    }
}