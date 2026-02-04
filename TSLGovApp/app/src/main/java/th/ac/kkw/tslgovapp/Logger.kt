package th.ac.kkw.tslgovapp

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.KClass



object Logger {
    private const val TAG = "TSLLogger"

    fun saveLogcat(context: Context, keyword: String? = null) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val keywordPart = if (keyword != null) "_${keyword}" else ""
        val fileName = "logcat${keywordPart}_$timestamp.txt"

        try {
            // Capture logcat output
            val cmd = if (keyword != null) {
                "logcat -d -v time *:D | grep '$keyword'"
            } else {
                "logcat -d -v time *:D"
            }
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val logContent = process.inputStream.bufferedReader().use { it.readText() }

            // Save to Downloads folder using MediaStore (Android 10+ compatible)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, fileName, logContent)
            } else {
                saveDirectly(context, fileName, logContent)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to save logcat", e)
            Toast.makeText(
                context,
                "Failed to save log: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveViaMediaStore(context: Context, fileName: String, content: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TSLGovAppLogs")
        }

        val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            resolver.openOutputStream(it)?.use { output ->
                output.write(content.toByteArray())
                Log.d(TAG, "✅ Log saved to: Downloads/TSLGovAppLogs/$fileName")
                Toast.makeText(
                    context,
                    "Log saved to Downloads/TSLGovAppLogs",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun saveDirectly(context: Context, fileName: String, content: String) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val logDir = File(downloadsDir, "TSLGovAppLogs")
        if (!logDir.exists()) logDir.mkdirs()

        val file = File(logDir, fileName)
        FileOutputStream(file).use { output ->
            output.write(content.toByteArray())
            Log.d(TAG, "✅ Log saved to: ${file.absolutePath}")
            Toast.makeText(
                context,
                "Log saved to Downloads/TSLGovAppLogs",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Convenience: auto-detect calling class
    fun saveLogcatFor(context: Context, clazz: KClass<*>)  {
        saveLogcat(context, clazz.simpleName)
    }
}