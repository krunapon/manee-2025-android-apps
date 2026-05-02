package th.ac.kkw.intentdemo

data class Student(
    val name: String,
    val grade: Int
)

fun main() {
    val s1 = Student(name = "Manee", grade = 12)
    println("Name: " + s1.name)
    println("Grade: " + s1.grade)
}
