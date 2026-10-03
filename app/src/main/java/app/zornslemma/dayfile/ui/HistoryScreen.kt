@file:OptIn(ExperimentalMaterial3Api::class)

package app.zornslemma.dayfile.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zornslemma.dayfile.R
import app.zornslemma.dayfile.ui.components.MyDropdownMenuItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    date: LocalDate,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by
        viewModel.uiStateFlow.collectAsStateWithLifecycle(
            HistoryUiState(null, emptyList(), emptyList(), false)
        )
    val filterCategoryId = uiState.filterCategoryId
    val items = uiState.items
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

    // "All" is a presentation-level sentinel rather than a category, so it is added here rather
    // than in the ViewModel, which deliberately holds no Android resources. Option names are raw;
    // deleted options are labelled at render time below.
    val categoryOptions =
        listOf(CategoryOption(null, stringResource(R.string.category_all), false)) +
            uiState.categoryOptions

    // The dropdown is a transient popup, so we use plain remember.
    var dropdownExpanded by remember { mutableStateOf(false) }

    // Explicit list state so the newest-entry scroll below can be programmatic.
    val listState = rememberLazyListState()

    // Scroll to the top when the displayed items GENUINELY change: the screen is opened, the
    // filter changes, or history rows appear/are pruned. Newest-first-on-arrival is this
    // recovery tool's core guarantee. The signature check exists so a configuration change
    // (rotation) does NOT reset the list: this effect re-runs in the fresh post-rotation
    // composition even though the items are identical, and without the check it would yank
    // the restored scroll position back to the top - making this the only screen in the app
    // that lost scroll position on rotation. The list state itself saves the position; the
    // saved signature is what lets the re-run distinguish "same items, just rotated" from a
    // real change.
    // Why (size, first id, last id) is a sufficient fingerprint - two different lists cannot
    // share it without being identical:
    // * Filters are nested or disjoint. "All" is a superset of any single-category filter and
    //   two different category filters are disjoint (an entry has one category), so a filter
    //   change either yields an identical list (same size) or cannot share the first id. The
    //   "include deleted" toggle only removes/re-adds whole items, so any real change moves
    //   the size.
    // * The writer set is append/prune only. New rows come from the home-screen capture
    //   pipeline (including a debounced flush still in flight when this screen was opened -
    //   the live case this scroll exists to serve) and land as the newest row, moving the
    //   first id; retention pruning removes oldest rows and clear-history empties the list,
    //   moving the last id or the size. Rows are never edited in place and text/savedAt are
    //   immutable per id, so ids are complete identity.
    // The mechanism does not depend on the first composition seeing no data: if the flow
    // delivered items into the very first run, the null saved signature still scrolls (a no-op
    // on a fresh list state already at position 0) and records the signature one step earlier;
    // either ordering converges on the same state.
    val itemsSignature =
        listOf(
                items.size,
                (items.firstOrNull()?.id ?: 0L).toInt(),
                (items.lastOrNull()?.id ?: 0L).toInt(),
            )
            .hashCode()
    var scrolledSignature by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(itemsSignature) {
        if (items.isNotEmpty() && itemsSignature != scrolledSignature) {
            listState.scrollToItem(0)
            scrolledSignature = itemsSignature
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.history_title),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = date.format(dateFormatter),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_content_description),
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(modifier = modifier.fillMaxSize().padding(innerPadding)) {
            // Category filter dropdown (Material 3 exposed dropdown with filled TextField)
            // The selected filter and "include deleted" toggle are global UI state persisted
            // in AppStateRepository (DataStore). They persist across History opens within a
            // 10-minute foreground gap and reset afterwards: the reset is the same 10-minute
            // background rule that also resets the selected date and prunes history.
            val filterCategory = categoryOptions.firstOrNull { it.id == filterCategoryId }
            // The elvis below is defensive only: resolveEffectiveFilter applies the same
            // visibility predicate as visibleOptions, so a non-null effective filter always
            // matches an option in categoryOptions and the fallback cannot currently fire.
            // It shares the sentinel's string deliberately - there is only one "All" resource.
            val filterCategoryName =
                filterCategory?.let {
                    if (it.deleted) deletedCategoryLabel(it.name, requireNotNull(it.id))
                    else it.name
                } ?: stringResource(R.string.category_all)
            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                TextField(
                    value = filterCategoryName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.history_filter_label)) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    modifier =
                        Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                            .testTag("history_filter_field"),
                )
                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    categoryOptions.forEach { option ->
                        MyDropdownMenuItem(
                            text = {
                                Text(
                                    if (option.deleted) {
                                        deletedCategoryLabel(option.name, requireNotNull(option.id))
                                    } else {
                                        option.name
                                    }
                                )
                            },
                            onClick = {
                                viewModel.setSelectedCategory(option.id)
                                dropdownExpanded = false
                            },
                            modifier =
                                Modifier.testTag(
                                    if (option.id == null) "history_filter_option_all"
                                    else "history_filter_option_${option.id}"
                                ),
                        )
                    }
                }
            }

            // Include deleted categories toggle
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.history_include_deleted),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                // No explicit reset to "All" is needed here when hiding deleted categories:
                // the ViewModel resolves the effective filter against the current setting and
                // self-heals the persisted filter when it stops resolving.
                Switch(
                    checked = uiState.includeDeleted,
                    onCheckedChange = { viewModel.setIncludeDeleted(it) },
                )
            }

            if (items.isEmpty()) {
                // Distinguish "nothing recorded at all" from "nothing for the filtered
                // category": with a filter active, the date-level wording would wrongly imply
                // the date itself has no history.
                val emptyMessageRes =
                    if (filterCategoryId == null) {
                        R.string.history_empty
                    } else {
                        R.string.history_empty_filtered
                    }
                Text(
                    text = stringResource(emptyMessageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(items = items, key = { _, it -> it.id }) { index, item ->
                        // Only show category headers when viewing "All" categories, since
                        // the heading is redundant when a single category is selected.
                        val showHeader =
                            filterCategoryId == null &&
                                (index == 0 || items[index - 1].categoryId != item.categoryId)
                        if (showHeader) {
                            Text(
                                text =
                                    if (item.categoryDeleted) {
                                        deletedCategoryLabel(item.categoryName, item.categoryId)
                                    } else {
                                        item.categoryName
                                    },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier =
                                    Modifier.padding(
                                            start = 16.dp,
                                            end = 16.dp,
                                            top = 12.dp,
                                            bottom = 4.dp,
                                        )
                                        .testTag("history_header_${item.categoryId}"),
                            )
                        }
                        HistoryItem(item)
                    }
                }
            }
        }
    }
}

