package app.contactscleaner.me

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.contactscleaner.core.me.ActivationInput
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun ltr(text: String) = "⁦$text⁩"

private val DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

private fun formatTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DATE_TIME)

/**
 * Connect to ME, see who saved you and you didn't save back, and queue them
 * to be added a small batch a day. [onContactsChanged] asks the main screen
 * to rescan after contacts were added or ME data changed.
 */
@Composable
fun MeScreen(vm: MeViewModel, showMessage: (String) -> Unit, onContactsChanged: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.message, state.error) {
        (state.error ?: state.message)?.let {
            showMessage(it)
            vm.dismissMessage()
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Disclaimer() }
        val data = state.data
        if (data == null) {
            item { ConnectCard(state, vm, onContactsChanged) }
        } else {
            item { SummaryCard(state, data, vm, onContactsChanged) }
            item { QueueCard(state, data, vm, onContactsChanged) }
            item { SettingsCard(state, data, vm) }
            item { ListHeader(state, vm) }
            items(state.visible, key = { it.phone }) { person ->
                val selected = person.phone in state.selected
                Card(
                    colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    else CardDefaults.cardColors(),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = selected, onCheckedChange = { vm.toggle(person.phone) })
                        Column(Modifier.weight(1f)) {
                            Text(person.importName(data.settings.prefix), style = MaterialTheme.typography.titleSmall)
                            Text(ltr(person.phone), style = MaterialTheme.typography.bodySmall)
                            if (person.savedYouAs.isNotEmpty()) {
                                Text(
                                    "שמר/ה אותך בשם: " + person.savedYouAs.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (state.toImport.isEmpty()) {
                item { Text("אין כרגע אנשים שממתינים לייבוא: כל מי ש-ME מכירה כבר שמור אצלך או בתור.") }
            }
            item {
                Button(
                    onClick = vm::enqueue,
                    enabled = state.selected.isNotEmpty() && state.busy == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("הוספת ${state.selected.size} לתור הייבוא") }
            }
            item { DangerZone(state, vm, onContactsChanged) }
        }
    }
}

@Composable
private fun Disclaimer() {
    Text(
        "כלי עצמאי, לא של ME. החיבור משתמש בממשק לא רשמי של ME, שעלול להשתנות או להפסיק לעבוד. " +
            "הבקשות יוצאות ישירות מהטלפון ל-ME, בלי שרת באמצע, ושום קוד או טוקן לא נשמר.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ConnectCard(state: MeUiState, vm: MeViewModel, onContactsChanged: () -> Unit) {
    val context = LocalContext.current
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("חיבור ל-ME", style = MaterialTheme.typography.titleMedium)
            Text("1. פתחו את WhatsApp דרך הכפתור.\n2. שלחו את ההודעה Connectme.\n3. העתיקו את התשובה של ME.\n4. חזרו והדביקו אותה כאן.")
            OutlinedButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MeConfig.ACTIVATION_BOT)))
            }) { Text("פתחו את בוט האימות של ME") }

            // Links and numbers read left-to-right.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = state.pasted,
                    onValueChange = vm::setPasted,
                    label = { Text("ההודעה מ-ME, הקישור או הקוד") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val parsed = state.parsed
            if (parsed is ActivationInput.NeedsPhone || state.manualPhone.isNotEmpty()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = state.manualPhone,
                        onValueChange = vm::setManualPhone,
                        label = { Text("מספר הטלפון של חשבון ה-ME") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            when (parsed) {
                is ActivationInput.Ready -> Text("מתחבר עם המספר ${ltr("+" + parsed.phone)}. ודאו שזה המספר שלכם.", fontWeight = FontWeight.Medium)
                is ActivationInput.NeedsPhone -> Text("נמצא קוד. הקלידו את המספר שאליו הוא נשלח.")
                is ActivationInput.Invalid -> Text(parsed.message, color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
            Text(
                "הקוד נותן גישה לחשבון ה-ME שלכם. הוא נשלח רק ל-ME, ישירות מהטלפון, ונמחק מהמסך אחרי ההתחברות.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.connect(onContactsChanged) },
                    enabled = parsed is ActivationInput.Ready && state.busy == null,
                ) { Text(state.busy?.takeIf { it.isNotEmpty() } ?: "התחברות ומשיכת הרשימה") }
                if (state.pasted.isNotEmpty()) {
                    TextButton(onClick = vm::clearPasted) { Text("מחיקת ההודעה") }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: MeUiState, data: MeData, vm: MeViewModel, onContactsChanged: () -> Unit) {
    var reconnect by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("מי שמר אותך", style = MaterialTheme.typography.titleMedium)
            Text("עודכן: ${formatTime(data.fetchedAt)}", style = MaterialTheme.typography.labelMedium)
            Text("התקבלו ${data.received} רשומות · ${data.people.size} אנשים שונים")
            Text("${data.people.count { it.unsavedInMe }} שמרו אותך ואתה לא (לפי ME)")
            Text("מתוכם ${state.alreadyInPhone} כבר בטלפון · ${state.toImport.size} ממתינים לבחירה · ${data.queue.size} בתור")
            if (data.rejected > 0) Text("${data.rejected} רשומות בלי מספר תקין לא נכללו", style = MaterialTheme.typography.bodySmall)
            Text(
                "\"לא שמור\" הוא לפי הנתונים של ME, שלא תמיד מעודכנים. לכן כל מספר נבדק גם מול אנשי הקשר בטלפון לפני שהוא נוסף." +
                    if (data.maybePartial) " ME רמזה שיש עוד עמודים שלא התקבלו, אז ייתכן שהרשימה חלקית." else " אין דרך לוודא שהרשימה מ-ME מלאה.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { reconnect = !reconnect }) { Text(if (reconnect) "סגירה" else "רענון מ-ME (קוד חדש)") }
            if (reconnect) ConnectCard(state, vm, onContactsChanged)
        }
    }
}

@Composable
private fun QueueCard(state: MeUiState, data: MeData, vm: MeViewModel, onContactsChanged: () -> Unit) {
    if (data.queue.isEmpty() && data.imported == 0) return
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ייבוא הדרגתי", style = MaterialTheme.typography.titleMedium)
            Text("בתור: ${data.queue.size} · מנה יומית: ${data.settings.batchSize} · נוספו עד עכשיו: ${data.imported}")
            data.lastBatchAt?.let { Text("מנה אחרונה: ${formatTime(it)}", style = MaterialTheme.typography.bodySmall) }
            if (data.settings.paused) Text("מושהה", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.importNow(onContactsChanged) },
                    enabled = data.queue.isNotEmpty() && state.busy == null,
                ) { Text("ייבא מנה עכשיו") }
                OutlinedButton(onClick = { vm.setPaused(!data.settings.paused) }) {
                    Text(if (data.settings.paused) "המשך" else "השהיה")
                }
            }
            if (data.queue.isNotEmpty()) {
                TextButton(onClick = vm::clearQueue) { Text("ריקון התור") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCard(state: MeUiState, data: MeData, vm: MeViewModel) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("הגדרות ייבוא", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = data.settings.prefix,
                onValueChange = { p -> vm.updateSettings { it.copy(prefix = p) } },
                label = { Text("קידומת לשם") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("כמה להוסיף בכל יום", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 20, 50, 100).forEach { size ->
                    FilterChip(
                        selected = data.settings.batchSize == size,
                        onClick = { vm.updateSettings { it.copy(batchSize = size) } },
                        label = { Text("$size") },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("להוסיף הערה \"שמר/ה אותי בשם…\"", Modifier.weight(1f))
                Switch(checked = data.settings.addNote, onCheckedChange = { on -> vm.updateSettings { it.copy(addNote = on) } })
            }
            Text(
                "הייבוא ההדרגתי נועד לשמור על קצב סביר ולתת לך לבדוק כל מנה. הוא לא מבטיח ש-WhatsApp לא יגביל את החשבון, ולא בודק למי יש WhatsApp.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ListHeader(state: MeUiState, vm: MeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("שמרו אותך ואתה לא (${state.toImport.size})", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.search,
            onValueChange = vm::setSearch,
            label = { Text("חיפוש לפי שם או מספר") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(onClick = vm::selectVisible) {
                Text(if (state.search.isBlank()) "סמן הכל (${state.visible.size})" else "סמן את ${state.visible.size} המסוננים")
            }
            TextButton(onClick = vm::clearSelection) { Text("נקה סימון") }
        }
        Text("נבחרו ${state.selected.size}", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DangerZone(state: MeUiState, vm: MeViewModel, onContactsChanged: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    TextButton(onClick = { confirming = true }, enabled = state.busy == null) {
        Text("מחיקת נתוני ME וסיום", color = MaterialTheme.colorScheme.error)
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("למחוק את נתוני ME?") },
            text = { Text("הרשימה, התור וההגדרות יימחקו מהטלפון. אנשי קשר שכבר נוספו נשארים.") },
            confirmButton = {
                Button(onClick = {
                    confirming = false
                    vm.clearAll(onContactsChanged)
                }) { Text("מחיקה") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("ביטול") } },
        )
    }
}
