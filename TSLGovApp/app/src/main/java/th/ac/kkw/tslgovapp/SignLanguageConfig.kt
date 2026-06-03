package th.ac.kkw.tslgovapp
import th.ac.kkw.tslgovapp.model.Point3D
import th.ac.kkw.tslgovapp.model.HandLandmarkData

// 🔧 เพิ่ม enum สำหรับประเภทมือ
enum class HandType {
    SINGLE_HAND,    // ใช้มือเดียว
    DOUBLE_HAND     // ใช้สองมือ
}

// 🔧 เพิ่ม handType ใน SignWord data class
data class SignWord(
    val word: String,
    val meaning: String,
    val category: String,
    val mainVideoFile: String,       // ไฟล์หลักสำหรับ template
    val testVideoFiles: List<String>, // ไฟล์สำหรับทดสอบ
    val priority: Int,               // 1 = ง่ายสุด, 3 = ยากสุด
    val expectedAccuracy: Int,        // เปอร์เซ็นต์ความแม่นยำที่คาดหวัง
    val numHands: Int,
)

object SignLanguageConfig {

    // 🎯 รายการคำศัพท์ทั้งหมด พร้อมระบุประเภทมือ
    val ALL_WORDS = listOf(
        // Phase 1: คำง่าย (เริ่มทดสอบจากนี้)
        SignWord(
            word = "ช่วย",
            meaning = "ขอความช่วยเหลือ",
            category = "โรงพยาบาล",
            mainVideoFile = "help_main.mp4",
            testVideoFiles = listOf("help_test1.mp4", "help_test2.mp4"),
            priority = 1,
            expectedAccuracy = 85,
            numHands = 2
        ),

        SignWord(
            word = "บัตรประชาชน",
            meaning = "บัตรประจำตัวประชาชน",
            category = "สถานีตำรวจ",
            mainVideoFile = "id_card_main.mp4",
            testVideoFiles = listOf("id_card_test1.mp4", "id_card_test2.mp4"),
            priority = 1,
            expectedAccuracy = 80,
            numHands = 2
        ),

        SignWord(
            word = "ห้องน้ำ",
            meaning = "ห้องสุขา",
            category = "สนามบิน",
            mainVideoFile = "toilet_main.mp4",
            testVideoFiles = listOf("toilet_test1.mp4", "toilet_test2.mp4"),
            priority = 2,
            expectedAccuracy = 75,
            numHands = 1
        ),

        // Phase 2: คำปานกลาง
        SignWord(
            word = "เจ็บคอ",
            meaning = "มีอาการเจ็บที่คอ",
            category = "โรงพยาบาล",
            mainVideoFile = "neck_ache_main.mp4",
            testVideoFiles = listOf("neck_ache_test1.mp4", "neck_ache_test2.mp4", "neck_ache_test3.mp4"),
            priority = 2,
            expectedAccuracy = 70,
            numHands = 2
        ),

        SignWord(
            word = "เครื่องบิน",
            meaning = "เครื่องบิน",
            category = "สนามบิน",
            mainVideoFile = "airplane_main.mp4",
            testVideoFiles = listOf("airplane_test1.mp4", "airplane_test2.mp4"),
            priority = 2,
            expectedAccuracy = 75,
            numHands = 1
        ),

        // Phase 3: คำยาก
        SignWord(
            word = "ปวดหัว",
            meaning = "มีอาการปวดหัว",
            category = "โรงพยาบาล",
            mainVideoFile = "head_ache_main.mp4",
            testVideoFiles = listOf("head_ache_test1.mp4"),
            priority = 3,
            expectedAccuracy = 65,
            numHands = 1
        ),

        SignWord(
            word = "หนังสือเดินทาง",
            meaning = "หนังสือเดินทาง",
            category = "สนามบิน",
            mainVideoFile = "passport_main.mp4",
            testVideoFiles = listOf("passport_test1.mp4", "passport_test2.mp4"),
            priority = 3,
            expectedAccuracy = 65,
            numHands = 2
        ),

        SignWord(
            word = "หาย",
            meaning = "สิ่งของหายไป",
            category = "สถานีตำรวจ",
            mainVideoFile = "lost_main.mp4",
            testVideoFiles = listOf("lost_test1.mp4", "lost_test2.mp4"),
            priority = 3,
            expectedAccuracy = 60,
            numHands = 2
        ),

        SignWord(
            word = "แจ้งความ",
            meaning = "การแจ้งความดำเนินคดี",
            category = "สถานีตำรวจ",
            mainVideoFile = "report_main.mp4",
            testVideoFiles = listOf("report_test1.mp4", "report_test2.mp4"),
            priority = 3,
            expectedAccuracy = 60,
            numHands = 1
        ),


        SignWord(
            word = "ไม่สบาย",
            meaning = "รู้สึกไม่สบาย/มีไข้",
            category = "โรงพยาบาล",
            mainVideoFile = "sick_main.mp4",
            testVideoFiles = listOf<String>("sick_test1.mp4", "sick_test2.mp4"),

            priority = 1,
            expectedAccuracy = 80,
            numHands = 1
        )
    )

    // ฟังก์ชันช่วยเหลือเดิม
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







    /**
     * ได้รายชื่อชื่อคำศัพท์เฉพาะ (สำหรับ backward compatibility)
     */
    fun getAllWordNames(): List<String> {
        return ALL_WORDS.map { it.word }
    }


}