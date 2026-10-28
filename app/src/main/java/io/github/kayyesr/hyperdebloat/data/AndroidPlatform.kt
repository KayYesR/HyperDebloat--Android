package io.github.kayyesr.hyperdebloat.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.graphics.drawable.toBitmap
import io.github.kayyesr.hyperdebloat.adb.AdbManager
import io.github.kayyesr.hyperdebloat.core.Fetcher
import io.github.kayyesr.hyperdebloat.core.Shell
import io.github.kayyesr.hyperdebloat.core.Storage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Work folder inside the app (the PowerShell Documents\HyperDebloat). Reports are also copied to Documents/HyperDebloat. */
class AndroidStorage(private val context: Context) : Storage {

    val root: File = File(context.filesDir, "HyperDebloat").apply { mkdirs() }
    private val exported = ConcurrentHashMap<String, Uri>()

    fun file(path: String) = File(root, path)

    override fun readText(path: String): String? = file(path).takeIf { it.isFile }?.readText()

    override fun writeText(path: String, text: String, export: Boolean) {
        val f = file(path)
        f.parentFile?.mkdirs()
        f.writeText(text)
        if (export) exportCopy(path, text)
    }

    override fun appendText(path: String, text: String) {
        val f = file(path)
        f.parentFile?.mkdirs()
        f.appendText(text)
    }

    override fun exists(path: String) = file(path).exists()
    override fun lastModified(path: String) = file(path).lastModified()

    override fun move(from: String, to: String) {
        val t = file(to)
        t.delete()
        file(from).renameTo(t)
    }

    override fun delete(path: String) { file(path).delete() }

    override fun listDirs(): List<String> = root.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    override fun listFiles(dir: String): List<String> = file(dir).listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()

    /** Copies a report to Documents/HyperDebloat/<phone folder>/ so it can be opened from a file manager. */
    private fun exportCopy(path: String, text: String) {
        try {
            val resolver = context.contentResolver
            val uri = exported[path] ?: run {
                val name = path.substringAfterLast('/')
                val dir = path.substringBeforeLast('/', "")
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".csv")) "text/csv" else "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/HyperDebloat" + if (dir.isNotEmpty()) "/$dir" else "")
                }
                resolver.insert(MediaStore.Files.getContentUri("external"), values)?.also { exported[path] = it }
            } ?: return
            resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
        } catch (_: Exception) {
            // The private copy is what the app uses; the Documents copy is a convenience.
        }
    }
}

/** Runs commands over wireless debugging. With [saveRaw], output is also written to raw_adb_output.txt. */
class AdbShell(private val adb: AdbManager, private val storage: AndroidStorage, private val saveRaw: () -> Boolean) : Shell {
    override fun stream(command: String, onLine: (String) -> Unit) {
        if (!saveRaw()) { adb.shell(command, onLine); return }
        val raw = StringBuilder("##### adb shell $command\n")
        adb.shell(command) { raw.append(it).append('\n'); onLine(it) }
        storage.appendText("raw_adb_output.txt", raw.append('\n').toString())
    }
}

class HttpFetcher : Fetcher {
    override fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "HyperDebloat-Android")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** App names and icons from the package manager, for display only (ratings never use them). */
class AppInfoCache(context: Context) {
    private val pm = context.packageManager
    private val labels = ConcurrentHashMap<String, String>()
    private val icons = ConcurrentHashMap<String, Any>() // Bitmap, or NONE
    private val none = Any()
    private val sizePx = (40 * context.resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun info(pkg: String) = try {
        pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
    } catch (_: Exception) {
        null
    }

    fun label(pkg: String): String = labels.getOrPut(pkg) {
        info(pkg)?.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() && it != pkg } ?: ""
    }

    fun icon(pkg: String): Bitmap? {
        val v = icons.getOrPut(pkg) {
            try { info(pkg)?.loadIcon(pm)?.toBitmap(sizePx, sizePx) ?: none } catch (_: Exception) { none }
        }
        return v as? Bitmap
    }
}
