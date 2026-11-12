package com.offlinequiz.app.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * Pure navigation state for the whole app.
 *
 * Keeping the route and every "return destination" in one immutable value makes the routing rules
 * (history back to library/wrongbook, editor back to history, rotation restoring all ids) testable
 * on the JVM instead of hiding them inside composable lambdas.
 */
data class AppRouteState(
    val route: String = Route.HOME,
    val libraryId: String = "",
    val questionId: String = "",
    val practiceSessionId: String = "",
    val practiceReturn: String = Route.LIBRARY,
    val editorReturn: String = Route.LIBRARY,
    val historyReturn: String = Route.LIBRARY,
    val questionFilter: String = "ALL",
    val wrongbookReturn: String = Route.LIBRARY,
    val backupReturn: String = Route.HOME,
    val libraryReturn: String = Route.HOME
) {

    object Route {
        const val HOME = "home"
        const val IMPORT = "import"
        const val LIBRARY = "library"
        const val WRONGBOOK = "wrongbook"
        const val HISTORY = "history"
        const val PRACTICE = "practice"
        const val EDITOR = "editor"
        const val BACKUP = "backup"
        const val QUESTIONS = "questions"
        const val WRONGBOOK_INDEX = "wrongbook-index"
        const val PROFILE = "profile"
        const val RECORDS = "records"
        const val HELP = "help"
        const val ABOUT = "about"
    }

    fun startBackup(): AppRouteState = copy(route = Route.BACKUP, backupReturn = route)

    fun backFromBackup(): AppRouteState = copy(route = backupReturn)

    fun openLibrary(id: String): AppRouteState =
        copy(route = Route.LIBRARY, libraryId = id, questionId = "",
            libraryReturn = if (route == Route.BACKUP) backupReturn else if (route == Route.RECORDS) Route.RECORDS else Route.HOME)

    fun openWrongbook(id: String = libraryId): AppRouteState = copy(
        route = Route.WRONGBOOK, libraryId = id,
        wrongbookReturn = if (route == Route.WRONGBOOK_INDEX) Route.WRONGBOOK_INDEX else Route.LIBRARY
    )

    fun openQuestionManager(onlyPending: Boolean = true): AppRouteState =
        copy(route = Route.QUESTIONS, questionFilter = if (onlyPending) "ATTENTION" else "ALL", questionId = "")

    fun backFromQuestionManager(): AppRouteState = copy(route = Route.LIBRARY, questionId = "")

    fun openHistory(questionId: String, from: String): AppRouteState = copy(
        route = Route.HISTORY,
        questionId = questionId,
        historyReturn = from
    )

    fun openEditor(questionId: String, from: String): AppRouteState = copy(
        route = Route.EDITOR,
        questionId = questionId,
        editorReturn = from
    )

    fun openPractice(sessionId: String, from: String): AppRouteState = copy(
        route = Route.PRACTICE,
        practiceSessionId = sessionId,
        practiceReturn = from
    )

    fun backFromHistory(): AppRouteState = copy(route = historyReturn)

    fun backFromWrongbook(): AppRouteState = copy(route = wrongbookReturn)

    fun backFromLibrary(): AppRouteState =
        copy(route = libraryReturn, libraryId = "", practiceSessionId = "")

    fun backFromEditor(): AppRouteState = copy(
        route = editorReturn,
        // The library screen does not use a question id; history still needs it after editing.
        questionId = if (editorReturn == Route.LIBRARY) "" else questionId
    )

    fun backFromPractice(): AppRouteState =
        copy(route = practiceReturn, practiceSessionId = "")

    fun backFromImport(): AppRouteState = copy(route = Route.HOME)

    fun startImport(): AppRouteState = copy(route = Route.IMPORT)

    /** Flat representation used by [Saver]; kept as a plain function so it is JVM-testable. */
    fun toPersistedList(): List<String> = listOf(
        route,
        libraryId,
        questionId,
        practiceSessionId,
        practiceReturn,
        editorReturn,
        historyReturn,
        questionFilter, wrongbookReturn, backupReturn, libraryReturn
    )

    companion object {
        fun fromPersistedList(values: List<String>): AppRouteState {
            if (values.size < 7) return AppRouteState()
            return AppRouteState(
                route = values[0],
                libraryId = values[1],
                questionId = values[2],
                practiceSessionId = values[3],
                practiceReturn = values[4],
                editorReturn = values[5],
                historyReturn = values[6],
                questionFilter = values.getOrElse(7) { "ALL" },
                wrongbookReturn = values.getOrElse(8) { Route.LIBRARY },
                backupReturn = values.getOrElse(9) { Route.HOME },
                libraryReturn = values.getOrElse(10) { Route.HOME }
            )
        }

        /** Persists every field, so rotation/process restore cannot lose ids or return targets. */
        val Saver: Saver<AppRouteState, Any> = listSaver(
            save = { it.toPersistedList() },
            restore = { fromPersistedList(it) }
        )
    }
}
