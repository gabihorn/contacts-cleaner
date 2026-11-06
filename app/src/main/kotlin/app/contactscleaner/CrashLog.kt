package app.contactscleaner

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Keeps the details of the last crash, so the next launch can show them and
 * the user can copy them. Nothing is sent anywhere.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName
                file(app).writeText(
                    "גרסה $version · אנדרואיד ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
                        error.stackTraceToString().take(8000)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }
}
