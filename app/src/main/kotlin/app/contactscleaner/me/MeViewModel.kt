package app.contactscleaner.me

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.contactscleaner.core.PhoneNormalizer
import app.contactscleaner.core.me.ActivationInput
import app.contactscleaner.core.me.ActivationParser
import app.contactscleaner.core.me.MeImportPlanner
import app.contactscleaner.core.me.MePerson
import app.contactscleaner.data.ChangeLog
import app.contactscleaner.data.ContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MeUiState(
    val data: MeData? = null,
    val pasted: String = "",
    val manualPhone: String = "",
    val parsed: ActivationInput? = null,
    val busy: String? = null,
    val error: String? = null,
    val message: String? = null,
    val search: String = "",
    /** Phones (E.164) ticked for adding to the queue. */
    val selected: Set<String> = emptySet(),
    /** Saved the user, not saved back, not in the phone and not already queued. */
    val toImport: List<MePerson> = emptyList(),
    /** ME says unsaved, but the phone already has the number. */
    val alreadyInPhone: Int = 0,
) {
    val visible: List<MePerson>
        get() {
            val q = search.trim()
            if (q.isEmpty()) return toImport
            val digits = q.filter { it.isDigit() }
            return toImport.filter { p ->
                p.personName?.contains(q, ignoreCase = true) == true ||
                    p.savedYouAs.any { it.contains(q, ignoreCase = true) } ||
                    (digits.length >= 3 && p.phone.contains(digits.trimStart('0')))
            }
        }
}

class MeViewModel(app: Application) : AndroidViewModel(app) {
    private val store = MeStore(app)
    private val resolver = app.contentResolver
    private val normalizer = PhoneNormalizer("IL")
    private val parser = ActivationParser(normalizer)

    private val _state = MutableStateFlow(MeUiState())
    val state: StateFlow<MeUiState> = _state

    init {
        refresh()
    }

    fun setPasted(text: String) = _state.update {
        it.copy(pasted = text, parsed = text.takeIf(String::isNotBlank)?.let { t -> parser.parse(t, it.manualPhone) }, error = null)
    }

    fun setManualPhone(phone: String) = _state.update {
        it.copy(manualPhone = phone, parsed = it.pasted.takeIf(String::isNotBlank)?.let { t -> parser.parse(t, phone) })
    }

    /** Hides the activation message once it's no longer needed. */
    fun clearPasted() = _state.update { it.copy(pasted = "", manualPhone = "", parsed = null) }

    fun dismissMessage() = _state.update { it.copy(message = null, error = null) }

    /**
     * Activates with the pasted code and fetches who saved the user. The
     * token lives only inside this call; it's never stored.
     */
    fun connect(onFetched: () -> Unit) {
        val ready = _state.value.parsed as? ActivationInput.Ready ?: return
        work("מתחבר ל-ME…") {
            withContext(Dispatchers.IO) {
                val client = MeClient()
                val token = client.activate(ready.phone, ready.code)
                val result = client.fetchSavedMe(token)
                val summary = MeImportPlanner(normalizer).summarize(result.entries, myNumbers())
                val previous = store.load()
                store.save(
                    MeData(
                        fetchedAt = System.currentTimeMillis(),
                        received = summary.received,
                        rejected = summary.rejected + result.skipped,
                        maybePartial = result.maybePartial,
                        people = summary.people,
                        queue = previous?.queue.orEmpty(),
                        settings = previous?.settings ?: ImportSettings(),
                        lastBatchAt = previous?.lastBatchAt,
                        imported = previous?.imported ?: 0,
                    )
                )
            }
            clearPasted()
            reload()
            onFetched()
        }
    }

    fun setSearch(q: String) = _state.update { it.copy(search = q) }

    fun toggle(phone: String) = _state.update {
        it.copy(selected = if (phone in it.selected) it.selected - phone else it.selected + phone)
    }

    /** Ticks what the search currently shows (all of the list when there's no search). */
    fun selectVisible() = _state.update { it.copy(selected = it.selected + it.visible.map(MePerson::phone)) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun updateSettings(change: (ImportSettings) -> ImportSettings) {
        val data = store.update { it.copy(settings = change(it.settings)) }
        _state.update { it.copy(data = data) }
    }

    /** Queues the ticked people; a daily batch adds them to the phone. */
    fun enqueue() = work("מוסיף לתור…") {
        val chosen = _state.value.toImport.map { it.phone }.filter { it in _state.value.selected }
        withContext(Dispatchers.IO) { store.update { d -> d.copy(queue = (d.queue + chosen).distinct()) } }
        ImportWorker.schedule(getApplication())
        _state.update { it.copy(selected = emptySet(), message = "${chosen.size} נוספו לתור. מנה ראשונה תתווסף בעוד כיום, או עכשיו בכפתור \"ייבא מנה עכשיו\".") }
        reload()
    }

    fun importNow(onDone: () -> Unit) = work("מוסיף מנה לאנשי הקשר…") {
        val added = withContext(Dispatchers.IO) {
            MeImporter(resolver, store, ChangeLog(getApplication())).importNext()
        }
        _state.update { it.copy(message = "נוספו $added אנשי קשר. אפשר לבטל ביומן השינויים.") }
        reload()
        onDone()
    }

    fun setPaused(paused: Boolean) = updateSettings { it.copy(paused = paused) }

    fun clearQueue() = work("מרוקן את התור…") {
        withContext(Dispatchers.IO) { store.update { it.copy(queue = emptyList()) } }
        ImportWorker.cancel(getApplication())
        reload()
    }

    /** Forgets everything from ME: the list, the queue, the settings. */
    fun clearAll(onDone: () -> Unit) = work("מוחק את נתוני ME…") {
        withContext(Dispatchers.IO) { store.clear() }
        ImportWorker.cancel(getApplication())
        _state.value = MeUiState(message = "נתוני ME נמחקו מהטלפון.")
        onDone()
    }

    fun refresh() = work(null) { reload() }

    private suspend fun reload() {
        val (data, toImport, alreadyInPhone) = withContext(Dispatchers.IO) {
            val data = store.load() ?: return@withContext Triple(null, emptyList<MePerson>(), 0)
            val mine = runCatching { myNumbers() }.getOrDefault(emptySet())
            val queued = data.queue.toSet()
            val unsaved = data.people.filter { it.unsavedInMe }
            Triple(
                data,
                unsaved.filter { it.phone !in mine && it.phone !in queued },
                unsaved.count { it.phone in mine },
            )
        }
        _state.update { s ->
            s.copy(
                data = data,
                toImport = toImport,
                alreadyInPhone = alreadyInPhone,
                selected = s.selected.intersect(toImport.map { it.phone }.toSet()),
            )
        }
    }

    private fun myNumbers(): Set<String> =
        ContactsRepository(resolver).loadAll().flatMap { c -> c.phones.map { normalizer.key(it.number) } }.toSet()

    private fun work(label: String?, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = label ?: "") }
            try {
                block()
            } catch (e: MeException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "שגיאה: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }
}
