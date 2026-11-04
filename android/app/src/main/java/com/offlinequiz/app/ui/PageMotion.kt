package com.offlinequiz.app.ui

import com.offlinequiz.app.ui.AppRouteState.Route

enum class PageMotionStyle { NONE, FORWARD, BACK, CARD }

/** Visual identity excludes filters/counters so updates never replay page navigation. */
object PageMotion {
    fun key(state: AppRouteState): String = when (state.route) {
        Route.LIBRARY, Route.QUESTIONS, Route.WRONGBOOK -> "${state.route}:${state.libraryId}"
        Route.HISTORY, Route.EDITOR -> "${state.route}:${state.libraryId}:${state.questionId}"
        Route.PRACTICE -> "${state.route}:${state.practiceSessionId}"
        else -> state.route
    }

    fun between(from: AppRouteState, to: AppRouteState): PageMotionStyle {
        if (key(from) == key(to)) return PageMotionStyle.NONE
        val returnRoute = when (from.route) {
            Route.LIBRARY, Route.IMPORT, Route.BACKUP -> Route.HOME
            Route.QUESTIONS, Route.WRONGBOOK -> Route.LIBRARY
            Route.EDITOR -> from.editorReturn
            Route.HISTORY -> from.historyReturn
            Route.PRACTICE -> from.practiceReturn
            else -> null
        }
        if (to.route == returnRoute) return PageMotionStyle.BACK
        return if (to.route == Route.EDITOR || to.route == Route.HISTORY) PageMotionStyle.CARD
            else PageMotionStyle.FORWARD
    }
}

/** Presentation-only tracker. No persisted navigation or business state is changed. */
class PageMotionTracker(initial: AppRouteState) {
    private var previous = initial
    private var style = PageMotionStyle.NONE

    fun next(state: AppRouteState): PageMotionStyle {
        if (PageMotion.key(previous) != PageMotion.key(state)) style = PageMotion.between(previous, state)
        previous = state
        return style
    }
}
