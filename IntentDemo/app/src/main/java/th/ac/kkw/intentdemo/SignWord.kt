package th.ac.kkw.intentdemo

// SignWord.kt
data class SignWord(
    val word: String,
    val meaning : String,      // คำแปล
    val category : String,      // หมวดหมู่
    val numHands : Int          // จำนวนมือ
)

fun main() {
    val w1 = SignWord(
        word = "ช่วย",
        meaning = "ขอความช่วยเหลือ",
        category = "โรงพยาบาล",
        numHands = 2
    )
    println("คำ: " + w1.word)
    println("ความหมาย: " + w1.meaning)
    println("หมวด: " + w1.category)
    println("จำนวนมือ: " + w1.numHands)
}
