package app.contactscleaner.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.contactscleaner.core.Contact
import app.contactscleaner.core.DuplicateReason
import app.contactscleaner.core.IdleFilter
import app.contactscleaner.core.LineType
import app.contactscleaner.core.NameProblem
import app.contactscleaner.core.PhoneTypes
import app.contactscleaner.core.Issue
import app.contactscleaner.core.Names
import app.contactscleaner.core.OutdatedReason
import app.contactscleaner.core.PhoneFormat
import app.contactscleaner.core.WhatsAppFilter
import app.contactscleaner.data.Action
import app.contactscleaner.data.ChangeRun
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Keeps numbers and emails left-to-right inside Hebrew text. */
private fun ltr(text: String) = "⁦$text⁩"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }
    BackHandler(enabled = state.screen != Screen.Home) { vm.show(Screen.Home) }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.start(
            contactsGranted = hasContactsPermission(context),
            callLogGranted = context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val screen = state.screen) {
                            Screen.Home -> "מסדר אנשי קשר"
                            is Screen.Review -> screen.category.title
                            Screen.Log -> "יומן שינויים"
                            Screen.Archive -> "ארכיון"
                        }
                    )
                },
                navigationIcon = {
                    if (state.screen != Screen.Home) {
                        IconButton(onClick = { vm.show(Screen.Home) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה")
                        }
                    }
                },
            )
        },
        // On a list screen the action buttons sit at the bottom; messages show above them, not over them.
        snackbarHost = {
            SnackbarHost(snackbar, Modifier.padding(bottom = if (state.screen is Screen.Review) 84.dp else 0.dp))
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.busy?.let { label ->
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(label, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            when (val screen = state.screen) {
                Screen.Home -> HomeScreen(state, vm)
                is Screen.Review -> CategoryScreen(screen.category, state, vm)
                Screen.Log -> LogScreen(state, vm)
                Screen.Archive -> ArchiveScreen(state, vm)
            }
        }
    }
}

@Composable
private fun rememberBackupLauncher(onPicked: (android.net.Uri) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-vcard")) { uri ->
        if (uri != null) onPicked(uri)
    }

private fun backupFileName() = "contacts-backup-${LocalDate.now()}.vcf"

@Composable
private fun rememberCsvLauncher(onPicked: (android.net.Uri) -> Unit) =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) onPicked(uri)
    }

private fun csvFileName() = "contacts-${LocalDate.now()}.csv"

/** The contacts a category's issues are about, for exporting that list. */
private fun contactsOf(issues: List<Issue>): List<Contact> = issues.flatMap(::contactsIn).distinctBy { it.id }

/** The contacts one issue is about. */
private fun contactsIn(issue: Issue): List<Contact> =
    when (issue) {
        is Issue.Duplicates -> issue.contacts
        is Issue.PhoneFormat -> listOf(issue.contact)
        is Issue.MissingAreaCode -> listOf(issue.contact)
        is Issue.PhoneLabel -> listOf(issue.contact)
        is Issue.RepeatedEntries -> listOf(issue.contact)
        is Issue.MissingName -> listOf(issue.contact)
        is Issue.SuspiciousName -> listOf(issue.contact)
        is Issue.NameTag -> listOf(issue.contact)
        is Issue.NameCleanup -> listOf(issue.contact)
        is Issue.EmptyContact -> listOf(issue.contact)
        is Issue.Inactive -> listOf(issue.contact)
        is Issue.OutdatedPhone -> listOf(issue.contact)
        is Issue.InvalidPhone -> listOf(issue.contact)
    }


