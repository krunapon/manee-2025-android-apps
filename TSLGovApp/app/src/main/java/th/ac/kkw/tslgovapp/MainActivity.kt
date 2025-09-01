package th.ac.kkw.tslgovapp

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import th.ac.kkw.tslgovapp.CameraActivity
import th.ac.kkw.tslgovapp.LearnActivity
import th.ac.kkw.tslgovapp.model.Point3D
import th.ac.kkw.tslgovapp.model.HandLandmarkData
import th.ac.kkw.tslgovapp.model.SignWord
import th.ac.kkw.tslgovapp.model.RecognitionResult


class MainActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var startButton: Button
    private lateinit var learnButton: Button
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupVideoPlayer()
        setupStartButton()
        setupLearnButton()

        testModelClasses()
    }

    private fun initializeViews() {
        videoView = findViewById(R.id.videoView)
        startButton = findViewById(R.id.btnStartSignLanguage)
        learnButton = findViewById(R.id.btnLearnSignLanguage)
    }

    private fun setupVideoPlayer() {
        try {
            // วางไฟล์วิดีโอในโฟลเดอร์ res/raw/intro_video.mp4
            val uri = Uri.parse("android.resource://$packageName/${R.raw.sawasdee}")
            videoView.setVideoURI(uri)

            // ตั้งค่า listener สำหรับการเล่นวิดีโอ
            videoView.setOnPreparedListener { mediaPlayer ->
                this.mediaPlayer = mediaPlayer
                mediaPlayer.isLooping = true // เล่นซ้ำ
                videoView.start()
                Log.d("MainActivity", "Video started playing")
            }

            videoView.setOnErrorListener { _, what, extra ->
                Log.e("MainActivity", "Video error: what=$what, extra=$extra")
                true
            }

            videoView.setOnCompletionListener {
                Log.d("MainActivity", "Video completed")
                // วิดีโอจะเล่นซ้ำเนื่องจากตั้งค่า isLooping = true
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up video: ${e.message}")
        }
    }

    private fun setupStartButton() {
        startButton.setOnClickListener {
            // ไปยังหน้าแปลภาษามือ
            var intent = Intent(this, CameraActivity::class.java)
            startActivity(intent)

            // หยุดการเล่นวิดีโอเมื่อออกจากหน้านี้
            pauseVideo()
        }
    }

    private fun setupLearnButton() {
        learnButton.setOnClickListener {
            // ไปยังหน้าแปลภาษามือ
            var intent = Intent(this, LearnActivity::class.java)
            startActivity(intent)

            // หยุดการเล่นวิดีโอเมื่อออกจากหน้านี้
            pauseVideo()
        }
    }

    override fun onResume() {
        super.onResume()
        // เล่นวิดีโอต่อเมื่อกลับมาที่หน้านี้
        if (!videoView.isPlaying) {
            videoView.start()
        }
    }

    override fun onPause() {
        super.onPause()
        // หยุดวิดีโอชั่วคราวเมื่อออกจากหน้า
        pauseVideo()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ปล่อย resources เมื่อปิดแอป
        releaseVideo()
    }

    private fun pauseVideo() {
        if (videoView.isPlaying) {
            videoView.pause()
        }
    }

    private fun releaseVideo() {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            videoView.stopPlayback()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error releasing video: ${e.message}")
        }
    }
}


// ใน เพิ่ม test function สำหรับ handlandmarkdata
private fun testModelClasses() {
    val testPoint = Point3D(0.5f, 0.5f, 0.0f)
    val testLandmarks = HandLandmarkData(
        landmarks = listOf(testPoint)
    )
    val testWord = SignWord("ช่วย", category = "โรงพยาบาล", templateLandmarks = testLandmarks)
    val testResult = RecognitionResult("ช่วย", 0.85f, 0.15f)

    Log.d("ModelTest", "✅ Models work: ${testWord.word}")
}