package app.zornslemma.dayfile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.zornslemma.dayfile.data.AppStateRepository
import app.zornslemma.dayfile.data.CategoryRepository
import app.zornslemma.dayfile.data.HistoryDatabase
import app.zornslemma.dayfile.data.MainDatabase
import app.zornslemma.dayfile.data.SettingsRepository
import app.zornslemma.dayfile.ui.AboutScreen
import app.zornslemma.dayfile.ui.CategoryAddEditScreen
import app.zornslemma.dayfile.ui.CategoryEditViewModel
import app.zornslemma.dayfile.ui.CategoryEditViewModelFactory
import app.zornslemma.dayfile.ui.CategoryScreen
import app.zornslemma.dayfile.ui.HistoryScreen
import app.zornslemma.dayfile.ui.HistoryViewModel
import app.zornslemma.dayfile.ui.HistoryViewModelFactory
import app.zornslemma.dayfile.ui.HomeScreen
import app.zornslemma.dayfile.ui.HomeViewModel
import app.zornslemma.dayfile.ui.HomeViewModelFactory
import app.zornslemma.dayfile.ui.LegalScreen
import app.zornslemma.dayfile.ui.ResetViewModel
import app.zornslemma.dayfile.ui.ResetViewModelFactory
import app.zornslemma.dayfile.ui.SettingsScreen
import app.zornslemma.dayfile.ui.SettingsViewModel
import app.zornslemma.dayfile.ui.SettingsViewModelFactory
import app.zornslemma.dayfile.ui.theme.AppTheme
import java.time.LocalDate
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = MainDatabase.getDatabase(applicationContext)
        val settingsRepository = SettingsRepository(applicationContext)
        val historyDao = HistoryDatabase.getDatabase(applicationContext).historyDao()
        val appStateRepository = AppStateRepository(applicationContext)
        val repository = CategoryRepository(database.categoryDao(), historyDao)
        lifecycleScope.launch {
            // Only create default categories the first time the app is launched. After that,
            // respect the user's choices (including deleting all categories).
            if (!settingsRepository.wereDefaultCategoriesCreated()) {
                repository.ensureDefaultCategoriesExist(applicationContext)
                settingsRepository.setDefaultCategoriesCreated()
            }
        }

        // ResetViewModel owns the app-session lifetime of the background timestamp and the
        // 10-minute reset (see ResetViewModel.kt for why the timestamp lives in a SavedStateHandle
        // rather than in AppStateRepository). It is activity-scoped - not tied to the home
        // back-stack entry - so it survives rotation and survives a successful reset navigation,
        // dying only with the process. That is the lifetime the timestamp needs: it must outlive
        // the home entry, which the reset destroys, and it must die with the task on swipe-away.
        //
        // The factory derives the SavedStateHandle from the provider's creation extras, so the
        // caller supplies only the shared repositories.
        val resetViewModel: ResetViewModel by
            viewModels(
                factoryProducer = {
                    ResetViewModelFactory(
                        settingsRepository = settingsRepository,
                        historyDao = historyDao,
                        appStateRepository = appStateRepository,
                    )
                }
            )

        setContent {
            AppTheme {
                val navController = rememberNavController()

                // When a long reset happens while Home is already the current destination, we
                // retain that back-stack entry to avoid throwing away its ViewModel and current
                // field state for no visible benefit. A rememberSaveable date picker, however, is
                // transient state from the previous session: it must close rather than let the
                // user confirm a stale selection after the reset. Incrementing this in-process
                // token is the smallest coordination for that one case; from a child route the
                // reset already discards the whole stack and creates a fresh Home screen.
                var homeSessionResetToken by remember { mutableIntStateOf(0) }

                // Wire app-level background/foreground lifecycle to the ResetViewModel so it can
                // perform the 10-minute reset. The ViewModel's suspend return value is the trigger
                // for the navigation: a >=10-minute background always starts a new session on
                // the home screen, no matter where the user was (SPEC). The nav op itself is a
                // single popUpTo(startDestination, inclusive=true) - it discards the whole back
                // stack atomically, so no manual "pop until we reach home" loop is needed.
                DisposableEffect(Unit) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_PAUSE -> {
                                lifecycleScope.launch { resetViewModel.onMoveToBackground() }
                            }
                            Lifecycle.Event.ON_RESUME -> {
                                lifecycleScope.launch {
                                    if (resetViewModel.onReturnToForeground()) {
                                        // A >=10-minute background always starts a new session on
                                        // the home screen, discarding the back stack - but only
                                        // navigate when we are not already there. Navigating to
                                        // home while on home would destroy and recreate the home
                                        // back-stack entry (fresh HomeViewModel, the focus-restore
                                        // gate in HomeScreen re-evaluating) for no visible benefit;
                                        // the existing home entry already observes selectedDate, so
                                        // the reset's date write propagates to a plain
                                        // recomposition.
                                        if (navController.currentDestination?.route != "home") {
                                            navController.navigate("home") {
                                                popUpTo(
                                                    navController.graph.findStartDestination().id
                                                ) {
                                                    inclusive = true
                                                }
                                                launchSingleTop = true
                                            }
                                        } else {
                                            homeSessionResetToken++
                                        }
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                    this@MainActivity.lifecycle.addObserver(observer)
                    onDispose { this@MainActivity.lifecycle.removeObserver(observer) }
                }

                // Consume the IME inset once, around the whole NavHost: with edge-to-edge enabled
                // the window is NOT resized when the on-screen keyboard appears, so without this
                // the keyboard simply overlays the bottom band of the content and nothing can
                // scroll there (lower text fields end up hidden behind the IME). Wrapping here
                // means every screen's Scaffold lays out in the space above the keyboard, on
                // every screen, in both orientations. Dialogs are separate windows and manage
                // their own IME behaviour, so they are unaffected (the retention dialog's field
                // may still misbehave in landscape - accepted gap, see ROADMAP).
                Box(modifier = Modifier.fillMaxSize().imePadding()) {
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        predictivePopEnterTransition = { EnterTransition.None },
                        predictivePopExitTransition = {
                            when {
                                // Navigation 2.10's predictive-pop transitions are NavHost-level,
                                // so mirror the destination-specific back direction here.
                                initialState.destination.route == "categoryAddEdit" ||
                                    initialState.destination.route ==
                                        "categoryAddEdit/{categoryId}" -> {
                                    slideOutVertically(targetOffsetY = { it })
                                }

                                else -> {
                                    slideOutHorizontally(targetOffsetX = { it })
                                }
                            }
                        },
                    ) {
                        composable(
                            route = "home",
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                        ) {
                            val homeViewModel: HomeViewModel =
                                viewModel(
                                    factory =
                                        HomeViewModelFactory(
                                            appStateRepository,
                                            settingsRepository,
                                            database.entryDao(),
                                            database.categoryDao(),
                                            historyDao,
                                        )
                                )
                            HomeScreen(
                                homeViewModel = homeViewModel,
                                onCategories = { navController.navigate("categories") },
                                onSettings = { navController.navigate("settings") },
                                onHistory = { date -> navController.navigate("history/${date}") },
                                homeSessionResetToken = homeSessionResetToken,
                            )
                        }
                        composable(
                            route = "categories",
                            enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                        ) { backStackEntry ->
                            // CategoryEditViewModel is scoped to the "categories" destination (the
                            // parent of the add/edit sub-flow). It is created when navigating to
                            // "categories" and destroyed when popping back to "home". This allows
                            // CategoryEditScreen and CategoryAddEditScreen (both the add and edit
                            // routes) to share the same ViewModel instance, so list updates are
                            // immediate and delete dialog state is coordinated.
                            val categoryEditViewModel: CategoryEditViewModel =
                                viewModel(
                                    viewModelStoreOwner = backStackEntry,
                                    factory =
                                        CategoryEditViewModelFactory(
                                            repository,
                                            database.entryDao(),
                                        ),
                                )
                            CategoryScreen(
                                viewModel = categoryEditViewModel,
                                onBack = { navController.popBackStack() },
                                onAddCategory = { navController.navigate("categoryAddEdit") },
                                onEditCategory = { id ->
                                    navController.navigate("categoryAddEdit/$id")
                                },
                            )
                        }
                        composable(
                            route = "categoryAddEdit",
                            enterTransition = { slideInVertically(initialOffsetY = { it }) },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { slideOutVertically(targetOffsetY = { it }) },
                        ) { backStackEntry ->
                            // CategoryAddEditScreen receives the shared CategoryEditViewModel from
                            // the parent "categories" destination.
                            val parentEntry =
                                remember(backStackEntry) {
                                    navController.getBackStackEntry("categories")
                                }
                            val categoryEditViewModel: CategoryEditViewModel =
                                viewModel(
                                    viewModelStoreOwner = parentEntry,
                                    factory =
                                        CategoryEditViewModelFactory(
                                            repository,
                                            database.entryDao(),
                                        ),
                                )
                            CategoryAddEditScreen(
                                viewModel = categoryEditViewModel,
                                categoryId = null,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            route = "categoryAddEdit/{categoryId}",
                            enterTransition = { slideInVertically(initialOffsetY = { it }) },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { slideOutVertically(targetOffsetY = { it }) },
                        ) { backStackEntry ->
                            val id =
                                backStackEntry.arguments?.getString("categoryId")?.toLongOrNull()
                            // CategoryAddEditScreen receives the shared CategoryEditViewModel from
                            // the parent "categories" destination.
                            val parentEntry =
                                remember(backStackEntry) {
                                    navController.getBackStackEntry("categories")
                                }
                            val categoryEditViewModel: CategoryEditViewModel =
                                viewModel(
                                    viewModelStoreOwner = parentEntry,
                                    factory =
                                        CategoryEditViewModelFactory(
                                            repository,
                                            database.entryDao(),
                                        ),
                                )
                            CategoryAddEditScreen(
                                viewModel = categoryEditViewModel,
                                categoryId = id,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            route = "settings",
                            enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                        ) {
                            // SettingsViewModel is the only ViewModel in this app whose factory
                            // takes a Context (the others are built from DAOs and repositories),
                            // so this is the only screen where the app-context rule comes up. The
                            // Context backs SettingsViewModel's file operations - backup, restore
                            // and export all resolve files or streams through it - and must be
                            // the application context, not the Activity held by
                            // LocalContext.current: ViewModels outlive configuration changes, so
                            // an Activity reference here would pin the destroyed Activity (e.g.
                            // after a rotation) for as long as this screen's back stack entry
                            // keeps the ViewModel alive. Restore is one of the operations that
                            // need the Context, but the rule is not restore-specific.
                            val settingsViewModel: SettingsViewModel =
                                viewModel(
                                    factory =
                                        SettingsViewModelFactory(
                                            LocalContext.current.applicationContext,
                                            settingsRepository,
                                            database,
                                            historyDao,
                                        )
                                )
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onBack = { navController.popBackStack() },
                                onAboutClick = { navController.navigate("about") },
                            )
                        }
                        composable(
                            route = "about",
                            enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                        ) {
                            AboutScreen(
                                onBack = { navController.popBackStack() },
                                onViewLegalClick = { navController.navigate("legal") },
                            )
                        }
                        composable(
                            route = "legal",
                            enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                        ) {
                            LegalScreen(onBack = { navController.popBackStack() })
                        }
                        composable(
                            route = "history/{dateStr}",
                            enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
                        ) { backStackEntry ->
                            val dateStr =
                                backStackEntry.arguments?.getString("dateStr")
                                    ?: LocalDate.now().toString()
                            val date = LocalDate.parse(dateStr)
                            val historyViewModel: HistoryViewModel =
                                viewModel(
                                    factory =
                                        HistoryViewModelFactory(
                                            historyDao,
                                            database.categoryDao(),
                                            appStateRepository,
                                            date,
                                        )
                                )
                            HistoryScreen(
                                viewModel = historyViewModel,
                                date = date,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}