private fun hasContactsPermission(context: android.content.Context) =
    listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasContactsPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = hasContactsPermission(context)
        vm.start(
            contactsGranted = hasPermission,
            callLogGranted = context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val backupLauncher = rememberBackupLauncher { vm.backup(it) }
    val exportAll = rememberCsvLauncher { vm.exportCsv(it, contacts = null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!hasPermission) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("כדי לסדר את אנשי הקשר, האפליקציה צריכה גישה אליהם.")
                        Text(
                            "הכל נשאר על הטלפון. שום מידע לא נשלח לשום מקום, ושום שינוי לא נעשה בלי אישור שלך.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
                            )
                        }) { Text("מתן גישה") }
                    }
                }
            }
            return@LazyColumn
        }

        state.crash?.let { details ->
            item {
                val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("האפליקציה נסגרה בפעם הקודמת", style = MaterialTheme.typography.titleMedium)
                        Text("אפשר להעתיק את פרטי השגיאה ולשלוח אותם, כדי שנוכל לתקן.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("פרטי שגיאה", details))
                                vm.showMessage("פרטי השגיאה הועתקו")
                            }) { Text("העתק פרטי שגיאה") }
                            TextButton(onClick = vm::dismissCrash) { Text("סגור") }
                        }
                    }
                }
            }
        }

        val report = state.report
        if (report == null) {
            item { Text("קורא את אנשי הקשר…", style = MaterialTheme.typography.titleMedium) }
            return@LazyColumn
        }

        // What needs attention comes first; the tools sit below it.
        val categories = Category.entries.filter { state.issuesIn(it).isNotEmpty() }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (categories.isEmpty()) "${report.contactCount} אנשי קשר. הכל מסודר 🎉"
                    else "${report.contactCount} אנשי קשר. זה מה שאפשר לסדר:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::scan, enabled = state.busy == null) { Text("רענון") }
            }
        }
        items(categories) { category ->
            val issues = state.issuesIn(category)
            val chosen = issues.count { it.id in state.selected }
            Card(Modifier.fillMaxWidth().clickable { vm.show(Screen.Review(category)) }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(category.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("${issues.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        category.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (chosen > 0) {
                        Text(count(chosen, "אחד מסומן", "מסומנים"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                Text("כלים", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.show(Screen.Log) }) {
                        Text(if (state.log.isEmpty()) "יומן שינויים" else "יומן שינויים (${state.log.size})")
                    }
                    OutlinedButton(onClick = { vm.show(Screen.Archive) }) {
                        Text(if (state.archived.isEmpty()) "ארכיון" else "ארכיון (${state.archived.size})")
                    }
                    OutlinedButton(onClick = { backupLauncher.launch(backupFileName()) }, enabled = state.busy == null) {
                        Text("גיבוי לקובץ")
                    }
                    OutlinedButton(onClick = { exportAll.launch(csvFileName()) }, enabled = state.busy == null) {
                        Text("ייצוא לאקסל")
                    }
                    if (state.ignored.isNotEmpty()) {
                        TextButton(onClick = vm::showIgnored) { Text("הצגת ${state.ignored.size} פריטים מוסתרים") }
                    }
                }
                Text("פורמט מספרים", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.format == PhoneFormat.E164,
                        onClick = { vm.setFormat(PhoneFormat.E164) },
                        label = { Text(ltr("+972501234567")) },
                    )
                    FilterChip(
                        selected = state.format == PhoneFormat.INTERNATIONAL,
                        onClick = { vm.setFormat(PhoneFormat.INTERNATIONAL) },
                        label = { Text(ltr("+972 50-123-4567")) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryScreen(category: Category, state: UiState, vm: MainViewModel) {
    val issues = state.issuesIn(category)
    val chosen = issues.count { it.id in state.selected }
    val selectable = category.fixable || category.deletesNumbers || category.deletesContacts
    var confirming by remember { mutableStateOf<Action?>(null) }
    val exportList = rememberCsvLauncher { vm.exportCsv(it, contactsOf(issues)) }

    // Everything above the action buttons scrolls, so however many filters
    // and tag buttons there are, the buttons stay on screen.
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text(category.description, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
            if (category == Category.INACTIVE) item { InactiveFilterPanel(state, vm) }
            if (category == Category.NAME_TAG) item { TagButtons(issues, vm) }
            if (selectable) item {
                FlowRow(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { vm.setAll(category, true) }) { Text("סמן הכל") }
                    TextButton(onClick = { vm.setAll(category, false) }) { Text("נקה סימון") }
                    if (category == Category.DUPLICATES) {
                        TextButton(onClick = vm::selectConfidentDuplicates) { Text("סמן רק ודאיים") }
                    }
                    if (chosen > 0) {
                        TextButton(onClick = { vm.ignoreSelected(category) }) { Text("הסתרת המסומנים ($chosen)") }
                    }
                    TextButton(onClick = { exportList.launch(csvFileName()) }) { Text("ייצוא הרשימה") }
                }
            }
            item { HorizontalDivider() }
            if (issues.isEmpty()) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("סיימת כאן ✓ אין יותר מה לסדר בקטגוריה הזו.", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { vm.show(Screen.Home) }) { Text("חזרה למסך הראשי") }
                    }
                }
            }
            items(issues, key = { it.id }) { issue ->
                Box(Modifier.padding(horizontal = 12.dp)) {
                    IssueCard(issue, state, vm, selectable = selectable)
                }
            }
        }
        if (selectable && issues.isNotEmpty()) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val enabled = chosen > 0 && state.busy == null
                    if (category.fixable) {
                        Button(
                            onClick = { confirming = Action.FIX },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        ) { Text("תיקון $chosen") }
                    }
                    if (category.deletesNumbers) {
                        OutlinedButton(
                            onClick = { confirming = Action.DELETE_NUMBERS },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("מחיקת " + count(chosen, "מספר אחד", "מספרים")) }
                    }
                    if (category.archives) {
                        OutlinedButton(
                            onClick = { confirming = Action.ARCHIVE_CONTACTS },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        ) { Text("ארכיון $chosen") }
                    }
                    if (category.deletesContacts) {
                        OutlinedButton(
                            onClick = { confirming = Action.DELETE_CONTACTS },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text(if (category.archives) "מחיקה $chosen" else "מחיקת " + count(chosen, "איש קשר אחד", "אנשי קשר")) }
                    }
                }
            }
        }
    }

    confirming?.let { action ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                Text(
                    when (action) {
                        Action.FIX -> "לבצע " + count(chosen, "תיקון אחד", "תיקונים") + "?"
                        Action.DELETE_NUMBERS -> "למחוק " + count(chosen, "מספר אחד", "מספרים") + "?"
                        Action.DELETE_CONTACTS -> "למחוק " + count(chosen, "איש קשר אחד", "אנשי קשר") + "?"
                        Action.ARCHIVE_CONTACTS -> "להעביר " + count(chosen, "איש קשר אחד", "אנשי קשר") + " לארכיון?"
                    }
                )
            },
            text = {
                Text(
                    when (action) {
                        Action.FIX -> "השינויים ייכתבו לאנשי הקשר"
                        Action.DELETE_NUMBERS -> "המספרים יימחקו מאנשי הקשר"
                        Action.DELETE_CONTACTS -> "אנשי הקשר יימחקו"
                        Action.ARCHIVE_CONTACTS -> "אנשי הקשר יישמרו בארכיון של האפליקציה, יימחקו מהטלפון"
                    } + " ויסונכרנו לחשבון גוגל. כל פעולה נשמרת ביומן השינויים, ואפשר לבטל אותה שם בלחיצה."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirming = null
                    vm.apply(category, action)
                }) { Text("ביצוע") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("ביטול") } },
        )
    }
}

