package dev.jarvis.assistant.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface AddResult {
    data class Saved(val memory: Memory) : AddResult
    data class Updated(val memory: Memory) : AddResult
    data object Duplicate : AddResult
    data class Blocked(val reason: String) : AddResult
    data class Failed(val message: String) : AddResult
}

/** What happened to one chat message. Counts only; never the text. */
data class ObserveResult(val saved: Int = 0, val updated: Int = 0, val duplicates: Int = 0, val proposed: Int = 0, val blocked: Int = 0)

/**
 * Everything the app does with memories. Every public call is safe: a database problem is caught and turned into a
 * harmless result, so chat never fails because of memory. All storage work runs off the main thread.
 * Memory text is never logged.
 */
class MemoryService(
    private val storageProvider: () -> MemoryStorage,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private var cached: MemoryStorage? = null

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())

    /** Newest first. ACTIVE and PENDING together; use [active] and [pending] to split. */
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    private fun storage(): MemoryStorage = cached ?: storageProvider().also { cached = it }

    /** Runs [block] on the IO dispatcher under the lock. On any failure the cached storage is dropped so the next call retries. */
    private suspend fun <T> safe(default: T, block: (MemoryStorage) -> T): T = try {
        withContext(io) { lock.withLock { block(storage()) } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        cached = null
        default
    }

    private fun publish(s: MemoryStorage) {
        _memories.value = s.all()
    }

    suspend fun refresh() { safe(Unit) { publish(it) } }

    suspend fun active(): List<Memory> = safe(emptyList()) { s -> s.all().filter { it.status == MemoryStatus.ACTIVE } }

    suspend fun pending(): List<Memory> = safe(emptyList()) { s -> s.all().filter { it.status == MemoryStatus.PENDING } }

    // ------------------------------------------------------------------ automatic extraction

    /** Looks at one user message and saves, updates or proposes memories. Never throws. */
    suspend fun observe(userText: String): ObserveResult {
        val candidates = MemoryExtractor.extract(userText)
        if (candidates.isEmpty()) return ObserveResult()
        return safe(ObserveResult()) { s ->
            var r = ObserveResult()
            for (c in candidates) r = applyCandidate(s, c).let { add(r, it) }
            publish(s)
            r
        }
    }

    private fun add(a: ObserveResult, b: ObserveResult) =
        ObserveResult(a.saved + b.saved, a.updated + b.updated, a.duplicates + b.duplicates, a.proposed + b.proposed, a.blocked + b.blocked)

    private fun applyCandidate(s: MemoryStorage, c: Candidate): ObserveResult {
        val all = s.all()
        val now = clock()
        val verdict = SensitivityGuard.check(c.content)
        if (verdict.level == Sensitivity.BLOCKED) return ObserveResult(blocked = 1)

        val active = all.filter { it.status == MemoryStatus.ACTIVE }
        val existing = findExisting(active, c)

        // Uncertain statements are never saved and never overwrite anything. If they disagree with a saved fact the
        // user is asked, otherwise they are simply ignored.
        if (c.uncertain) {
            if (existing != null && !same(existing, c)) {
                return propose(s, all, c, "you sounded unsure; update the saved fact?", existing.id, now)
            }
            return ObserveResult()
        }
        if (verdict.level == Sensitivity.NEEDS_APPROVAL) {
            return propose(s, all, c, verdict.reason ?: "it may be sensitive", existing?.id, now)
        }
        if (existing != null) {
            if (same(existing, c)) {
                s.upsert(existing.copy(updatedAt = now))
                return ObserveResult(duplicates = 1)
            }
            s.upsert(existing.copy(content = c.content, confidence = maxOf(existing.confidence, c.confidence), updatedAt = now))
            return ObserveResult(updated = 1)
        }
        if (active.size >= MemoryLimits.MAX_MEMORIES) return ObserveResult()
        s.upsert(newMemory(c, MemorySource.AUTO, MemoryStatus.ACTIVE, now))
        return ObserveResult(saved = 1)
    }

    private fun propose(s: MemoryStorage, all: List<Memory>, c: Candidate, reason: String, replacesId: String?, now: Long): ObserveResult {
        val pending = all.filter { it.status == MemoryStatus.PENDING }
        if (pending.any { MemoryMatcher.similarity(it.content, c.content) >= 0.85 }) return ObserveResult(duplicates = 1)
        if (pending.size >= MemoryLimits.MAX_PENDING) return ObserveResult()
        s.upsert(newMemory(c, MemorySource.AUTO, MemoryStatus.PENDING, now).copy(reason = reason, replacesId = replacesId))
        return ObserveResult(proposed = 1)
    }

    private fun newMemory(c: Candidate, source: MemorySource, status: MemoryStatus, now: Long) = Memory(
        id = newId(), category = c.category, key = c.key, content = c.content.take(MemoryLimits.MAX_CONTENT_CHARS),
        source = source, confidence = c.confidence, status = status, createdAt = now, updatedAt = now,
    )

    /** The record this candidate is about: same subject key, else a near-identical sentence in the same category. */
    private fun findExisting(active: List<Memory>, c: Candidate): Memory? {
        if (c.key != null) active.firstOrNull { it.key == c.key }?.let { return it }
        return active.firstOrNull { it.category == c.category && MemoryMatcher.similarity(it.content, c.content) >= 0.8 }
    }

    private fun same(m: Memory, c: Candidate) =
        m.content.trim().equals(c.content.trim(), ignoreCase = true) || MemoryMatcher.similarity(m.content, c.content) >= 0.85

    // ------------------------------------------------------------------ manual management

    /** The user typed this in the Memory screen, so it is explicit permission, except for secrets which are never stored. */
    suspend fun addManual(category: MemoryCategory, text: String): AddResult {
        val content = text.trim().take(MemoryLimits.MAX_CONTENT_CHARS)
        if (content.length < 2) return AddResult.Failed("Write something to remember first.")
        val verdict = SensitivityGuard.check(content)
        if (verdict.level == Sensitivity.BLOCKED) {
            return AddResult.Blocked("JARVIS never stores secrets: ${verdict.reason}. Nothing was saved.")
        }
        return safe<AddResult>(AddResult.Failed("Memory is unavailable right now. Nothing was saved.")) { s ->
            val active = s.all().filter { it.status == MemoryStatus.ACTIVE }
            val dup = active.firstOrNull { it.category == category && MemoryMatcher.similarity(it.content, content) >= 0.85 }
            if (dup != null) {
                s.upsert(dup.copy(updatedAt = clock()))
                publish(s)
                AddResult.Duplicate
            } else {
                val now = clock()
                val m = Memory(newId(), category, null, content, MemorySource.MANUAL, 1.0, MemoryStatus.ACTIVE, now, now)
                s.upsert(m)
                publish(s)
                AddResult.Saved(m)
            }
        }
    }

    suspend fun edit(id: String, text: String): AddResult {
        val content = text.trim().take(MemoryLimits.MAX_CONTENT_CHARS)
        if (content.length < 2) return AddResult.Failed("A memory can't be empty. Delete it instead.")
        if (SensitivityGuard.check(content).level == Sensitivity.BLOCKED) {
            return AddResult.Blocked("JARVIS never stores secrets. Nothing was changed.")
        }
        return safe<AddResult>(AddResult.Failed("Memory is unavailable right now. Nothing was changed.")) { s ->
            val old = s.get(id) ?: return@safe AddResult.Failed("That memory no longer exists.")
            val updated = old.copy(content = content, updatedAt = clock(), confidence = 1.0)
            s.upsert(updated)
            publish(s)
            AddResult.Updated(updated)
        }
    }

    suspend fun delete(id: String) { safe(Unit) { it.delete(id); publish(it) } }

    suspend fun clearAll() { safe(Unit) { it.deleteAll(); publish(it) } }

    /** The user said yes to a proposal. If it was an "update this" proposal the old record is changed instead of duplicated. */
    suspend fun approve(id: String) {
        safe(Unit) { s ->
            val p = s.get(id) ?: return@safe
            val now = clock()
            val target = p.replacesId?.let { s.get(it) }
            if (target != null) {
                s.upsert(target.copy(content = p.content, confidence = 1.0, updatedAt = now))
                s.delete(p.id)
            } else {
                s.upsert(p.copy(status = MemoryStatus.ACTIVE, reason = null, replacesId = null, confidence = 1.0, updatedAt = now))
            }
            publish(s)
        }
    }

    suspend fun dismiss(id: String) = delete(id)

    // ------------------------------------------------------------------ retrieval for chat

    /**
     * A short block of the memories relevant to the recent conversation, or "" if there are none or anything fails.
     * Only ACTIVE memories are used, at most [MAX_ITEMS], within [MAX_CHARS] in total. The conversation itself is untouched.
     */
    suspend fun contextFor(recentTexts: List<String>): String {
        val words = recentTexts.takeLast(3).flatMap { MemoryMatcher.tokens(it) }.toSet()
        val selected = safe(emptyList<Memory>()) { s -> select(s.all().filter { it.status == MemoryStatus.ACTIVE }, words) }
        if (selected.isEmpty()) return ""
        return render(selected)
    }

    internal fun select(active: List<Memory>, words: Set<String>): List<Memory> {
        val scored = active
            .map { it to MemoryMatcher.score(it, words) }
            .filter { it.second >= MIN_SCORE }
            .sortedWith(compareByDescending<Pair<Memory, Double>> { it.second }.thenByDescending { it.first.updatedAt })
            .map { it.first }
        // Standing instructions ("always reply briefly") apply to every message, but only the newest couple.
        val standing = active.filter { it.category == MemoryCategory.INSTRUCTION && it !in scored }
            .sortedByDescending { it.updatedAt }.take(MAX_STANDING)
        val picked = ArrayList<Memory>()
        var chars = 0
        for (m in (scored + standing)) {
            val len = minOf(m.content.length, MAX_ITEM_CHARS) + 3
            if (picked.size >= MAX_ITEMS || chars + len > MAX_CHARS) break
            picked += m
            chars += len
        }
        return picked
    }

    private fun render(items: List<Memory>): String = buildString {
        appendLine(
            "Things you remember about the user (saved earlier by them or noted from past chats). Use them only where they " +
                "help with this message, never list or recite them, and don't claim to remember anything not listed here:",
        )
        for (m in items) appendLine("- " + m.content.take(MAX_ITEM_CHARS))
    }.trimEnd()

    companion object {
        const val MAX_ITEMS = 5
        const val MAX_STANDING = 2
        const val MAX_CHARS = 700
        const val MAX_ITEM_CHARS = 160
        const val MIN_SCORE = 1.0
    }
}
