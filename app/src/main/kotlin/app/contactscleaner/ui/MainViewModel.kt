package app.contactscleaner.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.contactscleaner.core.AnalysisReport
import app.contactscleaner.core.ContactAnalyzer
import app.contactscleaner.core.Issue
import app.contactscleaner.core.PhoneFormat
import app.contactscleaner.core.PhoneNormalizer
import app.contactscleaner.data.Backup
import app.contactscleaner.data.ContactsRepository
import app.contactscleaner.data.ContactsWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class UiState(
    val format: PhoneFormat = PhoneFormat.E164,
    /** Set while scanning/applying; the text says what is going on. */
    val busy: String? = null,
    val report: AnalysisReport? = null,
    val open: Category? = null,
    val selected: Set<String> = emptySet(),
    /** Names typed for [Issue.MissingName], by issue id. */
    val names: Map<String, String> = emptyMap(),
    val message: String? = null,
) {
    fun issuesIn(category: Category) = report?.issues.orEmpty().filter { Category.of(it) == category }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val resolver = app.contentResolver
    private val region = detectRegion(app)

    private val _state = MutableStateFlow(
        UiState(format = prefs.getString("format", null)?.let(PhoneFormat::valueOf) ?: PhoneFormat.E164)
    )
    val state: StateFlow<UiState> = _state

    private val normalizer get() = PhoneNormalizer(region, _state.value.format)

    fun setFormat(format: PhoneFormat) {
        prefs.edit().putString("format", format.name).apply()
        _state.update { it.copy(format = format) }
        if (_state.value.report != null) scan()
    }

    fun scan() = work("סורק אנשי קשר…") { rescan() }

    private suspend fun rescan() {
        val normalizer = normalizer
        val report = withContext(Dispatchers.IO) {
            ContactAnalyzer(normalizer).analyze(ContactsRepository(resolver).loadAll())
        }
        _state.update { it.copy(report = report, selected = defaultSelection(report), names = emptyMap()) }
    }

    fun open(category: Category?) = _state.update { it.copy(open = category) }

    fun toggle(issueId: String) = _state.update {
        it.copy(selected = if (issueId in it.selected) it.selected - issueId else it.selected + issueId)
    }

    fun setAll(category: Category, selected: Boolean) = _state.update { s ->
        val ids = s.issuesIn(category).map { it.id }
        s.copy(selected = if (selected) s.selected + ids else s.selected - ids.toSet())
    }

    fun setName(issueId: String, name: String) = _state.update {
        it.copy(
            names = it.names + (issueId to name),
            selected = if (name.isBlank()) it.selected - issueId else it.selected + issueId,
        )
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun apply(category: Category) = work("מתקן…") { applyNow(category) }

    private suspend fun applyNow(category: Category) {
        val s = _state.value
        val chosen = s.issuesIn(category).filter { it.id in s.selected }
        val names = s.names + chosen.filterIsInstance<Issue.MissingName>()
            .filter { it.id !in s.names && it.suggested != null }
            .associate { it.id to it.suggested!! }
        val normalizer = normalizer
        val fixed = withContext(Dispatchers.IO) { ContactsWriter(resolver, normalizer).apply(chosen, names) }
        _state.update { it.copy(message = "בוצעו $fixed תיקונים") }
        rescan()
    }

    /** Backs up to [target], then applies [thenApply] if given and the backup worked. */
    fun backup(target: Uri, thenApply: Category? = null) = work("מגבה אנשי קשר…") {
        val count = withContext(Dispatchers.IO) { Backup(resolver).exportTo(target) }
        _state.update { it.copy(message = "גובו $count אנשי קשר") }
        if (thenApply != null) {
            _state.update { it.copy(busy = "מתקן…") }
            applyNow(thenApply)
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
     * duplicates, formatting, and names we could guess. Deleting empty
     * contacts always needs an explicit tick.
     */
    private fun defaultSelection(report: AnalysisReport): Set<String> = report.issues.filter {
        when (it) {
            is Issue.Duplicates -> it.confident
            is Issue.PhoneFormat, is Issue.RepeatedEntries, is Issue.NameCleanup -> true
            is Issue.MissingName -> it.suggested != null
            is Issue.EmptyContact, is Issue.InvalidPhone -> false
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
