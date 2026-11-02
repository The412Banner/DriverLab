package com.driverlab.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.driverlab.AppGraph
import com.driverlab.core.CtsSetup

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("tests", "Tests", Icons.Filled.Science),
    Tab("results", "Results", Icons.Filled.Assessment),
    Tab("files", "Files", Icons.Filled.Folder),
    Tab("drivers", "Drivers", Icons.Filled.DeveloperBoard),
)

/** Builds a route with URL-encoded arguments. */
fun route(base: String, vararg args: String): String = base + args.joinToString("") { "/" + Uri.encode(it) }

/** Switches tab, bringing back where that tab was left; [fresh] opens the tab's own screen instead. */
fun NavHostController.goTab(r: String, fresh: Boolean = false) = navigate(r) {
    popUpTo(graph.findStartDestination().id) { saveState = !fresh }
    launchSingleTop = true
    restoreState = !fresh
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DriverLabRoot() {
    val setup by AppGraph.setup.state.collectAsStateWithLifecycle()
    if (setup !is CtsSetup.State.Ready) {
        SetupScreen(setup)
        return
    }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val showBar = tabs.any { it.route == current }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            AnimatedVisibility(showBar, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    for (t in tabs) {
                        NavigationBarItem(
                            selected = current == t.route,
                            onClick = { nav.goTab(t.route) },
                            icon = { Icon(t.icon, t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
            NavHost(
                navController = nav,
                startDestination = "home",
                enterTransition = { fadeIn(tween(240)) + slideInHorizontally(tween(260)) { it / 12 } },
                exitTransition = { fadeOut(tween(180)) },
                popEnterTransition = { fadeIn(tween(240)) },
                popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { it / 12 } },
            ) {
                composable("home") { HomeScreen(nav) }
                composable("tests") { TestsScreen(nav) }
                composable("results") { ResultsScreen(nav) }
                composable("files") { FilesScreen(nav) }
                composable("drivers") { DriversScreen(nav) }
                composable("settings") { SettingsScreen(nav) }
                composable(
                    "run",
                    enterTransition = { fadeIn(tween(260)) + scaleIn(tween(320), initialScale = 0.94f) },
                    popExitTransition = { fadeOut(tween(200)) + scaleOut(tween(220), targetScale = 0.96f) },
                ) { RunScreen(nav) }
                composable("session/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                    SessionScreen(nav, it.arguments?.getString("id").orEmpty())
                }
                composable("test/{id}/{name}", listOf(navArgument("id") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType })) {
                    TestDetailScreen(nav, it.arguments?.getString("id").orEmpty(), it.arguments?.getString("name").orEmpty())
                }
                composable("features") { GameFeaturesScreen(nav) }
                composable("speed") { SpeedScreen(nav) }
                composable("speedresult/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                    SpeedResultScreen(nav, it.arguments?.getString("id").orEmpty())
                }
                composable("flags/{id}/{name}", listOf(navArgument("id") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType })) {
                    FlagCheckScreen(nav, it.arguments?.getString("id").orEmpty(), it.arguments?.getString("name").orEmpty())
                }
                composable("compare/{a}/{b}", listOf(navArgument("a") { type = NavType.StringType }, navArgument("b") { type = NavType.StringType })) {
                    CompareScreen(nav, it.arguments?.getString("a").orEmpty(), it.arguments?.getString("b").orEmpty())
                }
                composable(
                    "regdiff/{a}/{b}/{name}",
                    listOf(
                        navArgument("a") { type = NavType.StringType },
                        navArgument("b") { type = NavType.StringType },
                        navArgument("name") { type = NavType.StringType },
                    ),
                ) {
                    RegisterDiffScreen(nav, it.arguments?.getString("a").orEmpty(), it.arguments?.getString("b").orEmpty(), it.arguments?.getString("name").orEmpty())
                }
                composable("folder/{id}/{path}", listOf(navArgument("id") { type = NavType.StringType }, navArgument("path") { type = NavType.StringType })) {
                    FolderScreen(nav, it.arguments?.getString("id").orEmpty(), it.arguments?.getString("path").orEmpty())
                }
                composable("file/{id}/{path}", listOf(navArgument("id") { type = NavType.StringType }, navArgument("path") { type = NavType.StringType })) {
                    FileViewerScreen(nav, it.arguments?.getString("id").orEmpty(), it.arguments?.getString("path").orEmpty())
                }
            }
        }
    }
}