/** Filters for the "inactive contacts" screen, plus asking for the call log when it's missing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InactiveFilterPanel(state: UiState, vm: MainViewModel) {
    val filter = state.inactiveFilter
    val callLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.setCallLogGranted(it)
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("וואטסאפ", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                WhatsAppFilter.ON_WHATSAPP to "יש וואטסאפ",
                WhatsAppFilter.NOT_ON_WHATSAPP to "אין וואטסאפ",
                WhatsAppFilter.ANY to "הכל",
            ).forEach { (value, label) ->
                FilterChip(
                    selected = filter.whatsApp == value,
                    onClick = { vm.setInactiveFilter(filter.copy(whatsApp = value)) },
                    label = { Text(label) },
                )
            }
        }
        Text("שיחת טלפון אחרונה", style = MaterialTheme.typography.labelLarge)
        if (state.callLogGranted) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    IdleFilter.NEVER to "אף פעם",
                    IdleFilter.YEAR to "לפני שנה+",
                    IdleFilter.TWO_YEARS to "שנתיים+",
                    IdleFilter.THREE_YEARS to "3 שנים+",
                    IdleFilter.ANY to "הכל",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = filter.idle == value,
                        onClick = { vm.setInactiveFilter(filter.copy(idle = value)) },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "יומן השיחות בטלפון שומר רק תקופה מוגבלת (לרוב כמה מאות שיחות אחרונות), אז \"אף פעם\" יכול לכלול מי שדיברת איתו מזמן.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "כדי לסנן לפי שיחה אחרונה צריך הרשאה לקרוא את יומן השיחות. הוא נקרא רק בטלפון, לא נשלח לשום מקום.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { callLogLauncher.launch(Manifest.permission.READ_CALL_LOG) }) {
                Text("מתן הרשאה ליומן שיחות")
            }
        }
    }
}

private val DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/**
 * Quick selection for "fixed tags in names". Tags of one family ("ס001",
 * "ס002"…, i.e. the same letters with a number) get a single button; the
 * individual tags sit in one row that scrolls sideways.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagButtons(issues: List<Issue>, vm: MainViewModel) {
    val counts = issues.flatMap { (it as Issue.NameTag).tags }.groupingBy { it }.eachCount()
    val families = counts.entries
        .groupBy({ tagFamily(it.key) }, { it })
        .filterValues { it.size > 1 }
        .mapValues { (_, tags) -> tags.sumOf { it.value } }
        .entries.sortedByDescending { it.value }

    Column(Modifier.padding(horizontal = 8.dp)) {
        if (families.isNotEmpty()) {
            Text("סימון לפי סוג תוספת", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge)
            FlowRow {
                families.forEach { (family, count) ->
                    TextButton(onClick = { vm.selectTagFamily(family) }) {
                        Text("סמן את כל ${ltr(familyLabel(family))} ($count)")
                    }
                }
            }
        }
        Text("סימון לפי תוספת בודדת", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            counts.entries.sortedByDescending { it.value }.forEach { (tag, count) ->
                TextButton(onClick = { vm.selectTag(tag) }) { Text("${ltr(tag)} ($count)") }
            }
        }
    }
}

/** "ס005" -> "ס#", "AB12" -> "AB#", "Tz" -> "Tz": tags that differ only in their number are one family. */
fun tagFamily(tag: String) = tag.replace(Regex("\\d+"), "#")

