package com.offlinequiz.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import com.offlinequiz.app.ui.*
import com.offlinequiz.app.model.PracticeState
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.offlinequiz.app.ui.AppRouteState
import com.offlinequiz.app.ui.PageMotion
import com.offlinequiz.app.ui.PageMotionTracker
import com.offlinequiz.app.ui.DestinationTransition
import com.offlinequiz.app.ui.AppRouteState.Route
import com.offlinequiz.app.ui.BackupScreen
import com.offlinequiz.app.ui.HomeScreen
import com.offlinequiz.app.ui.ImportScreen
import com.offlinequiz.app.ui.LibraryDetailScreen
import com.offlinequiz.app.ui.PracticeScreen
import com.offlinequiz.app.ui.QuestionEditScreen
import com.offlinequiz.app.ui.QuestionHistoryScreen
import com.offlinequiz.app.ui.QuestionManagerScreen
import com.offlinequiz.app.ui.WrongbookScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("appearance", MODE_PRIVATE)
        var themeMode by mutableStateOf(ThemeMode.fromPreference(preferences.getString("theme_mode", null)))
        setContent {
            val dark = themeMode.isDark(isSystemInDarkTheme())
            OfflineQuizTheme(dark) {
                val background = MaterialTheme.colorScheme.background.toArgb()
                SideEffect { updateSystemBars(dark, background) }
                AppNavigation(themeMode) { selected ->
                    preferences.edit().putString("theme_mode", selected.name).apply()
                    themeMode = selected
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars(dark: Boolean, background: Int) {
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = background
            window.navigationBarColor = background
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

@Composable
private fun AppNavigation(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    var nav by rememberSaveable(stateSaver = AppRouteState.Saver) { mutableStateOf(AppRouteState()) }
    var importSession by rememberSaveable { mutableIntStateOf(0) }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    var preparedReview by remember { mutableStateOf<PracticeState?>(null) }

    val managerStates = rememberSaveableStateHolder()

    val motionTracker = remember { PageMotionTracker(nav) }
    val motionStyle = motionTracker.next(nav)
    val primary = nav.route in setOf(Route.HOME, Route.WRONGBOOK_INDEX, Route.PROFILE)
    BackHandler(enabled = nav.route == Route.WRONGBOOK_INDEX || nav.route == Route.PROFILE) { nav = nav.copy(route = Route.HOME) }
    Column(Modifier.fillMaxSize().glassBackdrop()) {
    Box(Modifier.weight(1f)) {
    DestinationTransition(PageMotion.key(nav), motionStyle) {
    when (nav.route) {
        Route.IMPORT -> ImportScreen(
            sessionId = importSession,
            initialUri = importUri,
            onBack = {
                importUri = null
                nav = nav.backFromImport()
            },
            onSaved = { id ->
                importUri = null
                nav = nav.openLibrary(id)
            }
        )

        Route.LIBRARY -> {
            if (nav.libraryId.isEmpty()) {
                MissingDestination { nav = nav.copy(route = Route.HOME) }
            } else {
                LibraryDetailScreen(
                    libraryId = nav.libraryId,
                    onBack = { nav = nav.backFromLibrary() },
                    onOpenPractice = { sessionId ->
                        nav = nav.openPractice(sessionId, Route.LIBRARY)
                    },
                    onOpenWrongbook = {
                        managerStates.removeState("wrongbook-${nav.libraryId}")
                        nav = nav.openWrongbook()
                    },
                    onOpenQuestions = {
                        managerStates.removeState("questions-${nav.libraryId}-ALL")
                        nav = nav.openQuestionManager(onlyPending = false)
                    },
                    onOpenReview = {
                        managerStates.removeState("questions-${nav.libraryId}-ATTENTION")
                        nav = nav.openQuestionManager()
                    }
                )
            }
        }

        Route.QUESTIONS -> {
            if (nav.libraryId.isEmpty()) {
                MissingDestination { nav = nav.backFromLibrary() }
            } else {
                managerStates.SaveableStateProvider("questions-${nav.libraryId}-${nav.questionFilter}") {
                QuestionManagerScreen(
                    libraryId = nav.libraryId,
                    filterName = nav.questionFilter,
                    onFilterChange = {
                        managerStates.removeState("questions-${nav.libraryId}-$it")
                        nav = nav.copy(questionFilter = it)
                    },
                    onBack = { nav = nav.backFromQuestionManager() },
                    onEditQuestion = { nav = nav.openEditor(it, Route.QUESTIONS) },
                    onOpenHistory = { nav = nav.openHistory(it, Route.QUESTIONS) }
                )
                }
            }
        }

        Route.WRONGBOOK -> {
            if (nav.libraryId.isEmpty()) {
                MissingDestination { nav = nav.copy(route = Route.HOME) }
            } else {
                managerStates.SaveableStateProvider("wrongbook-${nav.libraryId}") {
                WrongbookScreen(
                    libraryId = nav.libraryId,
                    onBack = { nav = nav.backFromWrongbook() },
                    onOpenPractice = { sessionId, initialState ->
                        preparedReview = initialState
                        nav = nav.openPractice(sessionId, Route.WRONGBOOK)
                    },
                    onOpenHistory = { qid -> nav = nav.openHistory(qid, Route.WRONGBOOK) },
                    onEditQuestion = { qid -> nav = nav.openEditor(qid, Route.WRONGBOOK) }
                )
                }
            }
        }

        Route.HISTORY -> {
            if (nav.libraryId.isEmpty() || nav.questionId.isEmpty()) {
                MissingDestination { nav = nav.copy(route = nav.historyReturn) }
            } else {
                QuestionHistoryScreen(
                    libraryId = nav.libraryId,
                    questionId = nav.questionId,
                    onBack = { nav = nav.backFromHistory() },
                    onEditQuestion = { qid -> nav = nav.openEditor(qid, Route.HISTORY) }
                )
            }
        }

        Route.PRACTICE -> {
            if (nav.practiceSessionId.isEmpty()) {
                MissingDestination { nav = nav.copy(route = nav.practiceReturn) }
            } else {
                PracticeScreen(
                    sessionId = nav.practiceSessionId,
                    initialState = preparedReview?.takeIf { it.session.id == nav.practiceSessionId },
                    onBack = {
                        preparedReview = null
                        nav = nav.backFromPractice()
                    },
                    onCorrectQuestion = { nav = nav.openEditor(it, Route.PRACTICE) }
                )
            }
        }

        Route.EDITOR -> {
            if (nav.libraryId.isEmpty() || nav.questionId.isEmpty()) {
                MissingDestination { nav = nav.copy(route = Route.LIBRARY) }
            } else {
                QuestionEditScreen(
                    libraryId = nav.libraryId,
                    questionId = nav.questionId,
                    onBack = { nav = nav.backFromEditor() },
                    fromPractice = nav.editorReturn == Route.PRACTICE
                )
            }
        }

        Route.BACKUP -> BackupScreen(
            onBack = { nav = nav.backFromBackup() },
            onOpenLibrary = { id -> nav = nav.openLibrary(id) }
        )

        Route.PROFILE -> managerStates.SaveableStateProvider("profile") {
            ProfileScreen(themeMode, onThemeModeChange) { destination ->
                nav = if (destination == Route.BACKUP) nav.startBackup() else nav.copy(route = destination)
            }
        }
        Route.WRONGBOOK_INDEX -> managerStates.SaveableStateProvider("wrongbook-index") {
            LearningOverviewScreen(true, {}, { id ->
                managerStates.removeState("wrongbook-$id")
                nav = nav.openWrongbook(id)
            })
        }
        Route.RECORDS -> managerStates.SaveableStateProvider("records") { LearningOverviewScreen(false, { nav = nav.copy(route = Route.PROFILE) }, { id -> nav = nav.openLibrary(id) }) }
        Route.HELP, Route.ABOUT -> InformationScreen(nav.route == Route.ABOUT) { nav = nav.copy(route = Route.PROFILE) }
        else -> managerStates.SaveableStateProvider("home") { HomeScreen(
            onImportPdf = { uri ->
                importSession += 1
                importUri = uri
                nav = nav.startImport()
            },
            onOpenLibrary = { id -> nav = nav.openLibrary(id) },
            onContinue = { session -> nav = nav.copy(libraryId = session.libraryId).openPractice(session.id, Route.HOME) }
        ) }
    }
    }
    }
    if (primary) MainTabs(nav.route) { nav = nav.copy(route = it) }
    }
}

@Composable
private fun MissingDestination(onReset: () -> Unit) {
    LaunchedEffect(Unit) { onReset() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
