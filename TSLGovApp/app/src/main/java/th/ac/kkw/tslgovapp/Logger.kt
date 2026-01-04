package th.ac.kkw.tslgovapp

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.KClass
import android.content.Context



object Logger {
    private const val TAG = "TSLLogger"

    fun saveLogcat(context: Context, keyword: String? = null) {
        val logDir = context.getExternalFilesDir("logs") ?: return
        if (!logDir.exists()) logDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val keywordPart = if (keyword != null) "_${keyword}" else ""
        val file = File(logDir, "logcat${keywordPart}_timestamp.txt")
        try {
            val cmd = if (keyword != null) {
                "logcat -d -v time | grep '$keyword'"
            } else {
                "logcat -d -v time"
            }
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            process.inputStream.copyTo(file.outputStream())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save logcat", e)
        }
    }

    // Convenience: auto-detect calling class
    fun saveLogcatFor(context: Context, clazz: KClass<*>)  {
        saveLogcat(context, clazz.simpleName)
    }
}