private fun familyLabel(family: String) = family.replace("#", "+מספר")

@Composable
private fun IssueCard(issue: Issue, state: UiState, vm: MainViewModel, selectable: Boolean) {
    val selected = issue.id in state.selected
    Card(
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            if (selectable) {
                Checkbox(checked = selected, onCheckedChange = { vm.toggle(issue.id) })
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f).padding(top = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                IssueBody(issue, state, vm)
                Row {
                    // Duplicates open each contact from its own line (see ContactDetails).
                    val single = contactsIn(issue).singleOrNull()
                    if (single != null) {
                        val context = LocalContext.current
                        TextButton(onClick = { openContact(context, single.id) }) { Text("פתח באנשי קשר") }
                    }
                    TextButton(onClick = { vm.ignore(listOf(issue.id)) }) { Text("הסתר") }
                }
            }
        }
    }
}

/** Opens a contact in the phone's Contacts app, to look at everything it holds. */
private fun openContact(context: android.content.Context, contactId: Long) {
    val uri = android.content.ContentUris.withAppendedId(android.provider.ContactsContract.Contacts.CONTENT_URI, contactId)
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
    } catch (_: android.content.ActivityNotFoundException) {
        // No contacts app to show it in; nothing else to do.
    }
}

@Composable
private fun IssueBody(issue: Issue, state: UiState, vm: MainViewModel) {
    val small = MaterialTheme.typography.bodySmall
    when (issue) {
        is Issue.PhoneFormat -> {
            Title(issue.contact.title)
            Text("לפני: ${ltr(issue.phone.number)}", style = small)
            Text("אחרי: ${ltr(issue.suggested)}", fontWeight = FontWeight.Medium)
        }
        is Issue.MissingAreaCode -> {
            Title(issue.contact.title)
            Text(ltr(issue.phone.number), style = small)
            val area = state.inputs[issue.id] ?: issue.guess
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Issue.MissingAreaCode.AREA_CODES.forEach { code ->
                    FilterChip(
                        selected = code == area,
                        onClick = { vm.setInput(issue.id, code) },
                        label = { Text(code) },
                    )
                }
            }
            if (area == null) {
                Text("בחר קידומת", style = small, color = MaterialTheme.colorScheme.error)
            } else {
                vm.preview(issue, area)?.let { Text("אחרי: ${ltr(it)}", fontWeight = FontWeight.Medium) }
            }
        }
        is Issue.OutdatedPhone -> {
            Title(issue.contact.title)
            Text(ltr(issue.phone.number))
            Text(
                when (issue.reason) {
                    OutdatedReason.OLD_MOBILE -> "סלולרי בפורמט ישן (6 ספרות אחרי הקידומת)"
                    OutdatedReason.OLD_LANDLINE -> "קווי בפורמט ישן (6 ספרות אחרי הקידומת)"
                    OutdatedReason.UNALLOCATED -> "בטווח מספרים שלא חולק לאף חברה"
                },
                style = small,
            )
        }
        is Issue.InvalidPhone -> {
            Title(issue.contact.title)
            Text(ltr(issue.phone.number))
        }
        is Issue.RepeatedEntries -> {
            Title(issue.contact.title)
            val removed = issue.repeatedPhones.map { it.number } + issue.repeatedEmails.map { it.address }
            Text("יימחקו העותקים: " + removed.joinToString(", ") { ltr(it) }, style = small)
        }
        is Issue.PhoneLabel -> {
            Title(issue.contact.title)
            Text(ltr(issue.phone.number), style = small)
            Text(
                (if (issue.line == LineType.MOBILE) "מספר נייד" else "מספר קווי") +
                    " שמסומן \"${typeLabel(issue.phone.type)}\". יסומן \"${typeLabel(issue.suggestedType)}\".",
                fontWeight = FontWeight.Medium,
            )
        }
        is Issue.NameTag -> {
            ContactDetails(issue.contact)
            Text(
                "תוספת: " + issue.tags.joinToString(", ") { ltr(it) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = state.inputs[issue.id] ?: issue.suggested,
                onValueChange = { vm.setInput(issue.id, it) },
                label = { Text(if (issue.suggested.isEmpty()) "בלי התוספת נשאר רק מספר. הקלד שם, או העבר לארכיון" else "שם בלי התוספת") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is Issue.SuspiciousName -> {
            ContactDetails(issue.contact)
            Text(
                when (issue.problem) {
                    NameProblem.SINGLE_LETTER -> "שם של אות אחת"
                    NameProblem.GENERIC -> "תיאור במקום שם"
                    NameProblem.EMOJI -> "אימוג'ים בשם"
                    NameProblem.ONLY_EMOJI -> "רק אימוג'ים"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedTextField(
                value = state.inputs[issue.id] ?: issue.suggested ?: issue.name.displayName.orEmpty(),
                onValueChange = { vm.setInput(issue.id, it) },
                label = { Text("שם חדש") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is Issue.NameCleanup -> {
            Text("לפני: \"${issue.name.displayName}\"", style = small)
            Text("אחרי: \"${issue.suggested}\"", fontWeight = FontWeight.Medium)
        }
        is Issue.MissingName -> {
            ContactDetails(issue.contact)
            OutlinedTextField(
                value = state.inputs[issue.id] ?: issue.suggested.orEmpty(),
                onValueChange = { vm.setInput(issue.id, it) },
                label = { Text("שם") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is Issue.EmptyContact -> Title(issue.contact.title)
        is Issue.Inactive -> {
            ContactDetails(issue.contact)
            val time = issue.lastCall
            val lastCall = when {
                !state.callLogGranted -> "לא ידוע (אין הרשאה ליומן שיחות)"
                time == null -> "אף פעם"
                else -> Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DATE)
            }
            Text(
                (if (issue.onWhatsApp) "יש וואטסאפ" else "אין וואטסאפ") + " · שיחה אחרונה: $lastCall",
                style = small,
            )
        }
        is Issue.Duplicates -> {
            Text(
                issue.reasons.joinToString(" · ") { reason ->
                    when (reason) {
                        DuplicateReason.SAME_PHONE -> "אותו מספר"
                        DuplicateReason.SAME_EMAIL -> "אותו מייל"
                        DuplicateReason.SAME_NAME -> "אותו שם"
                        DuplicateReason.SIMILAR_NAME -> "שם דומה"
                    }
                } + if (issue.confident) "" else " · כדאי לבדוק",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (issue.contacts.size > 2 && !issue.confident) {
                Text(
                    "⚠ קבוצה של ${issue.contacts.size} אנשי קשר. ייתכן שאלה אנשים שונים עם מספר משותף (בית, משרד). בדוק לפני איחוד.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            issue.contacts.forEach { ContactDetails(it) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val details = issue.merged.phones + issue.merged.emails
            if (details.isNotEmpty()) {
                Text("אחרי האיחוד יישארו: " + details.joinToString(", ") { ltr(it) }, style = small)
            }
            MergeNamePicker(issue, state, vm)
        }
    }
}

/**
 * The name the merged contact will get: one of the existing names (cleaned
 * up), or one the user types. Picking or typing also selects the merge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MergeNamePicker(issue: Issue.Duplicates, state: UiState, vm: MainViewModel) {
    val options = (listOfNotNull(issue.merged.name) + issue.contacts.mapNotNull { it.displayName })
        .filterNot(Names::looksLikePhoneOrEmail)
        .map { Names.cleanup(it) ?: it }
        .distinct()
    val chosen = state.inputs[issue.id] ?: issue.merged.name.orEmpty()

    Text("שם אחרי האיחוד:", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == chosen,
                onClick = { vm.setInput(issue.id, option) },
                label = { Text(option) },
            )
        }
    }
    OutlinedTextField(
        value = chosen,
        onValueChange = { vm.setInput(issue.id, it) },
        label = { Text("או הקלד שם אחר") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

@Composable
private fun ContactDetails(contact: Contact) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable { openContact(context, contact.id) }) {
        Title(contact.title)
        val details = contact.phones.map { it.number } + contact.emails.map { it.address }
        if (details.isNotEmpty()) {
            Text(details.joinToString(", ") { ltr(it) }, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LogScreen(state: UiState, vm: MainViewModel) {
    var confirming by remember { mutableStateOf<ChangeRun?>(null) }
    val timeFormat = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm") }

    if (state.log.isEmpty()) {
        Text("עוד לא בוצעו תיקונים.", Modifier.padding(16.dp))
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.log, key = { it.id }) { run ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(run.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(
                            Instant.ofEpochMilli(run.time).atZone(ZoneId.systemDefault()).format(timeFormat),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Text("${run.lines.size} שינויים" + if (run.undone) " · בוטל" else "", style = MaterialTheme.typography.labelMedium)
                    run.verification?.let {
                        Text("✓ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    run.lines.take(MAX_LOG_LINES).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (run.lines.size > MAX_LOG_LINES) {
                        Text("ועוד ${run.lines.size - MAX_LOG_LINES}…", style = MaterialTheme.typography.bodySmall)
                    }
                    if (run.id == state.undoableRunId) {
                        OutlinedButton(onClick = { confirming = run }, enabled = state.busy == null) { Text("ביטול השינויים") }
                    }
                }
            }
        }
    }

    confirming?.let { run ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("לבטל את \"${run.title}\"?") },
            text = { Text("אנשי הקשר שהשתנו יחזרו בדיוק למצב שלפני. אם ערכת אותם ידנית מאז, גם העריכה הזו תתבטל.") },
            confirmButton = {
                Button(onClick = {
                    confirming = null
                    vm.undo(run)
                }) { Text("ביטול השינויים") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("חזרה") } },
        )
    }
}

private const val MAX_LOG_LINES = 8

private fun typeLabel(type: Int) = when (type) {
    PhoneTypes.MOBILE -> "נייד"
    PhoneTypes.HOME -> "בית"
    PhoneTypes.WORK -> "עבודה"
    PhoneTypes.FAX_HOME, PhoneTypes.FAX_WORK -> "פקס"
    else -> "אחר"
}

/** Contacts moved out of the phone: restore them, or delete them for good. */
@Composable
private fun ArchiveScreen(state: UiState, vm: MainViewModel) {
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val timeFormat = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy") }

    if (state.archived.isEmpty()) {
        Text("הארכיון ריק. אפשר להעביר אליו אנשי קשר מ\"ניקוי לפי פעילות\", \"שמות חשודים\" ו\"אנשי קשר ריקים\".", Modifier.padding(16.dp))
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "אנשי קשר שהועברו לכאן נמחקו מהטלפון, אבל כל הפרטים שלהם שמורים באפליקציה. שחזור מחזיר אותם בדיוק כמו שהיו.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { selected = state.archived.map { it.id }.toSet() }) { Text("סמן הכל") }
            TextButton(onClick = { selected = emptySet() }) { Text("נקה סימון") }
        }
        HorizontalDivider()
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.archived, key = { it.id }) { item ->
                val isSelected = item.id in selected
                Card(
                    colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    else CardDefaults.cardColors(),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isSelected, onCheckedChange = {
                            selected = if (isSelected) selected - item.id else selected + item.id
                        })
                        Column(Modifier.weight(1f)) {
                            Title(item.title)
                            if (item.phones.isNotEmpty()) Text(item.phones.joinToString(", ") { ltr(it) }, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "הועבר לארכיון: " + Instant.ofEpochMilli(item.archivedAt).atZone(ZoneId.systemDefault()).format(timeFormat),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val enabled = selected.isNotEmpty() && state.busy == null
                Button(
                    onClick = {
                        vm.restore(selected)
                        selected = emptySet()
                    },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("שחזור ${selected.size}") }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("מחיקה לצמיתות") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("למחוק ${selected.size} לצמיתות?") },
            text = { Text("הם יימחקו גם מהארכיון, ואי אפשר יהיה לשחזר אותם, אלא מקובץ גיבוי.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    vm.deleteFromArchive(selected)
                    selected = emptySet()
                }) { Text("מחיקה") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ביטול") } },
        )
    }
}
