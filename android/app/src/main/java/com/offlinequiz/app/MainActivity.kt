package com.offlinequiz.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        setContent {
            OfflineQuizTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    var nav by rememberSaveable(stateSaver = AppRouteState.Saver) { mutableStateOf(AppRouteState()) }
    var importSession by rememberSaveable { mutableIntStateOf(0) }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    val managerStates = rememberSaveableStateHolder()

    val motionTracker = remember { PageMotionTracker(nav) }
    val motionStyle = motionTracker.next(nav)
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
                    onOpenPractice = { sessionId ->
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
                    onBack = { nav = nav.backFromPractice() },
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

        else -> HomeScreen(
            onImportPdf = { uri ->
                importSession += 1
                importUri = uri
                nav = nav.startImport()
            },
            onOpenLibrary = { id -> nav = nav.openLibrary(id) },
            onBackupRestore = { nav = nav.startBackup() }
        )
    }
    }
}

@Composable
private fun MissingDestination(onReset: () -> Unit) {
    LaunchedEffect(Unit) { onReset() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
