package app.contactscleaner.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import app.contactscleaner.core.Issue
import app.contactscleaner.core.OutdatedReason
import app.contactscleaner.core.PhoneFormat
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (val screen = state.screen) {
                            Screen.Home -> "מסדר אנשי קשר"
                            is Screen.Review -> screen.category.title
                            Screen.Log -> "יומן שינויים"
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
        snackbarHost = { SnackbarHost(snackbar) },
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
private fun HomeScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current
    fun granted() = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    var hasPermission by remember { mutableStateOf(granted()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = granted()
        if (hasPermission) vm.scan()
    }
    val backupLauncher = rememberBackupLauncher { vm.backup(it) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!hasPermission) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("כדי לסרוק ולתקן, האפליקציה צריכה גישה לאנשי הקשר.")
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

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::scan, enabled = state.busy == null) {
                    Text(if (state.report == null) "סריקת אנשי קשר" else "סריקה מחדש")
                }
                OutlinedButton(
                    onClick = { backupLauncher.launch(backupFileName()) },
                    enabled = state.busy == null,
                ) { Text("גיבוי לקובץ") }
            }
        }

        item {
            OutlinedButton(onClick = { vm.show(Screen.Log) }) {
                Text(if (state.log.isEmpty()) "יומן שינויים" else "יומן שינויים (${state.log.size})")
            }
        }

        item {
            Column {
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

        val report = state.report ?: return@LazyColumn
        item {
            Text(
                if (report.issues.isEmpty()) "נסרקו ${report.contactCount} אנשי קשר. הכל מסודר 🎉"
                else "נסרקו ${report.contactCount} אנשי קשר",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        items(Category.entries.filter { state.issuesIn(it).isNotEmpty() }) { category ->
            val issues = state.issuesIn(category)
            val chosen = issues.count { it.id in state.selected }
            Card(Modifier.fillMaxWidth().clickable { vm.show(Screen.Review(category)) }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(category.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("${issues.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Text(category.description, style = MaterialTheme.typography.bodySmall)
                    if (category.fixable || category.deletesNumbers) {
                        Text("$chosen מסומנים", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryScreen(category: Category, state: UiState, vm: MainViewModel) {
    val issues = state.issuesIn(category)
    val chosen = issues.count { it.id in state.selected }
    val selectable = category.fixable || category.deletesNumbers
    var confirming by remember { mutableStateOf<Action?>(null) }
    // Which action the backup-then-apply flow continues with once a file is picked.
    var afterBackup by remember { mutableStateOf(Action.FIX) }
    val backupThenApply = rememberBackupLauncher { vm.backup(it, thenApply = category to afterBackup) }

    Column(Modifier.fillMaxSize()) {
        Text(category.description, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        if (selectable) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { vm.setAll(category, true) }) { Text("סמן הכל") }
                TextButton(onClick = { vm.setAll(category, false) }) { Text("נקה סימון") }
            }
        }
        HorizontalDivider()
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(issues, key = { it.id }) { issue ->
                IssueCard(issue, state, vm, selectable = selectable)
            }
        }
        if (selectable) {
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
                        ) { Text("מחיקת $chosen מספרים") }
                    }
                }
            }
        }
    }

    confirming?.let { action ->
        val fix = action == Action.FIX
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(if (fix) "לבצע $chosen תיקונים?" else "למחוק $chosen מספרים?") },
            text = {
                Text(
                    (if (fix) "השינויים ייכתבו לאנשי הקשר" else "המספרים יימחקו מאנשי הקשר") +
                        " ויסונכרנו לחשבון גוגל. אפשר לבטל מיומן השינויים, ובכל זאת מומלץ לשמור גם גיבוי."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirming = null
                    afterBackup = action
                    backupThenApply.launch(backupFileName())
                }) { Text(if (fix) "גיבוי ותיקון" else "גיבוי ומחיקה") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirming = null }) { Text("ביטול") }
                    TextButton(onClick = {
                        confirming = null
                        vm.apply(category, action)
                    }) { Text("בלי גיבוי") }
                }
            },
        )
    }
}

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
            }
        }
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
        is Issue.Duplicates -> {
            Text(
                issue.reasons.joinToString(" · ") { reason ->
                    when (reason) {
                        DuplicateReason.SAME_PHONE -> "אותו מספר"
                        DuplicateReason.SAME_EMAIL -> "אותו מייל"
                        DuplicateReason.SAME_NAME -> "אותו שם"
                    }
                } + if (issue.confident) "" else " · כדאי לבדוק",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            issue.contacts.forEach { ContactDetails(it) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("אחרי איחוד:", style = MaterialTheme.typography.labelMedium)
            Title(issue.merged.name ?: "(ללא שם)")
            val details = issue.merged.phones + issue.merged.emails
            if (details.isNotEmpty()) Text(details.joinToString(", ") { ltr(it) }, style = small)
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

@Composable
private fun ContactDetails(contact: Contact) {
    Column {
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
