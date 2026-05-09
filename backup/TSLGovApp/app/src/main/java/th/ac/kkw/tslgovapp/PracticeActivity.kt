// PracticeActivity.kt
package th.ac.kkw.tslgovapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class PracticeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // จะเพิ่ม layout ทีหลัง
        setContentView(android.R.layout.activity_list_item)
    }
}