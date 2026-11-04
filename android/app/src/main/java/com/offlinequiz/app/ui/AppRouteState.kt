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
    val questionFilter: String = "ALL"
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
    }

    fun startBackup(): AppRouteState = copy(route = Route.BACKUP)

    fun backFromBackup(): AppRouteState = copy(route = Route.HOME)

    fun openLibrary(id: String): AppRouteState =
        copy(route = Route.LIBRARY, libraryId = id, questionId = "")

    fun openWrongbook(): AppRouteState = copy(route = Route.WRONGBOOK)

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

    fun backFromWrongbook(): AppRouteState = copy(route = Route.LIBRARY)

    fun backFromLibrary(): AppRouteState =
        copy(route = Route.HOME, libraryId = "", practiceSessionId = "")

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
        questionFilter
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
                questionFilter = values.getOrElse(7) { "ALL" }
            )
        }

        /** Persists every field, so rotation/process restore cannot lose ids or return targets. */
        val Saver: Saver<AppRouteState, Any> = listSaver(
            save = { it.toPersistedList() },
            restore = { fromPersistedList(it) }
        )
    }
}
