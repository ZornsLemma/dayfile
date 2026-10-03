@file:OptIn(ExperimentalFoundationApi::class)

package app.zornslemma.dayfile.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zornslemma.dayfile.R
import app.zornslemma.dayfile.R.plurals.delete_category_message_with_entries_no_date
import app.zornslemma.dayfile.R.plurals.delete_category_message_with_entries_range
import app.zornslemma.dayfile.R.string.category_item_menu_content_description
import app.zornslemma.dayfile.R.string.delete
import app.zornslemma.dayfile.R.string.delete_category_message_no_entries
import app.zornslemma.dayfile.R.string.delete_category_title
import app.zornslemma.dayfile.R.string.drag_handle_content_description
import app.zornslemma.dayfile.R.string.move_down
import app.zornslemma.dayfile.R.string.move_up
import app.zornslemma.dayfile.data.CategoryEntity
import app.zornslemma.dayfile.ui.CategoryEditViewModel.DeleteDialogState
import app.zornslemma.dayfile.ui.components.MyDropdownMenuItem
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    viewModel: CategoryEditViewModel,
    onBack: () -> Unit,
    onAddCategory: () -> Unit,
    onEditCategory: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val deleteDialogState by viewModel.deleteDialogState.collectAsStateWithLifecycle()
    val errorMessageRes by viewModel.errorMessageRes.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.categories_title)) },
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
        floatingActionButton = {
            FloatingActionButton(onClick = onAddCategory) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.add_category_content_description),
                )
            }
        },
    ) { innerPadding ->
        CategoryList(
            categories = categories,
            onReorder = viewModel::reorder,
            onCommitOrder = viewModel::commitOrder,
            onMoveUp = viewModel::moveUp,
            onMoveDown = viewModel::moveDown,
            onToggleEnabled = viewModel::toggleEnabled,
            onEdit = onEditCategory,
            onDelete = viewModel::openDeleteDialog,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }

    deleteDialogState?.let { state ->
        DeleteCategoryDialog(
            state = state,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::closeDeleteDialog,
        )
    }

    // Rendered here rather than in CategoryAddEditScreen because the ViewModel is shared with it
    // and a save always pops the form immediately, so by the time an error is set this screen is
    // the one composed. That also means a failed save is no longer silent: previously the form
    // popped either way and the write simply vanished.
    errorMessageRes?.let { messageRes ->
        AlertDialog(
            onDismissRequest = viewModel::consumeError,
            title = { Text(stringResource(R.string.categories_title)) },
            text = { Text(stringResource(messageRes)) },
            confirmButton = {
                TextButton(onClick = viewModel::consumeError) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun CategoryList(
    categories: List<CategoryEntity>,
    onReorder: (Int, Int) -> Unit,
    onCommitOrder: () -> Unit,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
    onToggleEnabled: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The reorderable library mutates the list locally during drag gestures to provide smooth
    // visual feedback. We therefore keep a local snapshot of the categories here and sync it
    // from the ViewModel's flow when that source of truth changes. This avoids writing every
    // intermediate drag position to the database while still persisting the final order
    // (the ViewModel's commitOrder is invoked when the drag gesture ends).
    val displayedCategories = remember { mutableStateListOf<CategoryEntity>() }

    LaunchedEffect(categories) {
        displayedCategories.clear()
        displayedCategories.addAll(categories)
    }

    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState =
        rememberReorderableLazyListState(lazyListState) { from, to ->
            displayedCategories.add(to.index, displayedCategories.removeAt(from.index))
            onReorder(from.index, to.index)
            hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }

    LazyColumn(
        state = lazyListState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items = displayedCategories, key = { _, item -> item.id }) { index, category ->
            ReorderableItem(reorderableLazyListState, key = category.id) { isDragging ->
                val dragHandleModifier =
                    Modifier.draggableHandle(
                        onDragStarted = {
                            hapticFeedback.performHapticFeedback(
                                HapticFeedbackType.GestureThresholdActivate
                            )
                        },
                        onDragStopped = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            onCommitOrder()
                        },
                    )
                CategoryItem(
                    category = category,
                    index = index,
                    isLast = index == displayedCategories.lastIndex,
                    dragHandleModifier = dragHandleModifier,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onToggleEnabled = onToggleEnabled,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    isDragging = isDragging,
                )
            }
        }
    }
}

@Composable
private fun CategoryItem(
    category: CategoryEntity,
    index: Int,
    isLast: Boolean,
    dragHandleModifier: Modifier,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
    onToggleEnabled: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    isDragging: Boolean,
) {
    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)
    var showMenu by remember { mutableStateOf(false) }
    val textColor =
        if (category.enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
        }
    Surface(modifier = Modifier.fillMaxWidth(), shadowElevation = elevation) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    dragHandleModifier.size(48.dp).testTag("category_drag_handle_${category.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = stringResource(drag_handle_content_description),
                )
            }
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                modifier = Modifier.weight(1f),
            )

            Switch(
                checked = category.enabled,
                onCheckedChange = { onToggleEnabled(category.id) },
                modifier = Modifier.testTag("category_switch_${category.id}"),
            )

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.testTag("category_menu_button_${category.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(category_item_menu_content_description),
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    MyDropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        onClick = {
                            showMenu = false
                            onEdit(category.id)
                        },
                    )
                    MyDropdownMenuItem(
                        text = { Text(stringResource(move_up)) },
                        onClick = {
                            showMenu = false
                            onMoveUp(category.id)
                        },
                        enabled = index != 0,
                    )
                    MyDropdownMenuItem(
                        text = { Text(stringResource(move_down)) },
                        onClick = {
                            showMenu = false
                            onMoveDown(category.id)
                        },
                        enabled = !isLast,
                    )
                    MyDropdownMenuItem(
                        text = { Text(stringResource(delete)) },
                        onClick = {
                            showMenu = false
                            onDelete(category)
                        },
                        enabled = !category.enabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteCategoryDialog(
    state: DeleteDialogState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val categoryName = state.category.name
    val stats = state.stats
    val hasEntries = stats.count > 0

    val message =
        if (hasEntries) {
            val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            val start = stats.earliestDate?.format(formatter) ?: ""
            val end = stats.latestDate?.format(formatter) ?: ""
            // The entry table's UNIQUE (category_id, date) index forces one category's entries onto
            // distinct dates, so earliestDate == latestDate exactly when count == 1 (see the
            // plurals comment in strings.xml for the knock-on effect on which plural quantities can
            // occur). Branch on the date equality rather than count == 1: it states the actual
            // display rule (show a range only when there is one) and stays truthful if a foreign
            // or hand-edited database ever violated the unique index.
            if (stats.earliestDate != null && stats.earliestDate == stats.latestDate) {
                pluralStringResource(
                    delete_category_message_with_entries_no_date,
                    stats.count,
                    stats.count,
                )
            } else {
                pluralStringResource(
                    delete_category_message_with_entries_range,
                    stats.count,
                    stats.count,
                    start,
                    end,
                )
            }
        } else {
            stringResource(delete_category_message_no_entries)
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon =
            if (hasEntries) {
                {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else null,
        title = { Text(stringResource(delete_category_title, categoryName)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors =
                    if (hasEntries) {
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
            ) {
                Text(stringResource(delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
