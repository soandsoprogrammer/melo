package `in`.melo.presentation.navigation

internal fun isMainRootRoute(route: String?): Boolean = mainRootRouteIndex(route) != null

internal fun mainRootRouteIndex(route: String?): Int? = when (route) {
    Screen.Home.route -> 0
    Screen.Search.route -> 1
    Screen.Library.route -> 2
    else -> null
}
