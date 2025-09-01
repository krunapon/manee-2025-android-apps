package th.ac.kkw.tslgovapp.model

import java.io.Serializable

/**
 * Point3D - เก็บพิกัดจุดสำคัญของมือใน 3 มิติ
 * ใช้เก็บตำแหน่ง x, y, z ของแต่ละจุดสำคัญที่ MediaPipe ตรวจจับได้
 * เช่น ปลายนิ้ว, ข้อมือ, หัวแม่มือ ทั้งหมด 21 จุด
 */
data class Point3D(
    val x: Float, // พิกัดแนวนอน (0.0-1.0 normalized)
    val y: Float, // พิกัดแนวตั้ง (0.0-1.0 normalized)
    val z: Float  // พิกัดความลึก (relative depth)
) : Serializable

/**
 * HandLandmarkData - เก็บข้อมูลจุดสำคัญของมือ ณ เวลาหนึ่ง
 * ใช้บันทึก snapshot ของท่าทางภาษามือพร้อมกับ timestamp
 * สำหรับการเปรียบเทียบและจับคู่ pattern
 */
data class HandLandmarkData(
    val landmarks: List<Point3D>, // รายการจุดสำคัญทั้ง 21 จุด (หรือ 7 จุดที่เลือกใช้)
    val timestamp: Long = System.currentTimeMillis() // เวลาที่บันทึกข้อมูล
) : Serializable

/**
 * SignWord - เก็บข้อมูลคำศัพท์ภาษามือแต่ละคำ
 * เป็น template สำหรับการเปรียบเทียบและรู้จำท่าทาง
 * ใช้ในฐานข้อมูลคำศัพท์ทั้ง 9 คำ
 */
data class SignWord(
    val word: String,                                    // ชื่อคำ เช่น "เจ็บ", "ของหาย"
    val category: String,                                // หมวดหมู่ เช่น "โรงพยาบาล", "ตำรวจ"
    val templateLandmarks: HandLandmarkData,             // ท่าทางมาตรฐานสำหรับเปรียบเทียบ
    val sampleLandmarks: List<HandLandmarkData> = listOf() // ตัวอย่างท่าทางเพิ่มเติม
)

/**
 * RecognitionResult - เก็บผลลัพธ์การรู้จำภาษามือ
 * ใช้แสดงผลการแปลและความมั่นใจของระบบ
 * สำหรับ Simple Distance Matching algorithm
 */
data class RecognitionResult(
    val word: String,      // คำที่รู้จำได้
    val confidence: Float, // ความมั่นใจ (0.0-1.0 หรือ 0-100%)
    val distance: Float    // ระยะทาง Euclidean ที่คำนวณได้
)