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

    // 🩺 Template สังเคราะห์สำหรับท่า "ไม่สบาย"
    // ลักษณะท่า: มือข้างเดียว ฝ่ามือเปิด แตะที่หน้าผาก (เช็คว่ามีไข้)
    // - ข้อมือ (wrist) อยู่ใกล้ระดับศีรษะ (Y ~0.30 = อยู่ส่วนบนของกรอบภาพ)
    // - นิ้วทุกนิ้วยืดขึ้น (tip Y < MCP Y) ไม่กำมือ
    // - นิ้วกระจายออก (ไม่กระจุก) เพื่อแยกจากท่า "ปวดหัว"
    // ☝️ เมื่อได้ไฟล์วิดีโอจริง ให้แทนที่ template นี้ด้วยการเรียก
    //    videoProcessor.createTemplateFromVideos("ไม่สบาย", listOf(...), numHands = 1)
    val SICK_SIGN_TEMPLATE = HandLandmarkData(
        landmarks = listOf(
            // ข้อมือยกอยู่ระดับหน้าผาก (Y = 0.30 = ส่วนบนของเฟรม)
            Point3D(0.50f, 0.45f, 0.00f),  // 0: WRIST
            // นิ้วโป้ง (กางออกด้านข้าง)
            Point3D(0.42f, 0.42f, -0.02f), // 1: THUMB_CMC
            Point3D(0.38f, 0.38f, -0.04f), // 2: THUMB_MCP
            Point3D(0.36f, 0.34f, -0.05f), // 3: THUMB_IP
            Point3D(0.34f, 0.30f, -0.06f), // 4: THUMB_TIP
            // นิ้วชี้ (ยืดขึ้น)
            Point3D(0.46f, 0.36f, 0.00f),  // 5: INDEX_MCP
            Point3D(0.45f, 0.30f, -0.02f), // 6: INDEX_PIP
            Point3D(0.44f, 0.25f, -0.04f), // 7: INDEX_DIP
            Point3D(0.43f, 0.20f, -0.05f), // 8: INDEX_TIP
            // นิ้วกลาง (ยืดขึ้น สูงสุด)
            Point3D(0.50f, 0.36f, 0.00f),  // 9: MIDDLE_MCP
            Point3D(0.50f, 0.29f, -0.02f), // 10: MIDDLE_PIP
            Point3D(0.50f, 0.23f, -0.04f), // 11: MIDDLE_DIP
            Point3D(0.50f, 0.18f, -0.05f), // 12: MIDDLE_TIP
            // นิ้วนาง (ยืดขึ้น)
            Point3D(0.54f, 0.36f, 0.00f),  // 13: RING_MCP
            Point3D(0.55f, 0.30f, -0.02f), // 14: RING_PIP
            Point3D(0.56f, 0.25f, -0.04f), // 15: RING_DIP
            Point3D(0.57f, 0.20f, -0.05f), // 16: RING_TIP
            // นิ้วก้อย (ยืดขึ้น)
            Point3D(0.58f, 0.38f, 0.00f),  // 17: PINKY_MCP
            Point3D(0.60f, 0.32f, -0.02f), // 18: PINKY_PIP
            Point3D(0.62f, 0.27f, -0.04f), // 19: PINKY_DIP
            Point3D(0.64f, 0.22f, -0.05f)  // 20: PINKY_TIP
        )
    )

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
            testVideoFiles = listOf("neck_ache_test1.mp4", "neck_ache_test2.mp4"),
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
            testVideoFiles = listOf("head_ache_test1.mp4", "head_ache_test2.mp4"),
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

        // 🩺 คำใหม่ — ใช้ template สังเคราะห์ (ยังไม่มีไฟล์วิดีโอ)
        SignWord(
            word = "ไม่สบาย",
            meaning = "รู้สึกไม่สบาย/มีไข้",
            category = "โรงพยาบาล",
            mainVideoFile = "",                           // ยังไม่มีไฟล์วิดีโอ — ใช้ SICK_SIGN_TEMPLATE แทน
            testVideoFiles = listOf<String>(),            // ยังไม่มีไฟล์วิดีโอทดสอบ
            priority = 1,
            expectedAccuracy = 70,
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