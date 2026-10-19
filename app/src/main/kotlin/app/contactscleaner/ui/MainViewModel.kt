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
import app.contactscleaner.core.CsvExporter
import app.contactscleaner.data.Action
import app.contactscleaner.data.Archive
import app.contactscleaner.data.ArchivedContact
import app.contactscleaner.data.Backup
import app.contactscleaner.data.ChangeLog
import app.contactscleaner.data.ChangeRun
import app.contactscleaner.data.ContactsRepository
import app.contactscleaner.data.ContactsWriter
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
    data object Archive : Screen
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
    val archived: List<ArchivedContact> = emptyList(),
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
    private val archive = Archive(app)

    private val _state = MutableStateFlow(
        UiState(format = prefs.getString("format", null)?.let(PhoneFormat::valueOf) ?: PhoneFormat.E164)
    )
    val state: StateFlow<UiState> = _state

    private val normalizer get() = PhoneNormalizer(region, _state.value.format)

    init {
        // Left behind by the removed ME connection on phones that used it.
        java.io.File(app.filesDir, "me.json").delete()
        viewModelScope.launch {
            reloadLog()
            reloadArchive()
        }
    }

    private suspend fun reloadArchive() {
        val items = withContext(Dispatchers.IO) { archive.all() }
        _state.update { it.copy(archived = items) }
    }

    /** Puts archived contacts back in the address book. */
    fun restore(ids: Set<Long>) = work("משחזר מהארכיון…") {
        val items = _state.value.archived.filter { it.id in ids }
        withContext(Dispatchers.IO) {
            ContactsWriter(resolver, normalizer).restore(items.map { it.raws })
            archive.remove(ids)
        }
        _state.update { it.copy(message = "${items.size} אנשי קשר חזרו לטלפון") }
        reloadArchive()
        if (_state.value.report != null) rescan()
    }

    fun deleteFromArchive(ids: Set<Long>) = work("מוחק מהארכיון…") {
        withContext(Dispatchers.IO) { archive.remove(ids) }
        _state.update { it.copy(message = "${ids.size} נמחקו לצמיתות מהארכיון") }
        reloadArchive()
    }

    /** Writes [contacts] (or, if null, the whole address book) as a CSV for Excel. */
    fun exportCsv(target: Uri, contacts: List<Contact>?) = work("מייצא…") {
        val normalizer = normalizer
        val count = withContext(Dispatchers.IO) {
            val list = contacts ?: ContactsRepository(resolver).let { repo ->
                repo.withActivity(repo.loadAll(), normalizer, _state.value.callLogGranted)
            }
            resolver.openOutputStream(target)?.use { it.write(CsvExporter.export(list).toByteArray(Charsets.UTF_8)) }
                ?: error("לא ניתן לכתוב לקובץ")
            list.size
        }
        _state.update { it.copy(message = "יוצאו $count אנשי קשר") }
    }

    /** Ticks every name carrying [tag], on top of what's already ticked. */
    fun selectTag(tag: String) = _state.update { s ->
        s.copy(selected = s.selected + s.issuesIn(Category.NAME_TAG).filter { tag in (it as Issue.NameTag).tags }.map { it.id })
    }

    /** Ticks every name whose tag is of [family] (see [tagFamily]). */
    fun selectTagFamily(family: String) = _state.update { s ->
        s.copy(
            selected = s.selected + s.issuesIn(Category.NAME_TAG)
                .filter { issue -> (issue as Issue.NameTag).tags.any { tagFamily(it) == family } }
                .map { it.id }
        )
    }

    /** Ticks only the duplicates that are safe to merge without a second look. */
    fun selectConfidentDuplicates() = _state.update { s ->
        val all = s.issuesIn(Category.DUPLICATES)
        s.copy(selected = s.selected - all.map { it.id }.toSet() + all.filter { (it as Issue.Duplicates).confident }.map { it.id })
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
        val report = withContext(Dispatchers.IO) {
            val repository = ContactsRepository(resolver)
            val contacts = repository.withActivity(repository.loadAll(), normalizer, readCallLog)
            ContactAnalyzer(normalizer).analyze(contacts, withActivity = true)
        }
        _state.update {
            it.copy(
                report = report,
                selected = defaultSelection(report),
                inputs = emptyMap(),
                scannedAt = System.currentTimeMillis(),
            )
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

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun apply(category: Category, action: Action) =
        work(if (action == Action.FIX) "מתקן…" else "מוחק…") { applyNow(category, action) }

    private suspend fun applyNow(category: Category, action: Action) {
        val s = _state.value
        val chosen = s.issuesIn(category).filter { it.id in s.selected }
        val normalizer = normalizer
        val now = System.currentTimeMillis()
        val run = withContext(Dispatchers.IO) {
            val title = if (action == Action.FIX) category.title else "${category.title}: מחיקה"
            ContactsWriter(resolver, normalizer).apply(title, chosen, s.inputs, action)
                ?.also(changeLog::add)
                ?.also { run ->
                    if (action == Action.ARCHIVE_CONTACTS) {
                        archive.add(chosen.mapNotNull(::contactOf).map { c ->
                            ArchivedContact(c.id, c.title, now, run.id, run.before.filter { it.id in c.rawContactIds })
                        })
                    }
                }
        }
        if (run == null) {
            _state.update { it.copy(message = "לא היה מה לתקן") }
            return
        }
        rescan()

        // Check the result against the phone itself: a fresh scan should no
        // longer find what was just fixed or deleted.
        val chosenIds = chosen.map { it.id }.toSet()
        val left = _state.value.report?.issues.orEmpty().count { it.id in chosenIds }
        val done = chosenIds.size - left
        val verification = if (left == 0) "אומתו כל $done השינויים בסריקה חוזרת"
        else "אומתו $done מתוך ${chosenIds.size}. $left עדיין מופיעים ברשימה ולא השתנו"
        withContext(Dispatchers.IO) { changeLog.update(run.copy(verification = verification)) }
        _state.update { it.copy(message = verification) }
        reloadLog()
        reloadArchive()
    }

    fun undo(run: ChangeRun) = work("מבטל…") {
        withContext(Dispatchers.IO) {
            ContactsWriter(resolver, normalizer).undo(run)
            changeLog.markUndone(run.id)
            archive.removeRun(run.id)
        }
        _state.update { it.copy(message = "השינויים של \"${run.title}\" בוטלו") }
        reloadLog()
        reloadArchive()
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
            is Issue.PhoneLabel -> true
            is Issue.EmptyContact, is Issue.InvalidPhone, is Issue.OutdatedPhone, is Issue.Inactive,
            is Issue.SuspiciousName, is Issue.NameTag -> false
        }
    }.map { it.id }.toSet()

    private companion object {
        /** The one contact a contact-level issue is about. */
        fun contactOf(issue: Issue): Contact? = when (issue) {
            is Issue.EmptyContact -> issue.contact
            is Issue.Inactive -> issue.contact
            is Issue.SuspiciousName -> issue.contact
            is Issue.NameTag -> issue.contact
            else -> null
        }

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
