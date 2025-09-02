package th.ac.kkw.tslgovapp
import th.ac.kkw.tslgovapp.model.Point3D
import th.ac.kkw.tslgovapp.model.HandLandmarkData
data class SignWord(
    val word: String,
    val meaning: String,
    val category: String,
    val mainVideoFile: String,       // ไฟล์หลักสำหรับ template
    val testVideoFiles: List<String>, // ไฟล์สำหรับทดสอบ
    val priority: Int,               // 1 = ง่ายสุด, 3 = ยากสุด
    val expectedAccuracy: Int        // เปอร์เซ็นต์ความแม่นยำที่คาดหวัง
)

object SignLanguageConfig {

    // สร้างข้อมูลเทมเพลตสำหรับท่าทาง "ช่วย" (แทนที่วิดีโอ)
    // นี่คือตัวอย่างข้อมูลพิกัด (landmarks) ที่สมมติขึ้นสำหรับท่า "ช่วย"
    // ในความเป็นจริง ข้อมูลนี้จะต้องถูกดึงออกมาจากไฟล์วิดีโอ help_main.mp4
    val HELP_SIGN_TEMPLATE = HandLandmarkData(
        landmarks = listOf(
            Point3D(0.5f, 0.5f, 0.0f), // ข้อมือ (Wrist)
            Point3D(0.4f, 0.6f, -0.1f), // โคนนิ้วโป้ง (Thumb CMC)
            Point3D(0.35f, 0.65f, -0.2f), // ข้อนิ้วโป้ง (Thumb MCP)
            Point3D(0.3f, 0.7f, -0.3f), // ปลายนิ้วโป้ง (Thumb IP)
            Point3D(0.45f, 0.55f, 0.0f), // โคนนิ้วชี้ (Index Finger MCP)
            Point3D(0.4f, 0.45f, -0.1f), // ข้อนิ้วชี้ (Index Finger PIP)
            Point3D(0.35f, 0.35f, -0.2f), // ปลายนิ้วชี้ (Index Finger DIP)
            Point3D(0.5f, 0.5f, 0.0f), // โคนนิ้วกลาง (Middle Finger MCP)
            Point3D(0.5f, 0.4f, -0.1f), // ข้อนิ้วกลาง (Middle Finger PIP)
            Point3D(0.5f, 0.3f, -0.2f), // ปลายนิ้วกลาง (Middle Finger DIP)
            Point3D(0.6f, 0.55f, 0.0f), // โคนนิ้วนาง (Ring Finger MCP)
            Point3D(0.65f, 0.45f, -0.1f), // ข้อนิ้วนาง (Ring Finger PIP)
            Point3D(0.7f, 0.35f, -0.2f), // ปลายนิ้วนาง (Ring Finger DIP)
            Point3D(0.7f, 0.5f, 0.0f), // โคนนิ้วก้อย (Pinky MCP)
            Point3D(0.75f, 0.4f, -0.1f), // ข้อนิ้วก้อย (Pinky PIP)
            Point3D(0.8f, 0.3f, -0.2f)  // ปลายนิ้วก้อย (Pinky DIP)
        )
    )
    // รายการคำศัพท์ทั้งหมด เรียงตามความง่าย-ยาก
    val ALL_WORDS = listOf(
        // Phase 1: คำง่าย (เริ่มทดสอบจากนี้)
        SignWord(
            word = "ช่วย",
            meaning = "ขอความช่วยเหลือ",
            category = "โรงพยาบาล",
            mainVideoFile = "help_main.mp4",
            testVideoFiles = listOf("help_test1.mp4", "help_test2.mp4"),
            priority = 1,
            expectedAccuracy = 85
        ),

        SignWord(
            word = "บัตรประชาชน",
            meaning = "บัตรประจำตัวประชาชน",
            category = "สถานีตำรวจ",
            mainVideoFile = "id_card_main.mp4",
            testVideoFiles = listOf("id_card_test1.mp4", "id_card_test2.mp4"),
            priority = 1,
            expectedAccuracy = 80
        ),

        SignWord(
            word = "ห้องน้ำ",
            meaning = "ห้องสุขา",
            category = "สนามบิน",
            mainVideoFile = "toilet_main.mp4",
            testVideoFiles = listOf("toilet_test1.mp4", "toilet_test2.mp4"),
            priority = 2,
            expectedAccuracy = 75
        ),

        // Phase 2: คำปานกลาง
        SignWord(
            word = "เจ็บคอ",
            meaning = "มีอาการเจ็บที่คอ",
            category = "โรงพยาบาล",
            mainVideoFile = "neck_ache_main.mp4",
            testVideoFiles = listOf("neck_ache_test1.mp4", "neck_ache_test2.mp4"),
            priority = 2,
            expectedAccuracy = 70
        ),

        SignWord(
            word = "เครื่องบิน",
            meaning = "เครื่องบิน",
            category = "สนามบิน",
            mainVideoFile = "airplane_main.mp4",
            testVideoFiles = listOf("airplane_test1.mp4", "airplane_test2.mp4"),
            priority = 2,
            expectedAccuracy = 75
        ),

        // Phase 3: คำยาก
        SignWord(
            word = "ปวดหัว",
            meaning = "มีอาการปวดหัว",
            category = "โรงพยาบาล",
            mainVideoFile = "head_ache_main.mp4",
            testVideoFiles = listOf("head_ache_test1.mp4", "head_ache_test2.mp4"),
            priority = 3,
            expectedAccuracy = 65
        ),

        SignWord(
            word = "หนังสือเดินทาง",
            meaning = "หนังสือเดินทาง",
            category = "สนามบิน",
            mainVideoFile = "passport_main.mp4",
            testVideoFiles = listOf("passport_test1.mp4", "passport_test2.mp4"),
            priority = 3,
            expectedAccuracy = 65
        ),

        SignWord(
            word = "หาย",
            meaning = "สิ่งของหายไป",
            category = "สถานีตำรวจ",
            mainVideoFile = "lost_main.mp4",
            testVideoFiles = listOf("lost_test1.mp4", "lost_test2.mp4"),
            priority = 3,
            expectedAccuracy = 60
        ),

        SignWord(
            word = "แจ้งความ",
            meaning = "การแจ้งความดำเนินคดี",
            category = "สถานีตำรวจ",
            mainVideoFile = "report_main.mp4",
            testVideoFiles = listOf("report_test1.mp4", "report_test2.mp4"),
            priority = 3,
            expectedAccuracy = 60
        )
    )

    // ฟังก์ชันช่วยเหลือ
    fun getWordsByPriority(priority: Int): List<SignWord> {
        return ALL_WORDS.filter { it.priority == priority }
    }

    fun getWordsByCategory(category: String): List<SignWord> {
        return ALL_WORDS.filter { it.category == category }
    }

    fun getWordByName(wordName: String): SignWord? {
        return ALL_WORDS.find { it.word == wordName }
    }

    fun getTestingOrder(): List<SignWord> {
        return ALL_WORDS.sortedWith(compareBy<SignWord> { it.priority }.thenBy { it.word })
    }
}