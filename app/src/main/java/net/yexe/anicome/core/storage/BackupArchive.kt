package net.yexe.anicome

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

internal data class ImportedBackup(val settings: JSONObject, val comments: List<File>)
internal fun readBackupArchive(input: InputStream, directory: File, maximum: Long = 512L * 1024 * 1024, check: () -> Unit = {}): ImportedBackup {
    require(directory.isDirectory && directory.listFiles().orEmpty().isEmpty())
    var manifest: JSONObject? = null
    val names = mutableSetOf<String>()
    val comments = mutableListOf<File>()
    var total = 0L
    ZipInputStream(input).use { zip ->
        while (true) {
            check()
            val entry = zip.nextEntry ?: break
            require(!entry.isDirectory && names.add(entry.name) && names.size <= 2001 && (entry.name == "manifest.json" || Regex("comments/[a-f0-9]{64}\\.json").matches(entry.name))) { "バックアップの形式を確認してください" }
            val file = File.createTempFile("entry-", ".json", directory)
            var size = 0L
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    check()
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n; size += n
                    require(size <= (if (entry.name == "manifest.json") 20 * 1024 * 1024 else CommentStore.MAX_FILE_BYTES) && total <= maximum) { "バックアップが大きすぎます" }
                    output.write(buffer, 0, n)
                }
            }
            if (entry.name == "manifest.json") { manifest = JSONObject(file.readText(Charsets.UTF_8)); file.delete() } else comments += file
            zip.closeEntry()
        }
    }
    val root = checkNotNull(manifest)
    require(root.optString("format") == "AniCome" && root.optInt("version") == 1) { "バックアップの形式を確認してください" }
    return ImportedBackup(root.optJSONObject("settings") ?: JSONObject(), comments)
}
