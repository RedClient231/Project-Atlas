package com.atlas.virtualspace.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.atlas.virtualspace.R
import com.atlas.virtualspace.feature.home.HomeScreen
import com.atlas.virtualspace.feature.install.InstallScreen
import com.atlas.virtualspace.feature.logcat.LogcatScreen
import com.atlas.virtualspace.feature.settings.SettingsScreen
import com.atlas.virtualspace.ui.theme.AtlasPurple

// ─── Route constants ──────────────────────────────────────────────────────────

object AtlasRoutes {
    const val HOME = "home"
    const val INSTALL = "install"
    const val LOGCAT = "logcat"
    const val SETTINGS = "settings"
    const val APP_DETAIL = "app_detail/{packageName}"
    fun appDetail(packageName: String) = "app_detail/$packageName"
}

// ─── Bottom nav items ─────────────────────────────────────────────────────────

private data class BottomNavItem(
    val route: String,
    val labelRes: Int,
    val iconRes: Int,
)

private val BOTTOM_NAV_ITEMS = listOf(
    BottomNavItem(
        route = AtlasRoutes.HOME,
        labelRes = R.string.nav_home,
        iconRes = R.drawable.ic_home,
    ),
    BottomNavItem(
        route = AtlasRoutes.INSTALL,
        labelRes = R.string.nav_install,
        iconRes = R.drawable.ic_install,
    ),
    BottomNavItem(
        route = AtlasRoutes.LOGCAT,
        labelRes = R.string.nav_logcat,
        iconRes = R.drawable.ic_logcat,
    ),
    BottomNavItem(
        route = AtlasRoutes.SETTINGS,
        labelRes = R.string.nav_settings,
        iconRes = R.drawable.ic_settings,
    ),
)

// ─── Animation durations ─────────────────────────────────────────────────────

private const val ANIM_DURATION_MS = 350

// ─── NavHost composable ──────────────────────────────────────────────────────

@Composable
fun AtlasNavHost(
    pendingInstallUri: Uri?,
    onPendingUriConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }

    // Determine which bottom-tab routes should show the navigation bar
    val showBottomBar = BOTTOM_NAV_ITEMS.any { item ->
        currentDestination?.hierarchy?.any { it.route == item.route } == true
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BOTTOM_NAV_ITEMS.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == item.route
                        } == true

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = ImageVector.vectorResource(item.iconRes),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(text = navController.context.getString(item.labelRes)) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AtlasPurple,
                                selectedTextColor = AtlasPurple,
                                indicatorColor = AtlasPurple.copy(alpha = 0.12f),
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AtlasRoutes.HOME,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                fadeIn(animationSpec = tween(ANIM_DURATION_MS)) +
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Start,
                            animationSpec = tween(ANIM_DURATION_MS),
                        )
            },
            exitTransition = {
                fadeOut(animationSpec = tween(ANIM_DURATION_MS)) +
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Start,
                            animationSpec = tween(ANIM_DURATION_MS),
                        )
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(ANIM_DURATION_MS)) +
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.End,
                            animationSpec = tween(ANIM_DURATION_MS),
                        )
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(ANIM_DURATION_MS)) +
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.End,
                            animationSpec = tween(ANIM_DURATION_MS),
                        )
            },
        ) {
            // ── Home ────────────────────────────────────────────────────────
            composable(AtlasRoutes.HOME) {
                HomeScreen(
                    onNavigateToInstall = {
                        navController.navigate(AtlasRoutes.INSTALL)
                    },
                    onNavigateToAppDetail = { packageName ->
                        navController.navigate(AtlasRoutes.appDetail(packageName))
                    },
                    snackbarHostState = snackbarHostState,
                )
            }

            // ── Install ─────────────────────────────────────────────────────
            composable(AtlasRoutes.INSTALL) {
                InstallScreen(
                    pendingInstallUri = pendingInstallUri,
                    onPendingUriConsumed = onPendingUriConsumed,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            // ── Logcat ─────────────────────────────────────────────────────
            composable(AtlasRoutes.LOGCAT) {
                LogcatScreen()
            }

            // ── Settings ────────────────────────────────────────────────────
            composable(AtlasRoutes.SETTINGS) {
                SettingsScreen()
            }

            // ── App Detail ──────────────────────────────────────────────────
            composable(
                route = AtlasRoutes.APP_DETAIL,
                arguments = listOf(
                    navArgument("packageName") { type = NavType.StringType }
                ),
            ) { backStackEntry ->
                val packageName = backStackEntry.arguments?.getString("packageName").orEmpty()
                HomeScreen( // Reuse home's app-detail view inside a standalone route
                    onNavigateToInstall = { navController.navigate(AtlasRoutes.INSTALL) },
                    onNavigateToAppDetail = { navController.navigate(AtlasRoutes.appDetail(it)) },
                    highlightPackage = packageName,
                )
            }
        }
    }
}
