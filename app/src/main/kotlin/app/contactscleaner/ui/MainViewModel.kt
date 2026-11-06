package app.contactscleaner.ui

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.contactscleaner.CrashLog
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
    /** Details of the last crash, if the app closed unexpectedly last time. */
    val crash: String? = null,
    /** Issues the user said are fine as they are; never shown again until "show hidden". */
    val ignored: Set<String> = emptySet(),
) {
    /** Without the call log every "last call" is unknown, so the idle part of the filter is ignored. */
    private val effectiveInactiveFilter
        get() = if (callLogGranted) inactiveFilter else inactiveFilter.copy(idle = IdleFilter.ANY)

    fun issuesIn(category: Category) = report?.issues.orEmpty().filter {
        it.id !in ignored && Category.of(it) == category &&
            (it !is Issue.Inactive || effectiveInactiveFilter.matches(it, scannedAt))
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
        UiState(
            format = prefs.getString("format", null)?.let(PhoneFormat::valueOf) ?: PhoneFormat.E164,
            ignored = prefs.getStringSet("ignored", null).orEmpty().toSet(),
            crash = CrashLog.read(app),
        )
    )
    val state: StateFlow<UiState> = _state

    private val normalizer get() = PhoneNormalizer(region, _state.value.format)

    /** Set when the address book changed since the last scan (here or in any other app). */
    @Volatile
    private var contactsChanged = false

    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            contactsChanged = true
        }
    }

    init {
        // Left behind by the removed ME connection on phones that used it.
        java.io.File(app.filesDir, "me.json").delete()
        viewModelScope.launch {
            reloadLog()
            reloadArchive()
        }
    }

    /**
     * Called when the screen opens: remembers the permissions and scans right
     * away, so there is never a "scan" button to press.
     */
    fun start(contactsGranted: Boolean, callLogGranted: Boolean) {
        _state.update { it.copy(callLogGranted = callLogGranted) }
        if (!contactsGranted) return
        if (!observing) {
            resolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, contactsObserver)
            observing = true
        }
        if (_state.value.report == null) scan()
    }

    private var observing = false

    /** Back in the app: if contacts changed meanwhile, the lists catch up by themselves. */
    fun onResume() {
        if (observing && contactsChanged && _state.value.report != null) {
            work("מתעדכן…") { rescan() }
        }
    }

    override fun onCleared() {
        if (observing) resolver.unregisterContentObserver(contactsObserver)
    }

    private suspend fun reloadArchive() {
        val items = withContext(Dispatchers.IO) { archive.all() }
        _state.update { it.copy(archived = items) }
    }

    /** Puts archived contacts back in the address book. */
    fun restore(ids: Set<Long>) = work("משחזר מהארכיון…") {
        val items = _state.value.archived.filter { it.id in ids }
        withContext(Dispatchers.IO) {
            // In parts, like applying: each part's snapshots are loaded only while it is restored.
            items.chunked(PART_SIZE).forEach { part ->
                ContactsWriter(resolver, normalizer).restore(archive.withRaws(part).map { it.raws })
                archive.remove(part.map { it.id }.toSet())
            }
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

    /** Hides [ids] from every list from now on; they are also unticked. */
    fun ignore(ids: Collection<String>) {
        _state.update { it.copy(ignored = it.ignored + ids, selected = it.selected - ids.toSet()) }
        prefs.edit().putStringSet("ignored", _state.value.ignored).apply()
    }

    /** Hides what is ticked in [category]. */
    fun ignoreSelected(category: Category) =
        ignore(_state.value.issuesIn(category).map { it.id }.filter { it in _state.value.selected })

    fun dismissCrash() {
        CrashLog.clear(getApplication())
        _state.update { it.copy(crash = null) }
    }

    fun showIgnored() {
        _state.update { it.copy(ignored = emptySet()) }
        prefs.edit().remove("ignored").apply()
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
        contactsChanged = false
        val normalizer = normalizer
        val readCallLog = _state.value.callLogGranted
        val report = withContext(Dispatchers.IO) {
            val repository = ContactsRepository(resolver)
            val contacts = repository.withActivity(repository.loadAll(), normalizer, readCallLog)
            ContactAnalyzer(normalizer).analyze(contacts, withActivity = true)
        }
        // Keep what the user ticked and typed for items still there; only items
        // new to this scan get the default selection.
        _state.update { s ->
            val newIds = report.issues.map { it.id }.toSet()
            val oldIds = s.report?.issues.orEmpty().map { it.id }.toSet()
            val defaults = defaultSelection(report).filter { it !in oldIds }
            s.copy(
                report = report,
                selected = s.selected.filter { it in newIds }.toSet() + defaults,
                inputs = s.inputs.filterKeys { it in newIds },
                scannedAt = System.currentTimeMillis(),
            )
        }
    }

    /** Changing the filter clears the selection, so nothing hidden stays selected for deletion. */
    fun setInactiveFilter(filter: InactiveFilter) = _state.update { s ->
        val hidden = s.report?.issues.orEmpty().filterIsInstance<Issue.Inactive>().map { it.id }.toSet()
        s.copy(inactiveFilter = filter, selected = s.selected - hidden)
    }

    /** Called when the call-log permission is granted on the "inactive" screen; rescans with it. */
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

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun apply(category: Category, action: Action) =
        work(if (action == Action.FIX) "מתקן…" else "מוחק…") { applyNow(category, action) }

    private suspend fun applyNow(category: Category, action: Action) {
        val s = _state.value
        val chosen = s.issuesIn(category).filter { it.id in s.selected }
        val normalizer = normalizer
        val baseTitle = when (action) {
            Action.FIX -> category.title
            Action.ARCHIVE_CONTACTS -> "${category.title}: ארכיון"
            else -> "${category.title}: מחיקה"
        }
        // Big selections go in parts: each part's snapshot (whole contacts,
        // photos included) is held in memory while it is written, so parts
        // keep that small however many items were ticked.
        val parts = chosen.chunked(PART_SIZE)
        val runs = mutableListOf<ChangeRun>()
        parts.forEachIndexed { index, part ->
            if (parts.size > 1) _state.update { it.copy(busy = "מבצע… חלק ${index + 1} מתוך ${parts.size}") }
            val title = if (parts.size > 1) "$baseTitle (חלק ${index + 1}/${parts.size})" else baseTitle
            withContext(Dispatchers.IO) {
                val run = ContactsWriter(resolver, normalizer).apply(title, part, s.inputs, action) ?: return@withContext
                changeLog.add(run)
                if (action == Action.ARCHIVE_CONTACTS) {
                    val now = System.currentTimeMillis()
                    archive.add(part.mapNotNull(::contactOf).map { c ->
                        ArchivedContact(c.id, c.title, now, run.id, raws = run.before.filter { it.id in c.rawContactIds })
                    })
                }
                runs += run.copy(before = emptyList())
            }
        }
        if (runs.isEmpty()) {
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
        withContext(Dispatchers.IO) { runs.forEach { changeLog.update(it.copy(verification = verification)) } }
        _state.update { it.copy(message = verification) }
        reloadLog()
        reloadArchive()
    }

    fun undo(run: ChangeRun) = work("מבטל…") {
        withContext(Dispatchers.IO) {
            ContactsWriter(resolver, normalizer).undo(changeLog.withSnapshot(run))
            changeLog.markUndone(run.id)
            archive.removeRun(run.id)
        }
        _state.update { it.copy(message = "השינויים של \"${run.title}\" בוטלו") }
        reloadLog()
        reloadArchive()
        rescan()
    }

    /** Saves every contact to [target] as one .vcf file. */
    fun backup(target: Uri) = work("מגבה אנשי קשר…") {
        val count = withContext(Dispatchers.IO) { Backup(resolver).exportTo(target) }
        _state.update { it.copy(message = "גובו $count אנשי קשר") }
    }

    private fun work(label: String, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            _state.update { it.copy(busy = label) }
            try {
                block()
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(message = "אין מספיק זיכרון לפעולה הזו. נסו לסמן פחות פריטים בכל פעם.") }
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
        /** Items per change-log run; see [applyNow]. */
        const val PART_SIZE = 200

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
