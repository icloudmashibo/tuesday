package com.mashibo.tuesday

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import java.io.File

/** Sorts loose files in Downloads into folders by type. Never deletes, never touches subfolders, always undoable. */
object FileOrganizer {
    private val groups = linkedMapOf(
        "Images" to setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp"),
        "Videos" to setOf("mp4", "mkv", "mov", "avi", "webm", "3gp"),
        "Audio" to setOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "opus"),
        "Documents" to setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "epub", "md"),
        "Archives" to setOf("zip", "rar", "7z", "tar", "gz"),
        "APKs" to setOf("apk", "xapk", "apks"),
        "Code" to setOf("py", "kt", "java", "js", "json", "html", "sh", "gradle", "kts")
    )

    private fun root(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    fun plan(): List<Pair<File, File>> {
        val out = ArrayList<Pair<File, File>>()
        val now = System.currentTimeMillis()
        val files = root().listFiles() ?: return out
        for (f in files) {
            if (!f.isFile || f.name.startsWith(".")) continue
            if (now - f.lastModified() < 5 * 60_000) continue // probably still downloading
            val ext = f.extension.lowercase()
            val group = groups.entries.firstOrNull { ext in it.value }?.key ?: continue
            out.add(f to unique(File(File(root(), group), f.name)))
        }
        return out
    }

    private fun unique(dest: File): File {
        if (!dest.exists()) return dest
        var i = 1
        while (true) {
            val c = File(dest.parentFile, "${dest.nameWithoutExtension} ($i)" + if (dest.extension.isEmpty()) "" else ".${dest.extension}")
            if (!c.exists()) return c
            i++
        }
    }

    fun run(ctx: Context): String {
        val moves = plan()
        if (moves.isEmpty()) return "Nothing to sort. Downloads is already tidy."
        val log = JSONArray()
        var done = 0
        for ((src, dst) in moves) {
            dst.parentFile?.mkdirs()
            if (src.renameTo(dst)) {
                log.put(JSONArray().put(src.absolutePath).put(dst.absolutePath))
                done++
            }
        }
        File(ctx.filesDir, "last_move.json").writeText(log.toString())
        return "Done. Sorted $done files. Say undo if you want them back."
    }

    fun undo(ctx: Context): String {
        val f = File(ctx.filesDir, "last_move.json")
        if (!f.exists()) return "There's nothing to undo."
        val log = JSONArray(f.readText())
        var back = 0
        for (i in 0 until log.length()) {
            val pair = log.getJSONArray(i)
            val src = File(pair.getString(0))
            val dst = File(pair.getString(1))
            if (dst.exists() && !src.exists() && dst.renameTo(src)) back++
        }
        f.delete()
        return "Put $back files back where they were."
    }
}
