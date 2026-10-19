package app.contactscleaner.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.contactscleaner.core.AnalysisReport
import app.contactscleaner.core.Contact
import app.contactscleaner.core.ContactAnalyzer
import app.contactscleaner.core.IdleFilter
import app.contactscleaner.core.InactiveFilter
import app.contactscleaner.core.Issue
import app.contactscleaner.core.PhoneFormat
import app.contactscleaner.core.PhoneNormalizer
import app.contactscleaner.data.Action
import app.contactscleaner.data.Backup
import app.contactscleaner.data.ChangeLog
import app.contactscleaner.data.ChangeRun
import app.contactscleaner.data.ContactsRepository
import app.contactscleaner.data.ContactsWriter
import app.contactscleaner.me.MeData
import app.contactscleaner.me.MeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

sealed interface Screen {
    data object Home : Screen
    data class Review(val category: Category) : Screen
    data object Log : Screen
    data object Me : Screen
}

data class UiState(
    val format: PhoneFormat = PhoneFormat.E164,
    /** Set while scanning/applying; the text says what is going on. */
    val busy: String? = null,
    val report: AnalysisReport? = null,
    val screen: Screen = Screen.Home,
    val selected: Set<String> = emptySet(),
    /** What the user typed or picked per issue id: a name, or an area code. */
    val inputs: Map<String, String> = emptyMap(),
    val log: List<ChangeRun> = emptyList(),
    val message: String? = null,
    val inactiveFilter: InactiveFilter = InactiveFilter(),
    val callLogGranted: Boolean = false,
    /** When the report was made; "inactive" ages are measured from it. */
    val scannedAt: Long = 0,
    /** When ME was last fetched; null if never. "Didn't save me" filtering needs it. */
    val meFetchedAt: Long? = null,
) {
    /** Without the call log every "last call" is unknown, so the idle part of the filter is ignored. */
    private val effectiveInactiveFilter
        get() = if (callLogGranted) inactiveFilter else inactiveFilter.copy(idle = IdleFilter.ANY)

    fun issuesIn(category: Category) = report?.issues.orEmpty().filter {
        Category.of(it) == category && (it !is Issue.Inactive || effectiveInactiveFilter.matches(it, scannedAt))
    }

    /** Undo goes newest first, one run at a time. */
    val undoableRunId: Long? get() = log.firstOrNull { !it.undone }?.id
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val resolver = app.contentResolver
    private val region = detectRegion(app)
    private val changeLog = ChangeLog(app)
    private val meStore = MeStore(app)

    private val _state = MutableStateFlow(
        UiState(format = prefs.getString("format", null)?.let(PhoneFormat::valueOf) ?: PhoneFormat.E164)
    )
    val state: StateFlow<UiState> = _state

    private val normalizer get() = PhoneNormalizer(region, _state.value.format)

    init {
        viewModelScope.launch { reloadLog() }
    }

    fun setFormat(format: PhoneFormat) {
        prefs.edit().putString("format", format.name).apply()
        _state.update { it.copy(format = format) }
        if (_state.value.report != null) scan()
    }

    fun scan() = work("סורק אנשי קשר…") { rescan() }

    private suspend fun rescan() {
        val normalizer = normalizer
        val readCallLog = _state.value.callLogGranted
        val (report, me) = withContext(Dispatchers.IO) {
            val repository = ContactsRepository(resolver)
            val me = meStore.load()
            val contacts = withMe(repository.withActivity(repository.loadAll(), normalizer, readCallLog), me)
            ContactAnalyzer(normalizer).analyze(contacts, withActivity = true) to me
        }
        _state.update {
            it.copy(
                report = report,
                meFetchedAt = me?.fetchedAt,
                selected = defaultSelection(report),
                inputs = emptyMap(),
                scannedAt = System.currentTimeMillis(),
            )
        }
    }

    /** Marks who saved the user and the names they registered, from the last ME fetch. */
    private fun withMe(contacts: List<Contact>, me: MeData?): List<Contact> {
        if (me == null) return contacts
        val byPhone = me.people.associateBy { it.phone }
        return contacts.map { c ->
            val people = c.phones.mapNotNull { byPhone[normalizer.key(it.number)] }
            c.copy(savedMe = people.isNotEmpty(), meName = people.firstNotNullOfOrNull { it.personName })
        }
    }

    /** Changing the filter clears the selection, so nothing hidden stays selected for deletion. */
    fun setInactiveFilter(filter: InactiveFilter) = _state.update { s ->
        val hidden = s.report?.issues.orEmpty().filterIsInstance<Issue.Inactive>().map { it.id }.toSet()
        s.copy(inactiveFilter = filter, selected = s.selected - hidden)
    }

    /** Called once the call-log permission is known; rescans if it was just granted. */
    fun setCallLogGranted(granted: Boolean) {
        val changed = granted != _state.value.callLogGranted
        _state.update { it.copy(callLogGranted = granted) }
        if (changed && granted && _state.value.report != null) scan()
    }

    private suspend fun reloadLog() {
        val runs = withContext(Dispatchers.IO) { changeLog.all() }
        _state.update { it.copy(log = runs) }
    }

    fun show(screen: Screen) = _state.update { it.copy(screen = screen) }

    fun toggle(issueId: String) = _state.update {
        it.copy(selected = if (issueId in it.selected) it.selected - issueId else it.selected + issueId)
    }

    fun setAll(category: Category, selected: Boolean) = _state.update { s ->
        val ids = s.issuesIn(category).map { it.id }
        s.copy(selected = if (selected) s.selected + ids else s.selected - ids.toSet())
    }

    /** Records a typed name or picked area code; a non-empty value also selects the issue. */
    fun setInput(issueId: String, value: String) = _state.update {
        it.copy(
            inputs = it.inputs + (issueId to value),
            selected = if (value.isBlank()) it.selected - issueId else it.selected + issueId,
        )
    }

    /** What the number will become with [areaCode]; null if that isn't a valid number. */
    fun preview(issue: Issue.MissingAreaCode, areaCode: String): String? =
        normalizer.withAreaCode(issue.phone.number, areaCode)

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    /** For when another screen added runs to the change log. */
    fun reloadChangeLog() {
        viewModelScope.launch { reloadLog() }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun apply(category: Category, action: Action) =
        work(if (action == Action.FIX) "מתקן…" else "מוחק…") { applyNow(category, action) }

    private suspend fun applyNow(category: Category, action: Action) {
        val s = _state.value
        val chosen = s.issuesIn(category).filter { it.id in s.selected }
        val normalizer = normalizer
        val run = withContext(Dispatchers.IO) {
            val title = if (action == Action.FIX) category.title else "${category.title}: מחיקה"
            ContactsWriter(resolver, normalizer).apply(title, chosen, s.inputs, action)
                ?.also(changeLog::add)
        }
        _state.update { it.copy(message = if (run == null) "לא היה מה לתקן" else "בוצעו ${run.lines.size} שינויים") }
        reloadLog()
        rescan()
    }

    fun undo(run: ChangeRun) = work("מבטל…") {
        withContext(Dispatchers.IO) {
            ContactsWriter(resolver, normalizer).undo(run)
            changeLog.markUndone(run.id)
        }
        _state.update { it.copy(message = "השינויים של \"${run.title}\" בוטלו") }
        reloadLog()
        rescan()
    }

    /** Backs up to [target], then applies [thenApply] if given and the backup worked. */
    fun backup(target: Uri, thenApply: Pair<Category, Action>? = null) = work("מגבה אנשי קשר…") {
        val count = withContext(Dispatchers.IO) { Backup(resolver).exportTo(target) }
        _state.update { it.copy(message = "גובו $count אנשי קשר") }
        if (thenApply != null) {
            _state.update { it.copy(busy = "מבצע…") }
            applyNow(thenApply.first, thenApply.second)
        }
    }

    private fun work(label: String, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = label) }
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(message = "שגיאה: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    /**
     * Preselect only what is safe to apply without a second look: confident
     * duplicates, formatting, and names or area codes we could guess.
     * Deleting empty contacts always needs an explicit tick.
     */
    private fun defaultSelection(report: AnalysisReport): Set<String> = report.issues.filter {
        when (it) {
            is Issue.Duplicates -> it.confident
            is Issue.PhoneFormat, is Issue.RepeatedEntries, is Issue.NameCleanup -> true
            is Issue.MissingName -> it.suggested != null
            is Issue.MissingAreaCode -> it.guess != null
            is Issue.EmptyContact, is Issue.InvalidPhone, is Issue.OutdatedPhone, is Issue.Inactive -> false
        }
    }.map { it.id }.toSet()

    private companion object {
        /** The SIM's country, else the phone's locale; numbers without a country code are read as local to it. */
        fun detectRegion(context: Context): String {
            val tm = context.getSystemService(TelephonyManager::class.java)
            return listOfNotNull(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
                .firstOrNull { it.length == 2 }
                ?.uppercase(Locale.ROOT)
                ?: "IL"
        }
    }
}
