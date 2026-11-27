package `in`.melo.presentation.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** Page motion stays separate from the persistent navigation bar and player. */
internal class PageTransitions(
    private val reducedMotion: Boolean,
    private val forwardSign: Int,
    private val tabDistancePx: Int,
    private val detailDistancePx: Int,
) {
    private val easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    private fun isTabSwitch(from: String?, to: String?): Boolean =
        isMainRootRoute(from) && isMainRootRoute(to)

    private fun direction(from: String?, to: String?, isPop: Boolean): Int {
        val fromIndex = mainRootRouteIndex(from)
        val toIndex = mainRootRouteIndex(to)
        val next = if (fromIndex != null && toIndex != null) toIndex > fromIndex else !isPop
        return if (next) forwardSign else -forwardSign
    }

    /**
     * Pages that are a WebView. Its surface redraws on every frame of an alpha or
     * offset animation and flickers, so these pages cut in and out instead.
     */
    private val webPages = setOf(Screen.DiscordLogin.route)

    private fun isWebPage(from: String?, to: String?): Boolean = from in webPages || to in webPages

    fun enter(from: String?, to: String?, isPop: Boolean = false): EnterTransition {
        if (reducedMotion || from == to || isWebPage(from, to)) return EnterTransition.None
        val tabs = isTabSwitch(from, to)
        val duration = if (tabs) 220 else 260
        val distance = if (tabs) tabDistancePx else detailDistancePx
        val sign = direction(from, to, isPop)
        return slideInHorizontally(
            initialOffsetX = { distance * sign },
            animationSpec = tween(durationMillis = duration, easing = easing),
        ) + fadeIn(
            animationSpec = tween(durationMillis = 160, delayMillis = 30, easing = easing),
        )
    }

    fun exit(from: String?, to: String?, isPop: Boolean = false): ExitTransition {
        if (reducedMotion || from == to || isWebPage(from, to)) return ExitTransition.None
        val tabs = isTabSwitch(from, to)
        val duration = if (tabs) 220 else 260
        val distance = if (tabs) tabDistancePx else detailDistancePx / 3
        val sign = direction(from, to, isPop)
        return slideOutHorizontally(
            targetOffsetX = { -distance * sign },
            animationSpec = tween(durationMillis = duration, easing = easing),
        ) + fadeOut(animationSpec = tween(durationMillis = 100, easing = easing))
    }
}
