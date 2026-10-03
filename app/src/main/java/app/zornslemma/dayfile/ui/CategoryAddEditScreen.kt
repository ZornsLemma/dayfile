package app.zornslemma.dayfile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zornslemma.dayfile.R
import app.zornslemma.dayfile.data.CapitalizationMode
import app.zornslemma.dayfile.ui.components.MyDropdownMenuItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryAddEditScreen(
    viewModel: CategoryEditViewModel,
    categoryId: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val categoriesLoaded by viewModel.categoriesLoaded.collectAsStateWithLifecycle()

    // The normal route reaches this form from the category list, so Room will usually have
    // loaded by the time the form is first composed. Process-death restoration is different:
    // navigation can recreate this destination before the shared ViewModel's Room flow has
    // emitted. Creating rememberTextFieldState()/rememberSaveable then would permanently retain
    // placeholder values from a temporarily empty list, rather than updating when the real
    // category arrived. Wait until the first database emission, then create form state once from
    // the actual snapshot.
    //
    // Keep Back registered while waiting so a quick Back press still returns to the category
    // list rather than being handled by the navigation host while no form has been composed.
    //
    // The blank return is deliberate: it is reached only when Room's first emission has not
    // arrived, which on the normal route cannot happen (the list screen is behind this one and
    // has already collected), so in practice it is a single frame of a process-death restoration.
    // A loading indicator would be a poor trade - it would need its own rememberSaveable to avoid
    // re-showing across rotation, and it would be visible in the normal case for no benefit.
    BackHandler(enabled = !categoriesLoaded) { onBack() }
    if (!categoriesLoaded) {
        return
    }

    val existing = categoryId?.let { id -> categories.firstOrNull { it.id == id } }

    // A loaded list without the requested category means a stale/invalid route. Return to the
    // category list rather than interpreting the missing row as an add form: allowing Save on
    // placeholder defaults would create a new category instead of editing the requested one.
    if (categoryId != null && existing == null) {
        LaunchedEffect(categoryId) { onBack() }
        return
    }

    val initialName = existing?.name ?: ""
    val initialAutoCorrect = existing?.autoCorrect ?: true
    val initialCapitalization = existing?.capitalization ?: CapitalizationMode.NONE

    // All editable form state survives rotation/process death via rememberSaveable
    // (DEVELOPMENT.md's state-ownership policy): the name, the keyboard hints and the discard
    // dialog are half-typed user input or UI chrome whose silent loss would confuse the user.
    // The previous plain-remember version also had a nasty side effect: rotation reset every
    // field to its initial value, making a dirty form look clean and silently skipping the
    // discard prompt. rememberTextFieldState carries TextFieldState.Saver, so text and cursor
    // restore for free and the initial value is used only when nothing is saved, so the
    // seeding semantics are unchanged. The dropdown's expanded flag stays plain remember: a
    // transient popup, which DEVELOPMENT.md allows to close on rotation.
    val nameState = rememberTextFieldState(initialName)
    var autoCorrect by rememberSaveable { mutableStateOf(initialAutoCorrect) }
    var capitalization by rememberSaveable { mutableStateOf(initialCapitalization) }
    var capitalizationExpanded by remember { mutableStateOf(false) }
    var nameErrorRes by rememberSaveable { mutableStateOf<Int?>(null) }

    val capitalizationOptions = CapitalizationMode.values()

    // Focus the name field as soon as the screen opens so typing can start immediately.
    // The request must wait until after composition (LaunchedEffect) or the target node
    // isn't attached yet. This also re-focuses after rotation, re-opening a keyboard the
    // user had dismissed - the platform's own behaviour for a focused editable field,
    // and consistent with the app's restore-on-rotation philosophy, so accepted.
    // ENHANCE: It may be better to avoid auto-focusing if we are opened to edit an existing item
    // rather than add a new one, but let's see how things work out in practice first.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(nameState.text) { nameErrorRes = null }

    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

    val isDirty =
        nameState.text.toString().trim() != initialName.trim() ||
            autoCorrect != initialAutoCorrect ||
            capitalization != initialCapitalization

    fun requestBack() {
        if (isDirty) showDiscardDialog = true else onBack()
    }

    BackHandler() { requestBack() }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.discard_changes_title)) },
            text = { Text(stringResource(R.string.discard_changes_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onBack()
                    }
                ) {
                    Text(stringResource(R.string.discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (existing != null) R.string.edit_category_title
                            else R.string.add_category_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.close_content_description),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val trimmed = nameState.text.toString().trim()
                            val duplicate =
                                categories.any {
                                    it.name.equals(trimmed, ignoreCase = true) &&
                                        it.id != categoryId
                                }
                            if (duplicate) {
                                nameErrorRes = R.string.category_name_error_duplicate
                                return@TextButton
                            }
                            nameErrorRes = null
                            viewModel.upsertCategory(
                                trimmed,
                                autoCorrect,
                                capitalization,
                                categoryId,
                            )
                            onBack()
                        },
                        enabled = nameState.text.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.save))
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            // verticalScroll: in landscape the form no longer fits on screen - a plain Column
            // clips, leaving the capitalization dropdown unreachable. (Portrait hid the bug
            // because the whole form fits on any phone.) rememberScrollState is itself
            // saveable, so the scroll position survives rotation like the form state above.
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextField(
                state = nameState,
                isError = nameErrorRes != null,
                supportingText = { nameErrorRes?.let { Text(stringResource(it)) } },
                inputTransformation = InputTransformation.maxLength(maxCategoryNameLength),
                label = { Text(stringResource(R.string.category_name_label)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier =
                    Modifier.fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("category_name_field"),
            )

            Text(
                text = stringResource(R.string.keyboard_hints_title),
                style = MaterialTheme.typography.titleSmall,
            )

            Text(
                text = stringResource(R.string.keyboard_hints_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.autocorrect_label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = autoCorrect,
                    onCheckedChange = { autoCorrect = it },
                    modifier = Modifier.testTag("category_autocorrect_switch"),
                )
            }

            ExposedDropdownMenuBox(
                expanded = capitalizationExpanded,
                onExpandedChange = { capitalizationExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextField(
                    value = stringResource(capitalization.labelRes()),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.capitalization_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = capitalizationExpanded)
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .testTag("category_capitalization_field"),
                )
                ExposedDropdownMenu(
                    expanded = capitalizationExpanded,
                    onDismissRequest = { capitalizationExpanded = false },
                ) {
                    capitalizationOptions.forEach { option ->
                        MyDropdownMenuItem(
                            text = { Text(stringResource(option.labelRes())) },
                            onClick = {
                                capitalization = option
                                capitalizationExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}
