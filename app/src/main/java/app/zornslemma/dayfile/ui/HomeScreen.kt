package app.zornslemma.dayfile.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.then
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zornslemma.dayfile.R
import app.zornslemma.dayfile.data.CategoryEntity
import app.zornslemma.dayfile.data.EntryEntity
import app.zornslemma.dayfile.ui.components.MyDropdownMenuItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    homeViewModel: HomeViewModel,
    onCategories: () -> Unit,
    onSettings: () -> Unit,
    onHistory: (LocalDate) -> Unit,
    homeSessionResetToken: Int = 0,
    modifier: Modifier = Modifier,
) {
    val uiState = homeViewModel.uiStateFlow.collectAsStateWithLifecycle().value
    // If uiState is null, we really don't have anything to render at all - just leave the home
    // screen completely blank for the few ms on initial load.
    if (uiState != null) {
        val categories = uiState.categories
        val selectedDate = uiState.date
        val isProtected = uiState.isProtected

        // Dialog visibility is rememberSaveable (DEVELOPMENT.md's state-ownership policy): the
        // date picker must stay open across rotation. rememberDatePickerState is itself
        // saveable, so the in-dialog date selection survives too.
        var showDatePicker by rememberSaveable { mutableStateOf(false) }
        var focusedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }

        // A long background reset retains the current home back-stack entry so that its
        // ViewModel, field states, and scroll position need not be needlessly discarded. A
        // rememberSaveable date picker is the one piece of that retained state which must be
        // closed: its in-dialog date belongs to the previous session, and leaving it open would
        // let a confirmation move the selection back to that stale date. MainActivity increments
        // this token only for that retained-home reset case; when the reset starts from a child
        // route, the back stack is replaced and a fresh HomeScreen already starts closed.
        LaunchedEffect(homeSessionResetToken) {
            if (homeSessionResetToken > 0) {
                showDatePicker = false
            }
        }

        // Focus restoration gate (design and case-by-case rationale in HOME_SCREEN_NOTES.md §6).
        // focusedCategoryId alone cannot tell us WHY the composition was rebuilt: rotation,
        // returning from a child screen, process death and LazyColumn item recycling all produce
        // the same fresh composition. The orientation token separates them: restoredOrientation
        // is saved with the rest of the UI state, so on the first fresh composition after an
        // actual configuration change it still holds the pre-change orientation, while every
        // other cause of a fresh composition (child-screen return, recycling, process death
        // without rotation) restores it already equal to the live one. The mismatch between the
        // two is therefore the gate: open across exactly one configuration change, then closed -
        // the effects below call this "consuming the token", though no queue or channel is
        // involved - simply by writing the live orientation back into the saved state once the
        // restore pass has handled or declined the restore. Focused fields are restored after a
        // rotation and never re-grabbed on any other fresh composition: focus exists only where
        // the user put it or a configuration change restored it. An Int needs no custom Saver;
        // width/height were deliberately left out (IME show/hide can perturb them on some API
        // levels for no benefit here), so size-only changes such as split-screen entry do not
        // restore - accepted.
        //
        // The gate check is deliberately performed inside the LaunchedEffects below (which read
        // these state variables live when they run), not eagerly here: whether one effect's
        // write has already closed the gate must be visible to the other effects' reads
        // regardless of the order in which this pass's effects happen to execute.
        val currentOrientation = LocalConfiguration.current.orientation
        var restoredOrientation by rememberSaveable { mutableStateOf<Int?>(null) }

        // When the display rotates, we get re-composed and lose focus and have to go out of our way
        // to preserve it. When the current date changes, we don't get re-composed and a TextField
        // with focus retains it by default and we have to go out of our way to remove it.
        val focusManager = LocalFocusManager.current
        fun clearFocus() {
            focusedCategoryId =
                null // stop the focus retention logic undoing our focusManager.clearFocus()
            focusManager.clearFocus()
        }

        // Clearing focus when the app leaves the foreground (HOME_SCREEN_NOTES §7.6). The home
        // screen is both a viewer and the app's primary editor, so focus is a liability rather than
        // a help once the user is done with it: a field that kept focus across a backgrounding
        // would have the OSK re-popped on return, eating the screen they wanted to browse. The text
        // is already persisted immediately, so clearing focus loses nothing - resuming is a tap on
        // the field.
        //
        // Rotation is the one exception and is excluded by isChangingConfigurations(): while the
        // app stays on screen and the phone rotates, the user is still actively typing (or just
        // was) and losing focus there would break the §6 restore path. That guard is the standard
        // discriminator for exactly this distinction, and it is reliable here.
        //
        // Note the placement: this observer is scoped to the home screen's lifecycle owner, so it
        // only fires when HOME is the resumed destination. Pushing Settings/History/Categories
        // keeps the app RESUMED (no ON_PAUSE), so intra-app navigation is untouched, and the
        // observer is disposed when the home screen is navigated away from - child screens cannot
        // accidentally trigger it. Dialogs are separate windows and do not fire ON_PAUSE either.
        // The decision itself lives in shouldClearFocusOnPause below, so the rotation/background
        // distinction is unit-testable on the JVM without composing the screen.
        //
        // LocalActivity.current and LocalLifecycleOwner.current are @Composable reads, so they are
        // taken in the composition body here rather than inside the DisposableEffect lambda below
        // - a composable read inside a non-composable lambda is a compile error.
        val lifecycleOwner = LocalLifecycleOwner.current
        val activity = LocalActivity.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (
                    activity != null &&
                        event == Lifecycle.Event.ON_PAUSE &&
                        shouldClearFocusOnPause(activity.isChangingConfigurations())
                ) {
                    clearFocus()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // The toolbar date is deliberately split across two lines and two formatters rather than
        // one locale-aware call, because no single standard call reliably yields "weekday on its
        // own line, then the date". ofLocalizedDate(FULL) - the previous value here - puts the
        // weekday wherever the locale wants it (first in en-GB/en-US, LAST in ja-JP, sometimes
        // absent), so its shape varies across devices and that is the whole source of the
        // inconsistent wrapping. We instead ask the platform for the two pieces separately and
        // decide the line break ourselves:
        //
        //   "EEEE"  - full standalone weekday name, in the device locale (Saturday, or the
        //             locale's own word for it, e.g. Japanese 土曜日). This is the piece the user
        //             actually came to see: it is what makes "yesterday" and "three days ago"
        //             legible without counting.
        //   LONG   - full date without the weekday (15 August 2026 / August 15, 2026, or the
        //             locale's own form, e.g. Japanese 2026年8月15日). LONG is used rather than
        //             FULL because FULL would duplicate the weekday we already drew above;
        //             MEDIUM/SHORT abbreviate the month and are exactly the "2026/08/15" style
        //             being avoided.
        //
        // Both pull from the same CLDR data the OS already has, so this is locale-aware with
        // zero string resources and zero locale detection - only the line break and the
        // weekday-first ordering are our own presentation choices. The year is kept: on a daily
        // home screen it is the only thing that disambiguates a date the user may have navigated
        // back through.
        val weekdayFormatter = DateTimeFormatter.ofPattern("EEEE")
        val dateLabel =
            selectedDate.format(weekdayFormatter) to
                selectedDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))

        val enabledCategories = categories.filter { it.enabled }

        // This seems to give half-reasonable scroll position preservation as the user moves between
        // dates. I am not entirely sure what the ideal behaviour would be, never mind how to
        // implement it, so let's just go with this for now.
        val listState = rememberLazyListState()

        // Index of the currently focused category within the LazyColumn's items
        // (enabledCategories is exactly that item list), or -1 when there is no restore target.
        // -1 rather than null is deliberate: indexOfFirst already answers "not found" with -1,
        // so a remembered id that no longer matches an enabled category collapses into the same
        // -1 as "nothing remembered", and every consumer can test both cases with a single
        // comparison (focusedItemIndex < 0). A nullable Int would keep the two cases apart
        // (null vs -1) without any consumer wanting to distinguish them.
        val focusedItemIndex =
            focusedCategoryId?.let { id -> enabledCategories.indexOfFirst { it.id == id } } ?: -1

        // Restore pass, screen-level half. Runs while the gate is open - i.e. only on the first
        // fresh composition after a genuine configuration change. Two outcomes:
        //
        // - Nothing to restore: no remembered focus, a remembered id matching no enabled
        //   category, or a protected day (nobody benefits from focusing a read-only field).
        //   Close the gate by writing the live orientation back into the saved state. That
        //   write is all "consuming the token" (§3/§6) ever means - no queue, channel or
        //   flow, just this one comparison going false, so every later fresh composition is
        //   a no-op.
        // - A real target on an editable day: scroll it into view but do NOT close the gate.
        //   The target may lie outside the viewport and compose only after the scroll; if
        //   this effect closed the gate first, the target's own effect would find it closed
        //   and never take focus. The target field closes the gate when it handles the
        //   restore (see the per-field effect below).
        LaunchedEffect(Unit) {
            if (restoredOrientation != currentOrientation) {
                if (focusedItemIndex < 0 || isProtected) {
                    restoredOrientation = currentOrientation
                } else {
                    listState.scrollToItem(focusedItemIndex)
                }
            }
        }

        Scaffold(
            topBar = {
                var showMenu by remember { mutableStateOf(false) }
                TopAppBar(
                    title = { Text(text = stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription =
                                    stringResource(R.string.menu_content_description),
                            )
                        }
                        // Clearing focus when a menu ITEM is clicked (not when the menu opens)
                        // ends the "OSK dance" (HOME_SCREEN_NOTES §4.5/§7.3): the OSK briefly
                        // re-emerged while a child screen slid in because the field still held
                        // focus when the popup dismissed. Opening and dismissing the menu alone
                        // keeps focus, so type-peek-dismiss-resume keeps the OSK up - standard
                        // behaviour, deliberately preserved.
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            MyDropdownMenuItem(
                                text = { Text(stringResource(R.string.categories_title)) },
                                onClick = {
                                    showMenu = false
                                    clearFocus()
                                    onCategories()
                                },
                            )
                            MyDropdownMenuItem(
                                text = { Text(stringResource(R.string.history)) },
                                onClick = {
                                    showMenu = false
                                    clearFocus()
                                    onHistory(selectedDate)
                                },
                            )
                            MyDropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                onClick = {
                                    showMenu = false
                                    clearFocus()
                                    onSettings()
                                },
                            )
                        }
                    },
                )
            }
        ) { innerPadding ->
            Column(modifier = modifier.fillMaxSize().padding(innerPadding)) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(
                            onClick = {
                                clearFocus()
                                homeViewModel.setSelectedDate(selectedDate.minusDays(1))
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription =
                                    stringResource(R.string.previous_day_content_description),
                            )
                        }
                        // The date label fades between values rather than sliding: a slide
                        // implies direction and motion across a gap ("I left the date on last
                        // January, it's August 26th, it slid right once and we skipped months"),
                        // which is showier and forces a direction decision we don't need. A
                        // fade just says "this value changed." Animating here covers both
                        // user navigation and the 10-minute background reset with one
                        // mechanism, so the reset's date change is legible as a date change
                        // rather than a glitch - the reset's *effect* (home on today, ready to
                        // type) is self-evident regardless.
                        //
                        // Crossfade rather than AnimatedContent: AnimatedContent's default
                        // transitionSpec can animate from nothing to the new value (a "flash
                        // in"), which is what made the first attempt look terrible. Crossfade
                        // is purpose-built for fading old->new, so it cannot flash in from
                        // nothing.
                        //
                        // The date is wrapped in a Box so the tappable area stays the full
                        // middle slot regardless of text width or line count - the picker
                        // must open from anywhere in the middle, and a shrinking hit target
                        // would be a regression. The label is left-aligned inside it, like
                        // the app bar title and the category headings elsewhere in the app.
                        //
                        // Crossfade sizes its own animation container to the *target* state's
                        // intrinsic width, so when fading a short date in over a long one the
                        // short text fills a narrow container (effectively left-aligned) and
                        // only snaps to centre when the animation finishes - a visible "leap".
                        // fillMaxWidth makes the container equal to the Box's fixed slot for
                        // both states, so the position is stable across the whole transition
                        // and there is nothing to leap from. The same applies to the height:
                        // both lines are always present, so the slot never resizes mid-fade.
                        Box(
                            modifier =
                                Modifier.weight(1f).testTag("home_date_label").clickable {
                                    showDatePicker = true
                                },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Crossfade(
                                targetState = dateLabel,
                                modifier = Modifier.fillMaxWidth(),
                                animationSpec = tween(durationMillis = 300),
                                label = "date_label_animation",
                            ) { (weekday, date) ->
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.Start,
                                ) {
                                    Text(
                                        text = weekday,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        text = date,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                        // Right group: padlock + forward, pinned to the right end by the
                        // SpaceBetween arrangement. The fixed width is the point: it makes the
                        // padlock's horizontal position independent of the date text, which is
                        // what removes the jump seen with long month names (September) - the
                        // date's intrinsic width varies, and without a fixed width the group's
                        // measured size could shift as the date settles, nudging the padlock.
                        // No hardcoded dp widths: IntrinsicSize.Min sizes the group to its
                        // widest child, which is constant across icon glyphs.
                        Row(
                            modifier = Modifier.width(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { homeViewModel.setProtection(!isProtected) }) {
                                Icon(
                                    imageVector =
                                        if (isProtected) Icons.Filled.Lock
                                        else Icons.Filled.LockOpen,
                                    contentDescription =
                                        stringResource(
                                            if (isProtected) R.string.protected_content_description
                                            else R.string.unprotected_content_description
                                        ),
                                )
                            }
                            IconButton(
                                onClick = {
                                    clearFocus()
                                    homeViewModel.setSelectedDate(selectedDate.plusDays(1))
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription =
                                        stringResource(R.string.next_day_content_description),
                                )
                            }
                        }
                    }
                }

                if (enabledCategories.isEmpty()) {
                    EmptyCategoriesState(
                        hasAnyCategories = categories.isNotEmpty(),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(items = enabledCategories, key = { it.id }) { category ->
                            val state =
                                rememberEntryFieldState(
                                    entries = uiState.entries,
                                    categoryId = category.id,
                                    date = selectedDate,
                                )
                            val focusRequester = remember { FocusRequester() }

                            // Restore pass, per-field half. After a genuine configuration
                            // change, the field that held focus takes it back (unless the day
                            // is protected) and closes the gate. This - not the screen-level
                            // effect - is the designated gate-closer in the real-restore case,
                            // which is exactly what lets an offscreen target still restore:
                            // the scroll brings this item into composition on a later pass,
                            // when the screen-level effect is long done, and this item's
                            // effect finds the gate still open. Any other fresh composition
                            // of this item - child-screen return, category enable/disable,
                            // viewport recycling - finds the gate closed (or never open) and
                            // does nothing.
                            //
                            // The two effects cannot fight over restoredOrientation: in a
                            // real restore exactly one of them closes the gate by design
                            // (the screen half scrolls and deliberately defers; the field
                            // half closes when it grabs focus), and where both halves'
                            // conditions hold at once - a protected day with the target
                            // already composed - both write the same value, which is
                            // harmless. Both effects re-read the state live when their
                            // coroutines run, so neither can act on a stale snapshot of the
                            // gate; that is why the check lives inside the effects rather
                            // than in the composition body.
                            //
                            // focusRequester in a LaunchedEffect is the standard
                            // focus-restore pattern: the requester is created once per item
                            // (remember, above) and attached to the field via its modifier,
                            // so by the time this effect runs the field node exists and
                            // requestFocus() is safe; calling it from the composition body
                            // itself would be a side effect during composition. The effect
                            // key matches the requester merely for self-documentation - the
                            // instance never changes within an item's lifetime.
                            LaunchedEffect(focusRequester) {
                                if (
                                    focusedCategoryId == category.id &&
                                        restoredOrientation != currentOrientation
                                ) {
                                    if (!isProtected) {
                                        focusRequester.requestFocus()
                                    }
                                    restoredOrientation = currentOrientation
                                }
                            }

                            val keyboardOptions = buildKeyboardOptions(category)

                            CategoryEntrySection(
                                category = category,
                                state = state,
                                readOnly = isProtected,
                                keyboardOptions = keyboardOptions,
                                focusRequester = focusRequester,
                                onTextChanged = {
                                    homeViewModel.onUserTyped(category.id, selectedDate, it)
                                },
                                onFocusChanged = { isFocused ->
                                    if (isFocused) {
                                        focusedCategoryId = category.id
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        if (showDatePicker) {
            val datePickerState =
                rememberDatePickerState(
                    initialSelectedDateMillis = HomeLogic.localDateToDatePickerMillis(selectedDate)
                )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis?.let { millis ->
                                clearFocus()
                                val newDate = HomeLogic.datePickerMillisToLocalDate(millis)
                                homeViewModel.setSelectedDate(newDate)
                            }
                            showDatePicker = false
                        }
                    ) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text(text = stringResource(android.R.string.cancel))
                    }
                },
            ) {
                DatePicker(state = datePickerState)
            }
        }
    }
}

/**
 * Returns the entry "scratchpad" [TextFieldState] for one (category, date) pair.
 *
 * This function is the heart of the field/database contract on this screen: the field is seeded
 * from the database exactly once, on first composition for the (category, date) pair, and every
 * later re-emission of the UI state - including ones carrying back the very text just typed - is
 * deliberately ignored. TextFieldState is the master; the database is a durability sink, not a
 * display driver. That is only sound because HomeViewModel.saveEntry persists immediately, so
 * whenever this state is recreated (date change, category re-enabled, viewport recycling) the
 * database row already holds what the user last saw.
 *
 * See docs/HOME_SCREEN_NOTES.md §2; pinned by the field-state tests in HomeScreenPinningTest.
 */
@Composable
private fun rememberEntryFieldState(
    entries: List<EntryEntity>,
    categoryId: Long,
    date: LocalDate,
): TextFieldState {
    // Linear search over a handful of entries per fresh field creation is fine at this scale.
    val initialText = entries.firstOrNull { it.categoryId == categoryId }?.text ?: ""
    // The date in the key ensures a fresh state (re-seeded from the database) when the selected
    // date changes; remember keeps the state across recompositions and rotation.
    return key(categoryId, date) { rememberTextFieldState(initialText) }
}

@Composable
private fun EmptyCategoriesState(hasAnyCategories: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Category,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Text(
            text =
                if (hasAnyCategories) stringResource(R.string.home_empty_no_enabled_categories)
                else stringResource(R.string.home_empty_no_categories),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CategoryEntrySection(
    category: CategoryEntity,
    state: TextFieldState,
    readOnly: Boolean,
    keyboardOptions: KeyboardOptions,
    focusRequester: FocusRequester,
    onTextChanged: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = category.name,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        EntryTextField(
            state = state,
            readOnly = readOnly,
            keyboardOptions = keyboardOptions,
            focusRequester = focusRequester,
            onTextChanged = onTextChanged,
            onFocusChanged = onFocusChanged,
            modifier = Modifier.fillMaxWidth().testTag("entry_textfield_${category.id}"),
        )
    }
}

@Composable
private fun EntryTextField(
    state: TextFieldState,
    readOnly: Boolean,
    keyboardOptions: KeyboardOptions,
    focusRequester: FocusRequester,
    onTextChanged: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        state = state,
        readOnly = readOnly,
        keyboardOptions = keyboardOptions,

        // We use inputTransformation as a way to be notified when Compose commits an input or
        // selection change. This is much more reliable than trying to hack this via a snapshotFlow
        // observing the TextFieldState's text. Initial population from the database does not invoke
        // it, but selection-only changes (such as moving the cursor) and focus teardown can. This
        // is a ChatGPT suggestion. HomeViewModel.saveEntry() filters selection-only text no-ops,
        // and onUserTyped() ignores callbacks after the ViewModel has been cleared.
        //
        // If maxLength() causes the user's input to be an effective no-op, onTextChanged() is still
        // called. It's probably possible to avoid this, but we want to avoid unnecessary complexity
        // and corner case bugs, and it isn't really harmful, so just accept it.
        inputTransformation =
            InputTransformation.maxLength(maxEntryLength).then {
                onTextChanged(asCharSequence().toString())
            },
        modifier =
            modifier.focusRequester(focusRequester).onFocusChanged { onFocusChanged(it.isFocused) },
    )
}

/**
 * Builds the per-category IME hints handed to the home screen's text fields. Declared as a
 * top-level function rather than inline in [HomeScreen] so the mapping can be unit-tested directly
 * on the JVM without composing the screen (same rationale as selectExportData in
 * SettingsViewModel.kt).
 *
 * Only capitalization and autocorrect vary per category; other keyboard options are left at their
 * defaults. These are hints to the keyboard - which may ignore them (see the editor disclaimer
 * string) - never enforcement.
 */
internal fun buildKeyboardOptions(category: CategoryEntity): KeyboardOptions =
    KeyboardOptions(
        capitalization = category.capitalization.toKeyboardCapitalization(),
        autoCorrectEnabled = category.autoCorrect,
    )

/**
 * Decides whether the home screen should clear focus when the app pauses.
 *
 * The home screen is both a viewer and the app's primary editor, so focus is a help while the user
 * is editing and a hindrance while they are browsing. A field that kept focus across a
 * backgrounding would have the OSK re-popped on return, eating the screen they wanted to browse.
 * Clearing focus on pause therefore leaves the screen clean on return, and since edits are
 * persisted immediately, resuming is simply a tap on the field.
 *
 * The one exception is a rotation: `isChangingConfigurations()` is true while the phone is turning,
 * because the app stays on screen and the user is still actively typing (or just was). Losing focus
 * there would break the §6 restore path, so rotation is excluded. This is the standard
 * discriminator for exactly this distinction, and it is reliable here.
 *
 * Deliberately NOT tied to the IME state: dismissing the on-screen keyboard with back while staying
 * in the app does not clear focus, so a user who dismisses the OSK in order to type on a physical
 * or Bluetooth keyboard can keep typing without re-tapping the field.
 *
 * Pinned by [HomeFocusPolicyTest].
 */
internal fun shouldClearFocusOnPause(isChangingConfigurations: Boolean): Boolean =
    !isChangingConfigurations
