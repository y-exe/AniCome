package net.yexe.anicome

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class SavedEpisode(val episode: Episode, val sourceId: String, val sourceTitle: String, val savedAt: Long, val count: Int, val bytes: Long, val amount: Int = 1, val thumbnailUrl: String = "", val ownerName: String = "", val ownerUrl: String = "", val sources: String = "OFFICIAL")
data class SavedComments(val info: SavedEpisode, val comments: List<Comment>, val fromArchive: Boolean = false, val slots: List<PlaybackCommentSlot> = emptyList(), val durationMs: Long = 0, val incompleteIds: Set<String> = emptySet(), val recoveryMetadata: String = "", val countLimit: Int = 0)

internal fun SavedComments.isIncompleteSource(id: String) = incompleteIds.any { (niconicoVideoId(it) ?: it) == (niconicoVideoId(id) ?: id) }

internal fun mergeRecoveryComments(previous: List<Comment>, fresh: List<Comment>) = mergeCommentUpdates(previous, fresh)

 
class CommentStore(private val directory: File, private val maxBytes: Long = Long.MAX_VALUE, private val capacity: () -> Long = { maxBytes }, private val index: SavedEpisodeIndex? = null, private val fallback: CommentStore? = null, private val expiresAfter: () -> Long = { Long.MAX_VALUE }, private val clock: () -> Long = System::currentTimeMillis, private val automaticLimit: () -> Long = { 0L }, private val onSaved: (SavedEpisode?, SavedEpisode) -> Unit = { _, _ -> }) {
    val isAutomaticCache get() = fallback != null
    companion object {
        const val MAX_FILE_BYTES = 128 * 1024 * 1024
        fun fileKey(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun file(episode: Episode) = File(directory, "${fileKey(episode.key)}.json")
    @Synchronized fun readOwn(episode: Episode): SavedComments? = readFile(file(episode))
    @Synchronized fun read(episode: Episode): SavedComments? {
        val own = readOwn(episode)
        val manual = fallback?.readForPlayback(episode) ?: return own
        if (own == null) return manual
        fun slots(saved: SavedComments) = saved.slots.ifEmpty { listOf(PlaybackCommentSlot(saved.info.sourceId, saved.info.sourceTitle, PlaybackSource.OFFICIAL, saved.comments, true, saved.durationMs, amount = saved.info.amount, countLimit = saved.countLimit)) }
        val chosen = (slots(manual).map { it to manual.isIncompleteSource(it.id) } + slots(own).map { it to own.isIncompleteSource(it.id) })
            .groupBy { it.first.id }.values.map { values ->
                val chosen = values.maxWith(compareBy<Pair<PlaybackCommentSlot, Boolean>> { !it.second }.thenBy { it.first.amount }.thenBy { it.first.comments.size })
                val comments = values.filter { it.second }.fold(chosen.first.comments) { retained, partial -> mergeRecoveryComments(partial.first.comments, retained) }
                chosen.first.copy(comments = comments) to chosen.second
            }
        val merged = chosen.map { it.first }
        val primary = merged.firstOrNull { it.id == manual.info.sourceId } ?: merged.first()
        return manual.copy(info = manual.info.copy(count = merged.sumOf { it.comments.size }, amount = primary.amount, sources = merged.map { it.source.name }.distinct().joinToString(",")), comments = primary.comments, slots = merged, incompleteIds = chosen.filter { it.second }.map { it.first.id }.toSet())
    }
    @Synchronized fun readForPlayback(episode: Episode): SavedComments? {
        read(episode)?.let { return it }
        return list().filter { it.videoEpisodeNumber() == episode.number && savedEpisodeMatches(episode, Candidate(it.sourceId, it.sourceTitle)) }
            .sortedWith(compareByDescending<SavedEpisode> { it.amount }.thenByDescending { it.savedAt }).firstNotNullOfOrNull { read(it.episode) }
    }
    private fun readFile(file: File): SavedComments? = runCatching {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        val root = JSONObject(readArchiveText(file))
        require(root.getInt("version") in 1..2)
        val episode = Episode(root.getString("series"), root.getInt("episode"))
        require(file.name == this.file(episode).name)
        if (clock() - root.getLong("savedAt") >= expiresAfter()) { delete(episode); return null }
        val slots = readSlots(root)
        val comments = if (root.getInt("version") == 2) slots.firstOrNull { (niconicoVideoId(it.id) ?: it.id) == (niconicoVideoId(root.getString("sourceId")) ?: root.getString("sourceId")) }?.comments ?: error("保存元がありません")
            else parseComments(root.getJSONArray("comments"))
        require(comments.isNotEmpty() && comments.all { it.at >= 0 && it.body.isNotBlank() })
        val recovery = JSONObject().apply { listOf("repairPendingSources", "repairPendingIds", "repairOriginalId", "repairOriginalTitle", "repairAmount").forEach { key -> if (root.has(key)) put(key, root.get(key)) } }
        SavedComments(SavedEpisode(episode, root.getString("sourceId"), root.getString("sourceTitle"), root.getLong("savedAt"), if (slots.isEmpty()) comments.size else slots.sumOf { it.comments.size }, file.length(), root.optInt("amount", 1).coerceAtLeast(1), root.optString("thumbnailUrl"), root.optString("ownerName"), root.optString("ownerUrl"), slots.map { it.source.name }.distinct().joinToString(",").ifBlank { "OFFICIAL" }), comments.sortedBy { it.at }, slots = slots, durationMs = root.optLong("durationMs"), incompleteIds = root.optJSONArray("repairPendingIds")?.let { pending -> (0 until pending.length()).mapNotNull { i -> pending.optJSONObject(i)?.optString("id") }.toSet() }.orEmpty(), recoveryMetadata = if (recovery.length() == 0) "" else recovery.toString(), countLimit = root.optInt("countLimit", 0).coerceAtLeast(0))
    }.getOrNull()
    @Synchronized fun ownList(): List<SavedEpisode> {
        val entries = index?.all() ?: filesList()
        val expired = entries.filter { clock() - it.savedAt >= expiresAfter() }
        expired.forEach { delete(it.episode) }
        return entries - expired.toSet()
    }
    @Synchronized fun list(): List<SavedEpisode> = (ownList() + fallback?.list().orEmpty()).distinctBy { it.episode.key }
    @Synchronized fun purgeExpired() {
        ownList()
        enforceAutomaticLimit()
    }
    @Synchronized fun cleanupInterruptedData() {
        directory.listFiles().orEmpty().forEach { candidate ->
            if (candidate.name.endsWith(".json.tmp") && clock() - candidate.lastModified() >= 3_600_000L) candidate.delete()
            else if (isAutomaticCache && candidate.extension == "json" && readFile(candidate) == null) candidate.delete()
        }
        File(directory, "recovery").listFiles().orEmpty().filter { it.name.endsWith(".tmp") && clock() - it.lastModified() >= 3_600_000L }.forEach { it.delete() }
        if (isAutomaticCache) { enforceAutomaticLimit(); reconcileIndex() }
    }
    @Synchronized fun recoveryBytes(): Long = File(directory, "recovery").listFiles().orEmpty().sumOf { it.length() }
    @Synchronized fun storageSpace(): Long = directory.let { it.mkdirs(); it.usableSpace }
    @Synchronized fun enforceAutomaticLimit(protected: Episode? = null) {
        if (!isAutomaticCache) return
        val limit = automaticLimit().coerceAtLeast(0L)
        if (limit == 0L) return
        val entries = ownList().sortedWith(compareBy<SavedEpisode> { it.savedAt }.thenBy { it.episode.key })
        var used = entries.sumOf { it.bytes }
        for (entry in entries) {
            if (used <= limit) break
            if (entry.episode == protected) continue
            delete(entry.episode)
            used -= entry.bytes
        }
    }
    @Synchronized fun keepCached(episode: Episode): SavedEpisode {
        val destination = checkNotNull(fallback)
        checkNotNull(readOwn(episode))
        val root = destination.validateImport(checkNotNull(exportJson(episode)))
        if (destination.readOwn(episode) == null) root.put("savedAt", clock())
        destination.importJson(root.toString(), upgrade = true)
        val kept = checkNotNull(destination.read(episode))
        val incoming = checkNotNull(readOwn(episode))
        val retained = kept.slots.ifEmpty { listOf(PlaybackCommentSlot(kept.info.sourceId, kept.info.sourceTitle, PlaybackSource.OFFICIAL, kept.comments, amount = kept.info.amount)) }
        val slots = incoming.slots.ifEmpty { listOf(PlaybackCommentSlot(incoming.info.sourceId, incoming.info.sourceTitle, PlaybackSource.OFFICIAL, incoming.comments, amount = incoming.info.amount)) }
        val merged = slots.map { slot ->
            val previous = retained.firstOrNull { it.id == slot.id } ?: return@map slot
            val remaining = previous.comments.groupingBy { it }.eachCount().toMutableMap()
            val additions = slot.comments.filter { comment ->
                val count = remaining[comment] ?: 0
                if (count > 0) { remaining[comment] = count - 1; false } else true
            }
            previous.copy(comments = (previous.comments + additions).sortedBy { it.at }, amount = maxOf(previous.amount, slot.amount))
        }
        require(merged.all { it.comments.size <= Int.MAX_VALUE }) { "1話あたりの保存データが大きすぎます" }
        if (merged.any { fresh -> retained.none { it.id == fresh.id && it.comments == fresh.comments && it.amount == fresh.amount } }) destination.saveSources(episode, merged)
        val result = checkNotNull(destination.read(episode))

        delete(episode)
        return result.info
    }
    private fun commentsJson(comments: List<Comment>) = JSONArray().apply { comments.forEach { put(JSONObject().put("at", it.at).put("body", it.body).put("raw", it.raw).put("fork", it.fork)) } }
    private fun parseComments(rows: JSONArray) = List(rows.length()) { i -> rows.getJSONObject(i).let { Comment(it.getLong("at"), it.getString("body"), if (it.isNull("raw")) null else it.getString("raw"), it.optString("fork", "main")) } }.sortedBy { it.at }
    private fun readSlots(root: JSONObject): List<PlaybackCommentSlot> {
        val rows = root.optJSONArray("sources") ?: return emptyList()
        return List(rows.length()) { i -> rows.getJSONObject(i).let { row -> PlaybackCommentSlot(row.getString("id"), row.getString("title"), PlaybackSource.valueOf(row.getString("source")), parseComments(row.getJSONArray("comments")), true, row.optLong("durationMs"), amount = row.optInt("amount", 1), countLimit = row.optInt("countLimit", 0)) } }
    }
    private fun slotsJson(slots: List<PlaybackCommentSlot>) = JSONArray().apply { slots.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("source", it.source.name).put("durationMs", it.durationMs).put("amount", it.amount).put("countLimit", it.countLimit).put("comments", commentsJson(it.comments))) } }
    @Synchronized fun saveSources(episode: Episode, incoming: List<PlaybackCommentSlot>, requested: Set<PlaybackSource>? = null, confirmedIds: Set<String> = emptySet(), maxComments: Int = 0): SavedComments {
        require(incoming.isNotEmpty() && incoming.any { it.comments.isNotEmpty() })
        val existing = readOwn(episode)
        val manualIds = fallback?.readForPlayback(episode)?.let { saved -> saved.slots.map { it.id }.ifEmpty { listOf(saved.info.sourceId) }.toSet() }.orEmpty()
        val old = existing?.slots?.ifEmpty { listOf(PlaybackCommentSlot(existing.info.sourceId, existing.info.sourceTitle, PlaybackSource.OFFICIAL, existing.comments, true, durationMs = existing.durationMs, amount = existing.info.amount, countLimit = existing.countLimit)) }.orEmpty()
        val slots = old.associateByTo(linkedMapOf()) { it.id }.apply { incoming.filter { it.comments.isNotEmpty() && (!it.fromArchive || it.id !in manualIds) }.forEach { fresh ->
            val partial = get(fresh.id)?.takeIf { existing?.isIncompleteSource(fresh.id) == true }
            put(fresh.id, if (partial == null) fresh else fresh.copy(comments = mergeRecoveryComments(partial.comments, fresh.comments)))
        } }.values.toList()
        if (slots.isEmpty()) return checkNotNull(fallback?.readForPlayback(episode))
        val primary = slots.firstOrNull { it.source == PlaybackSource.OFFICIAL } ?: slots.first()
        val root = JSONObject().put("version", 2).put("series", episode.series).put("episode", episode.number)
            .put("thumbnailUrl", existing?.info?.thumbnailUrl.orEmpty()).put("ownerName", existing?.info?.ownerName.orEmpty()).put("ownerUrl", existing?.info?.ownerUrl.orEmpty())
        root.put("sourceId", primary.id).put("sourceTitle", primary.title).put("amount", primary.amount).put("countLimit", maxComments.takeIf { it > 0 } ?: primary.countLimit)
            .put("savedAt", clock()).put("durationMs", primary.durationMs).put("sources", slotsJson(slots))
        if (root.optString("thumbnailUrl").isBlank()) Comments.knownSource(primary.id)?.thumbnailUrl?.let { root.put("thumbnailUrl", it) }
        if (requested != null) root.put("requestedSources", JSONArray(requested.map { it.name }))
        val verified = confirmedIds + incoming.filter { !it.fromArchive && runCatching { validateSlot(it) }.isSuccess }.map { it.id }
        return writeData(episode, root, confirmedIds = verified, prior = existing)
    }
    private fun writeData(episode: Episode, data: JSONObject, backup: Boolean = true, preserveRecovery: Boolean = true, confirmedIds: Set<String> = emptySet(), prior: SavedComments? = null): SavedComments {
        val previousData = prior ?: readOwn(episode)
        val previous = previousData?.info ?: index?.get(episode.key)
        directory.mkdirs()
        val target = file(episode)
        if (preserveRecovery) {
            val previousRoot = if (previousData != null) previousData.recoveryMetadata.takeIf(String::isNotBlank)?.let(::JSONObject)
                else if (target.isFile && target.length() <= MAX_FILE_BYTES) runCatching { JSONObject(readArchiveText(target)) }.getOrNull() else null
            previousRoot?.let { root -> listOf("repairPendingSources", "repairPendingIds", "repairOriginalId", "repairOriginalTitle", "repairAmount").forEach { key -> if (!data.has(key) && root.has(key)) data.put(key, root.get(key)) } }
        }
        if (confirmedIds.isNotEmpty()) {
            val ids = confirmedIds.map { niconicoVideoId(it) ?: it }.toSet()
            data.optJSONArray("repairPendingIds")?.let { pending ->
                val old = (0 until pending.length()).map { pending.getJSONObject(it) }
                val sourceRows = data.optJSONArray("sources")
                val amounts = if (sourceRows == null) mapOf((niconicoVideoId(data.optString("sourceId")) ?: data.optString("sourceId")) to data.optInt("amount", 1))
                    else (0 until sourceRows.length()).associate { i -> sourceRows.getJSONObject(i).let { row -> (niconicoVideoId(row.getString("id")) ?: row.getString("id")) to row.optInt("amount", 1) } }
                val remaining = old.filter { row ->
                    val id = niconicoVideoId(row.optString("id")) ?: row.optString("id")
                    id !in ids || (amounts[id] ?: 0) < row.optInt("amount", 1)
                }
                data.put("repairPendingIds", JSONArray(remaining))
                val pendingKinds = remaining.map { it.optString("source") }.toSet()
                val repairedKinds = old.filter { (niconicoVideoId(it.optString("id")) ?: it.optString("id")) in ids }.map { it.optString("source") }.toSet() - pendingKinds
                data.optJSONArray("repairPendingSources")?.let { kinds -> data.put("repairPendingSources", JSONArray((0 until kinds.length()).map { kinds.getString(it) }.filter { it !in repairedKinds })) }
                if (remaining.isEmpty() && (data.optJSONArray("repairPendingSources")?.length() ?: 0) == 0) listOf("repairPendingSources", "repairPendingIds", "repairOriginalId", "repairOriginalTitle", "repairAmount").forEach(data::remove)
            }
        }
        val temporary = File(directory, "${target.name}.tmp")
        try {
            temporary.outputStream().use { output ->
                val writer = output.bufferedWriter(Charsets.UTF_8)
                writeJson(writer, data)
                writer.flush()
                output.fd.sync()
            }
            val bytes = temporary.length()
            if (isAutomaticCache && automaticLimit() > 0L) check(bytes <= automaticLimit()) { "キャッシュ容量の上限より大きいため保存できません" }
            require(bytes <= MAX_FILE_BYTES) { "1話あたりの保存データが大きすぎます" }
            val used = directory.listFiles().orEmpty().filter { it.extension == "json" && it != target }.sumOf { it.length() }
            check(used + bytes <= capacity()) { "保存容量の上限に達しました。上限を変更するか、保存済みのコメントを削除してください" }
            check(directory.usableSpace > 5L * 1024 * 1024) { "端末の空き容量が不足しています" }
            if (backup && !isAutomaticCache && target.isFile && (previousData == null || previousData.slots.any { runCatching { validateSlot(it) }.isFailure })) backupArchive(target)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        return checkNotNull(readOwn(episode)).also { index?.upsert(it.info); enforceAutomaticLimit(episode); onSaved(previous, it.info) }
    }
    private fun writeJson(writer: java.io.Writer, value: Any?) {
        when (value) {
            is JSONObject -> { writer.write("{"); value.keys().asSequence().forEachIndexed { i, key -> if (i > 0) writer.write(","); writer.write(JSONObject.quote(key)); writer.write(":"); writeJson(writer, value.get(key)) }; writer.write("}") }
            is JSONArray -> { writer.write("["); for (i in 0 until value.length()) { if (i > 0) writer.write(","); writeJson(writer, value.get(i)) }; writer.write("]") }
            is String -> writer.write(JSONObject.quote(value))
            null, JSONObject.NULL -> writer.write("null")
            else -> writer.write(value.toString())
        }
    }
    private fun filesList(): List<SavedEpisode> = directory.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { readFile(it)?.info }.sortedByDescending { it.savedAt }
    @Synchronized fun save(episode: Episode, candidate: Candidate, comments: List<Comment>, amount: Int = 1, savedAt: Long = clock(), countLimit: Int = 0): SavedComments {
        require(comments.isNotEmpty()) { "空のコメントは保存できません" }
        directory.mkdirs()
        val previous = readOwn(episode)
        val commentsToSave = if (candidate.id in previous?.incompleteIds.orEmpty()) mergeRecoveryComments(previous!!.slots.firstOrNull { it.id == candidate.id }?.comments ?: previous.comments, comments) else comments
        val data = JSONObject().put("version", 1).put("series", episode.series).put("episode", episode.number)
            .put("sourceId", niconicoVideoId(candidate.id) ?: candidate.id).put("sourceTitle", candidate.title).put("savedAt", savedAt.coerceAtLeast(0)).put("amount", amount.coerceAtLeast(1)).put("countLimit", countLimit).put("durationMs", candidate.duration * 1000).put("thumbnailUrl", candidate.thumbnailUrl).put("ownerName", candidate.ownerName).put("ownerUrl", candidate.ownerUrl)
            .put("comments", JSONArray().apply { commentsToSave.forEach { put(JSONObject().put("at", it.at).put("body", it.body).put("raw", it.raw).put("fork", it.fork)) } })
        val oldSlots = previous?.slots.orEmpty()
        if (oldSlots.isNotEmpty()) {
            data.put("version", 2); data.remove("comments")
            val retained = oldSlots.filter { it.id != candidate.id && !(it.source == PlaybackSource.OFFICIAL && it.id == previous?.info?.sourceId) }
            data.put("sources", slotsJson(listOf(PlaybackCommentSlot(candidate.id, candidate.title, PlaybackSource.OFFICIAL, commentsToSave, durationMs = candidate.duration * 1000, amount = amount, countLimit = countLimit)) + retained))
        }
        val verified = runCatching { validateSlot(PlaybackCommentSlot(candidate.id, candidate.title, PlaybackSource.OFFICIAL, commentsToSave, durationMs = candidate.duration * 1000, amount = amount, countLimit = countLimit)) }.isSuccess
        return writeData(episode, data, confirmedIds = if (verified) setOf(candidate.id) else emptySet(), prior = previous)
    }
    @Synchronized fun updateSource(episode: Episode, expectedId: String, source: Candidate): SavedEpisode? {
        val target = file(episode)
        if (!target.isFile) return null
        val data = JSONObject(readArchiveText(target))
        if (data.optString("sourceId") != expectedId) return null
        data.put("sourceId", niconicoVideoId(source.id) ?: source.id)
        if (source.duration > 0) data.put("durationMs", source.duration * 1000)
        if (source.title.isNotBlank()) data.put("sourceTitle", source.title)
        if (source.thumbnailUrl.isNotBlank()) data.put("thumbnailUrl", source.thumbnailUrl)
        if (source.ownerName.isNotBlank()) data.put("ownerName", source.ownerName)
        if (source.ownerUrl.isNotBlank()) data.put("ownerUrl", source.ownerUrl)
        val slots = readSlots(data)
        if (slots.isNotEmpty()) data.put("sources", slotsJson(slots.map { if (it.id == expectedId) it.copy(id = source.id, title = source.title.ifBlank { it.title }, durationMs = source.duration.takeIf { duration -> duration > 0 }?.times(1000) ?: it.durationMs) else it }))
        return writeData(episode, data).info
    }
    @Synchronized fun readSource(input: String): SavedComments? {
        val id = niconicoVideoId(input) ?: input
        val own = directory.listFiles { f -> f.extension == "json" }.orEmpty().asSequence().mapNotNull { readFile(it) }.mapNotNull { saved ->
            if (saved.incompleteIds.any { (niconicoVideoId(it) ?: it) == id }) return@mapNotNull null
            val slot = saved.slots.firstOrNull { (niconicoVideoId(it.id) ?: it.id) == id }
            if (slot != null) saved.copy(info = saved.info.copy(sourceId = slot.id, sourceTitle = slot.title, count = slot.comments.size, amount = slot.amount, sources = slot.source.name), comments = slot.comments, slots = listOf(slot), durationMs = slot.durationMs, countLimit = slot.countLimit)
            else saved.takeIf { it.slots.isEmpty() && (niconicoVideoId(it.info.sourceId) ?: it.info.sourceId) == id }
        }.maxWithOrNull(compareBy<SavedComments> { it.info.amount }.thenBy { it.info.savedAt })
        return listOfNotNull(own, fallback?.readSource(input)).maxWithOrNull(compareBy<SavedComments> { it.info.amount }.thenBy { it.comments.size })
    }
    @Synchronized fun reconcileIndex() {
        val valid = filesList()
        index?.replace(if (isAutomaticCache) valid else (valid + index.all()).distinctBy { it.episode.key })
    }
    @Synchronized fun exportJson(episode: Episode): String? = file(episode).takeIf { it.isFile }?.let(::readArchiveText)
    fun validateImport(text: String): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES)
        val j = JSONObject(text)
        require(j.getInt("version") in 1..2 && j.getString("series").length in 1..300 && j.getInt("episode") in 0..9999)
        val slots = readSlots(j)
        require(niconicoVideoId(j.getString("sourceId")) != null || slots.any { it.id == j.getString("sourceId") })
        require(slots.size <= 32 && slots.map { it.id }.distinct().size == slots.size)
        slots.forEach(::validateSlot)
        val comments = if (j.getInt("version") == 2) slots.firstOrNull { (niconicoVideoId(it.id) ?: it.id) == (niconicoVideoId(j.getString("sourceId")) ?: j.getString("sourceId")) }?.comments ?: error("保存元がありません") else parseComments(j.getJSONArray("comments"))
        require(comments.size in 1..Int.MAX_VALUE && comments.all { it.at in 0..86_400_000 && it.body.isNotBlank() && it.body.length <= 16000 })
        return j
    }
    @Synchronized fun importJson(text: String, upgrade: Boolean = false) {
        val j = validateImport(text)
        val episode = Episode(j.getString("series"), j.getInt("episode"))
        val existing = read(episode)
        if (existing != null) {
            val old = existing.slots.ifEmpty { listOf(PlaybackCommentSlot(existing.info.sourceId, existing.info.sourceTitle, PlaybackSource.OFFICIAL, existing.comments, amount = existing.info.amount, countLimit = existing.countLimit)) }.associateBy { it.id }
            val incoming = readSlots(j).ifEmpty { if (upgrade) listOf(PlaybackCommentSlot(j.getString("sourceId"), j.getString("sourceTitle"), PlaybackSource.OFFICIAL, parseComments(j.getJSONArray("comments")), durationMs = j.optLong("durationMs"), amount = j.optInt("amount", 1))) else emptyList() }
            val fresh = incoming.filter { slot -> old[slot.id]?.let { previous -> upgrade && (slot.amount > previous.amount || (slot.amount == previous.amount && (existing.isIncompleteSource(slot.id) || slot.comments.size > previous.comments.size))) } ?: true }
            if (fresh.isNotEmpty()) {
                val pending = j.optJSONArray("repairPendingIds")?.let { rows -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("id") }.toSet() }.orEmpty()
                saveSources(episode, fresh, confirmedIds = fresh.map { it.id }.filter { it !in pending }.toSet())
            }
            return
        }
        writeData(episode, j)
    }
    @Synchronized fun relabel(from: Episode, to: Episode) {
        if (from.key == to.key) return
        check(!file(to).exists() && index?.get(to.key) == null && read(to) == null) { "修正先には保存済みのコメントがあります" }
        checkNotNull(readOwn(from))
        val root = JSONObject(checkNotNull(exportJson(from))).put("series", to.series).put("episode", to.number)
        val copied = mutableListOf<File>()
        try {
            File(directory, "recovery").listFiles().orEmpty().filter { it.name.startsWith("${file(from).name}.") && it.name.endsWith(".bak") }
                .forEach { copied += backupArchive(it, file(to).name) }
            importJson(root.toString())
        } catch (e: Exception) {
            if (file(from).isFile) runCatching { delete(to) }
            copied.forEach { it.delete() }
            throw e
        }
        delete(from)
    }
    @Synchronized fun delete(episode: Episode) {
        val target = file(episode)
        check(!target.exists() || target.delete()) { "削除できませんでした" }
        File(directory, "recovery").listFiles().orEmpty().filter { it.name.startsWith("${target.name}.") }.forEach { check(it.delete()) { "復旧用ファイルを削除できませんでした" } }
        index?.delete(episode.key)
    }
    private fun readArchiveText(target: File): String = target.inputStream().use { input ->
        java.io.InputStreamReader(input, Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)).use { it.readText() }
    }
    private fun validateSlot(slot: PlaybackCommentSlot) {
        require(slot.id.length in 1..1000 && slot.durationMs in 0..86_400_000 && slot.amount >= 1 && slot.countLimit >= 0 && slot.comments.isNotEmpty())
        require(slot.comments.all { it.at in 0..86_400_000 && it.body.isNotBlank() && it.body.length <= 16000 })
    }
    private fun backupArchive(target: File, ownerName: String = target.name): File {
        val folder = File(directory, "recovery").apply { check(isDirectory || mkdirs()) }
        val backup = File(folder, "${ownerName}.${java.util.UUID.randomUUID()}.bak")
        val temporary = File(folder, "${backup.name}.tmp")
        try {
            temporary.outputStream().use { output -> target.inputStream().use { it.copyTo(output) }; output.fd.sync() }
            Files.move(temporary.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
        return backup
    }
    fun inspectArchives(check: () -> Unit = {}): List<ArchiveCheck> {
        val known = index?.all().orEmpty().associateBy { file(it.episode).name }
        return (directory.listFiles { candidate -> candidate.extension == "json" }.orEmpty().map { it.name } + known.keys).distinct().map { name -> check(); synchronized(this) { inspectArchive(File(directory, name), known[name]) } }
    }
    @Synchronized fun inspectEpisode(episode: Episode) = inspectArchive(file(episode), index?.get(episode.key))
    private fun inspectArchive(target: File, known: SavedEpisode?): ArchiveCheck {
        if (!target.isFile) return ArchiveCheck(target.name, known, "missing")
        var info = known
        return try {
            require(target.length() <= MAX_FILE_BYTES)
            val root = JSONObject(readArchiveText(target))
            val episode = Episode(root.getString("series"), root.getInt("episode"))
            require(episode.series.isNotBlank() && episode.series.length <= 300 && episode.number in 0..9999 && file(episode).name == target.name)
            if (info != null) require(info.episode == episode)
            info = info ?: SavedEpisode(episode, root.optString("sourceId"), root.optString("sourceTitle"), root.optLong("savedAt"), 0, target.length(), root.optInt("amount", 1).coerceAtLeast(1), root.optString("thumbnailUrl"), root.optString("ownerName"), root.optString("ownerUrl"))
            val version = root.getInt("version")
            if (version !in 1..2) return ArchiveCheck(target.name, info, "unsupported")
            info = info.copy(amount = root.optInt("repairAmount", info.amount).coerceAtLeast(1))
            val broken = mutableListOf<BrokenArchiveSource>()
            val valid = mutableSetOf<PlaybackSource>()
            val damaged = mutableSetOf<PlaybackSource>()
            if (version == 1) {
                try { validateImport(root.toString()); require(root.getLong("savedAt") >= 0); valid += PlaybackSource.OFFICIAL }
                catch (_: Exception) { damaged += PlaybackSource.OFFICIAL; broken += BrokenArchiveSource(PlaybackSource.OFFICIAL, root.optString("sourceId"), root.optString("sourceTitle"), amount = info.amount) }
            } else {
                val rows = root.getJSONArray("sources")
                if (rows.length() !in 1..32) return ArchiveCheck(target.name, info, "corrupt")
                val ids = mutableSetOf<String>()
                for (n in 0 until rows.length()) {
                    val row = rows.optJSONObject(n)
                    val kind = row?.optString("source")?.let { runCatching { PlaybackSource.valueOf(it) }.getOrNull() } ?: return ArchiveCheck(target.name, info, "unsupported")
                    try { val slot = readSlots(JSONObject().put("sources", JSONArray().put(row))).single(); validateSlot(slot); require(ids.add(slot.id)); valid += kind }
                    catch (_: Exception) { if (row?.optString("id").isNullOrBlank()) return ArchiveCheck(target.name, info, "unsupported"); damaged += kind; broken += BrokenArchiveSource(kind, row!!.optString("id"), row.optString("title"), row.optLong("durationMs"), row.optInt("amount", 1).coerceAtLeast(1)) }
                }
                require(root.getLong("savedAt") >= 0)
                if (root.getString("sourceId") !in ids && broken.none { it.id == root.getString("sourceId") }) { damaged += PlaybackSource.OFFICIAL; broken += BrokenArchiveSource(PlaybackSource.OFFICIAL, root.getString("sourceId"), root.optString("sourceTitle"), amount = info.amount) }
            }
            val pending = root.optJSONArray("repairPendingIds")
            val pendingSources = mutableSetOf<PlaybackSource>()
            if (pending != null) for (n in 0 until pending.length()) {
                val row = pending.getJSONObject(n)
                val kind = PlaybackSource.valueOf(row.getString("source"))
                pendingSources += kind
                damaged += kind; broken += BrokenArchiveSource(kind, row.getString("id"), row.optString("title"), row.optLong("durationMs"), row.optInt("amount", 1))
            }
            root.optJSONArray("repairPendingSources")?.let { missing -> for (n in 0 until missing.length()) PlaybackSource.valueOf(missing.getString(n)).let { if (it !in valid && it !in pendingSources) damaged += it } }
            ArchiveCheck(target.name, info, if (damaged.isEmpty()) "healthy" else "partial", valid, damaged, root.optString("repairOriginalId", root.optString("sourceId")), root.optString("repairOriginalTitle", root.optString("sourceTitle")), broken.distinctBy { it.id })
        } catch (_: Exception) { ArchiveCheck(target.name, info, "corrupt") }
    }
    @Synchronized fun salvageArchive(check: ArchiveCheck) {
        val episode = check.info?.episode ?: return
        val target = file(episode)
        if (!target.isFile || check.status != "partial" || target.length() > MAX_FILE_BYTES) return
        val root = JSONObject(readArchiveText(target))
        val rows = when (root.optInt("version")) {
            1 -> JSONArray().put(JSONObject().put("id", root.optString("sourceId")).put("title", root.optString("sourceTitle")).put("source", "OFFICIAL").put("amount", root.optInt("amount", 1)).put("durationMs", root.optLong("durationMs")).put("comments", root.getJSONArray("comments")))
            2 -> root.getJSONArray("sources")
            else -> return
        }
        val valid = (0 until rows.length()).mapNotNull { n ->
            rows.optJSONObject(n)?.let { row ->
                val kind = runCatching { PlaybackSource.valueOf(row.getString("source")) }.getOrNull() ?: return@let null
                val id = row.optString("id").takeIf { it.length in 1..1000 } ?: return@let null
                val comments = row.optJSONArray("comments") ?: return@let null
                val intact = (0 until comments.length()).mapNotNull { i ->
                    runCatching { parseComments(JSONArray().put(comments.get(i))).single() }.getOrNull()?.takeIf { it.at in 0..86_400_000L && it.body.isNotBlank() && it.body.length <= 16000 }
                }
                if (intact.isEmpty()) return@let null
                JSONObject().put("id", id).put("title", row.optString("title")).put("source", kind.name).put("comments", commentsJson(intact))
                    .put("durationMs", row.optLong("durationMs").coerceIn(0, 86_400_000)).put("amount", row.optInt("amount", 1).coerceAtLeast(1))
            }
        }
        if (valid.isEmpty()) return
        val unique = valid.distinctBy { it.getString("id") }
        val primary = unique.firstOrNull { it.optString("source") == "OFFICIAL" } ?: unique.first()
        backupArchive(target)
        if (root.optString("sourceId") != primary.getString("id")) root.put("thumbnailUrl", "").put("ownerName", "").put("ownerUrl", "")
        root.put("version", 2); root.remove("comments")
        root.put("sourceId", primary.getString("id")).put("sourceTitle", primary.getString("title")).put("amount", primary.optInt("amount", 1)).put("durationMs", primary.optLong("durationMs"))
            .put("sources", JSONArray(unique)).put("repairPendingSources", JSONArray(check.damaged.map { it.name })).put("repairOriginalId", check.originalId).put("repairOriginalTitle", check.originalTitle).put("repairAmount", check.info.amount).put("repairPendingIds", brokenSourcesJson(check.broken))
        writeData(episode, root, backup = false)
    }
    private fun brokenSourcesJson(sources: List<BrokenArchiveSource>) = JSONArray().apply { sources.forEach { put(JSONObject().put("source", it.source.name).put("id", it.id).put("title", it.title).put("durationMs", it.duration).put("amount", it.amount)) } }
    @Synchronized fun finishRecovery(episode: Episode, wanted: Set<PlaybackSource>, originalId: String, originalTitle: String, expected: List<BrokenArchiveSource>, verified: Set<String>) {
        val saved = readOwn(episode) ?: return
        val present = saved.slots.map { it.source }.toSet().ifEmpty { setOf(PlaybackSource.OFFICIAL) }
        val confirmed = verified.map { niconicoVideoId(it) ?: it }.toSet()
        val missingIds = expected.filter { (niconicoVideoId(it.id) ?: it.id) !in confirmed }
        val remaining = (wanted - present) + missingIds.map { it.source }
        val metadata = saved.recoveryMetadata.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
        val keys = listOf("repairPendingSources", "repairPendingIds", "repairOriginalId", "repairOriginalTitle", "repairAmount")
        if (remaining.isEmpty()) keys.forEach(metadata::remove)
        else metadata.put("repairPendingSources", JSONArray(remaining.map { it.name })).put("repairOriginalId", originalId).put("repairOriginalTitle", originalTitle).put("repairPendingIds", brokenSourcesJson(missingIds))
        val encoded = if (metadata.length() == 0) "" else metadata.toString()
        if (encoded == saved.recoveryMetadata) return
        val root = JSONObject(checkNotNull(exportJson(episode)))
        keys.forEach(root::remove)
        metadata.keys().forEach { key -> root.put(key, metadata.get(key)) }
        writeData(episode, root, preserveRecovery = false, prior = saved)
    }

}
