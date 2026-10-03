package app.zornslemma.dayfile.ui

import android.annotation.SuppressLint
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zornslemma.dayfile.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onAboutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayStartTime by viewModel.dayStartTime.collectAsStateWithLifecycle()
    val restoreState by viewModel.restoreState.collectAsStateWithLifecycle()
    val csvBomEnabled by viewModel.csvBomEnabled.collectAsStateWithLifecycle()
    val fileOpState by viewModel.fileOpState.collectAsStateWithLifecycle()
    val historyRetentionDays by viewModel.historyRetentionDays.collectAsStateWithLifecycle()
    val historyEmpty by viewModel.historyEmpty.collectAsStateWithLifecycle()
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var pendingRestoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var showExportSheet by rememberSaveable { mutableStateOf(false) }
    var includeDisabled by rememberSaveable { mutableStateOf(false) }
    var showRestoreConfirm by rememberSaveable { mutableStateOf(false) }
    var showHistoryRetentionDialog by rememberSaveable { mutableStateOf(false) }
    var retentionText by rememberSaveable { mutableStateOf("") }
    var showClearHistoryConfirm by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    val isoDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy_MM_dd"))
    val defaultBackupName = stringResource(R.string.default_backup_filename, isoDate)
    val defaultExportName = stringResource(R.string.default_export_filename, isoDate)

    val backupLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            uri?.let { viewModel.backup(it) }
        }

    val restoreLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri
            ->
            uri?.let { pendingRestoreUri = it }
        }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/csv")
        ) { uri ->
            uri?.let { viewModel.export(it, includeDisabled) }
        }

    LaunchedEffect(fileOpState) {
        when (val state = fileOpState) {
            is FileOpState.Success -> {
                val msg = context.getString(state.messageResId)
                snackbarHostState.showSnackbar(message = msg, withDismissAction = true)
                viewModel.consumeFileOpState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_content_description),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(innerPadding).testTag("settings_list")
        ) {
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_general))
            }
            item {
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(
                                R.string.settings_day_starts_at,
                                dayStartTime.format(timeFormatter),
                            )
                        )
                    },
                    modifier = Modifier.clickable { showTimePicker = true },
                )
            }

            item { SettingsSectionHeader(title = stringResource(R.string.settings_section_data)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_backup_title)) },
                    supportingContent = {
                        Text(stringResource(R.string.settings_backup_supporting))
                    },
                    modifier = Modifier.clickable { backupLauncher.launch(defaultBackupName) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_restore_title)) },
                    supportingContent = {
                        Text(stringResource(R.string.settings_restore_supporting))
                    },
                    modifier = Modifier.clickable { showRestoreConfirm = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.export_title)) },
                    supportingContent = { Text(stringResource(R.string.export_supporting)) },
                    modifier = Modifier.clickable { showExportSheet = true },
                )
            }
            item {
                // The whole row toggles the setting; the Switch defers to it via
                // onCheckedChange = null so a tap on the switch isn't handled twice.
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_bom_title)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (csvBomEnabled) R.string.settings_bom_supporting_on
                                else R.string.settings_bom_supporting_off
                            )
                        )
                    },
                    trailingContent = { Switch(checked = csvBomEnabled, onCheckedChange = null) },
                    modifier = Modifier.clickable { viewModel.setCsvBomEnabled(!csvBomEnabled) },
                )
            }
            item {
                ListItem(
                    headlineContent = {
                        Text(
                            context.resources.getQuantityString(
                                R.plurals.settings_history_retention_title,
                                historyRetentionDays,
                                historyRetentionDays,
                            )
                        )
                    },
                    supportingContent = {
                        if (historyRetentionDays == 0) {
                            Text(stringResource(R.string.settings_history_retention_zero_subtitle))
                        } else {
                            Text(stringResource(R.string.settings_history_retention_supporting))
                        }
                    },
                    modifier =
                        Modifier.clickable {
                            retentionText = historyRetentionDays.toString()
                            showHistoryRetentionDialog = true
                        },
                )
            }
            item {
                // Disabled while there is no history to clear.
                // ENHANCE: ChatGPT told me that a current alpha of Compose adds an enabled
                // parameter to ListItem. If true, we should use that once it's available to us
                // instead of using disabledAlpha.
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.settings_clear_history_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.settings_clear_history_supporting))
                    },
                    modifier =
                        Modifier.alpha(if (historyEmpty) disabledAlpha else 1f).clickable(
                            enabled = !historyEmpty
                        ) {
                            showClearHistoryConfirm = true
                        },
                )
            }

            item { SettingsSectionHeader(title = stringResource(R.string.settings_section_about)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.title_about_app_name)) },
                    modifier = Modifier.clickable { onAboutClick() },
                )
            }
        }
    }

    if (showTimePicker) {
        val timePickerState =
            rememberTimePickerState(
                initialHour = dayStartTime.hour,
                initialMinute = dayStartTime.minute,
            )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateDayStartTime(
                            LocalTime.of(timePickerState.hour, timePickerState.minute)
                        )
                        showTimePicker = false
                    }
                ) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
            text = { TimePicker(state = timePickerState) },
        )
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text(stringResource(R.string.title_restore_from_backup)) },
            text = { Text(stringResource(R.string.message_restore_from_backup_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestoreConfirm = false
                        restoreLauncher.launch(arrayOf("*/*"))
                    }
                ) {
                    Text(stringResource(R.string.button_select_backup_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (pendingRestoreUri != null) {
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text(stringResource(R.string.title_restore_from_backup)) },
            text = { Text(stringResource(R.string.message_restore_from_backup_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingRestoreUri!!
                        pendingRestoreUri = null
                        viewModel.restore(uri)
                    }
                ) {
                    Text(stringResource(R.string.button_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showExportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showExportSheet = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Text(
                    text = stringResource(R.string.export_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.export_include_disabled),
                        modifier = Modifier.weight(1f),
                    )

                    Switch(checked = includeDisabled, onCheckedChange = { includeDisabled = it })
                }

                Button(
                    onClick = {
                        showExportSheet = false
                        exportLauncher.launch(defaultExportName)
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(stringResource(R.string.export_button))
                }
            }
        }
    }

    // Retention dialog: validates input. OK is disabled while the field is blank, and 0 is
    // accepted explicitly (no silent coercion - e.g. blank -> 7, or 0 -> 1), consistent with the
    // "no dishonest UI" principle that the time-picker choice (full hh:mm rather than hour-only)
    // relies on: the user's input must not be rewritten after they press OK.
    if (showHistoryRetentionDialog) {
        val parsedRetention = retentionText.toIntOrNull()
        AlertDialog(
            onDismissRequest = { showHistoryRetentionDialog = false },
            title = { Text(stringResource(R.string.settings_history_retention_dialog_title)) },
            text = {
                TextField(
                    value = retentionText,
                    onValueChange = { retentionText = it.filter { c -> c.isDigit() }.take(3) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.testTag("settings_retention_days_field"),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = parsedRetention != null,
                    onClick = {
                        viewModel.updateHistoryRetentionDays(requireNotNull(parsedRetention))
                        showHistoryRetentionDialog = false
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showHistoryRetentionDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text(stringResource(R.string.settings_clear_history_title)) },
            text = { Text(stringResource(R.string.settings_clear_history_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearHistoryConfirm = false
                        viewModel.clearAllHistory()
                    }
                ) {
                    Text(
                        stringResource(R.string.button_clear_history),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    when (val state = restoreState) {
        // InProgress and RestartRequired deliberately render the same non-dismissable dialog:
        // it appears the moment the user confirms the restore (the ViewModel sets InProgress
        // synchronously before the work starts) and stays up through the restart countdown.
        // This closes the window in which the user could navigate out mid-restore — the
        // restore's blocking I/O does not respond to coroutine cancellation, so it would
        // otherwise complete with no UI left to show the restart prompt.
        //
        // The one thing that must differ is the wording for a restart-after-failure: the
        // replacement did NOT take effect, and telling the user their data is being applied
        // would be a lie. That variant still restarts, because the alternative is a crash on
        // the next database-backed screen (the process is holding a closed Room graph).
        RestoreState.InProgress,
        is RestoreState.RestartRequired -> {
            val restartFailed = state is RestoreState.RestartRequired && !state.restoredDataApplied
            AlertDialog(
                onDismissRequest = { /* prevent dismissal */ },
                title = { Text(stringResource(R.string.title_app_will_restart)) },
                text = {
                    Text(
                        stringResource(
                            if (restartFailed) R.string.message_restore_failed_restarting
                            else R.string.message_applying_restored_data
                        )
                    )
                },
                confirmButton = {},
            )
            // Only a completed restore starts the countdown; while InProgress the dialog
            // simply holds the screen.
            if (state is RestoreState.RestartRequired) {
                LaunchedEffect(Unit) {
                    delay(1500)
                    safeRestartApp(context)
                }
            }
        }
        is RestoreState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.consumeRestoreState() },
                title = { Text(stringResource(R.string.title_restore_from_backup)) },
                text = {
                    Text(
                        if (state.formatArgs.isEmpty()) {
                            stringResource(state.messageResId)
                        } else {
                            stringResource(state.messageResId, *state.formatArgs.toTypedArray())
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.consumeRestoreState() }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }
        RestoreState.Idle -> {
            /* nothing */
        }
    }

    when (val state = fileOpState) {
        is FileOpState.Error -> {
            AlertDialog(
                onDismissRequest = { viewModel.consumeFileOpState() },
                title = { Text(stringResource(state.titleResId)) },
                text = {
                    Text(
                        if (state.formatArgs.isEmpty()) {
                            stringResource(state.messageResId)
                        } else {
                            stringResource(state.messageResId, *state.formatArgs.toTypedArray())
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.consumeFileOpState() }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }
        FileOpState.Idle -> {
            /* nothing */
        }
        is FileOpState.Success -> {
            /* handled by LaunchedEffect snackbar */
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                // Exposed as a heading so TalkBack users can jump between sections.
                .semantics { heading() },
    )
}