// Renders a deleted category's name with its "(deleted, ID n)" marker. Applied at every render
// site for deleted categories (dropdown items, the selected-filter field, and category headers
// under "All"): the marker is what distinguishes a live category from a same-named archived one,
// and signals that the filter self-heals to "All" once "include deleted" is switched off.
// Data-layer names stay raw (see HistoryLogic); this is the screen's single formatting point
// for the marker.
@Composable
private fun deletedCategoryLabel(name: String, id: Long): String =
    stringResource(R.string.history_deleted_category_option, name, id)

@Composable
private fun HistoryItem(item: HistoryDisplayItem) {
    // Absolute time shown with full date+time (the edit timestamp, which may differ from
    // the logical date the entry relates to).
    val absTime =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .format(Instant.ofEpochMilli(item.savedAt).atZone(ZoneId.systemDefault()))
    // Relative time uses second resolution as the minimum, but still escalates to
    // minutes/hours/days for older entries (never "42342 seconds ago").
    val relTime =
        DateUtils.getRelativeTimeSpanString(
                item.savedAt,
                System.currentTimeMillis(),
                DateUtils.SECOND_IN_MILLIS,
            )
            .toString()

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "$relTime $middleDot $absTime",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        SelectionContainer { Text(text = item.text, style = MaterialTheme.typography.bodyMedium) }
    }
}
