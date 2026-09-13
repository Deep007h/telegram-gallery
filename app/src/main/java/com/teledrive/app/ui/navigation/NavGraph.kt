package com.teledrive.app.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.teledrive.app.media.AudioPlayerScreen
import com.teledrive.app.media.ImageViewerScreen
import com.teledrive.app.media.VideoPlayerScreen
import com.teledrive.app.ui.auth.AuthScreen
import com.teledrive.app.ui.photos.GooglePhotosMainScreen
import com.teledrive.app.ui.settings.SettingsScreen
import com.teledrive.app.ui.transfers.TransferScreen

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String,
    shareUris: List<Uri>? = null
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(300))
        },
        exitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(300))
        },
        popEnterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(300))
        },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(300))
        }
    ) {
        composable(Screen.Auth.route) {
            AuthScreen(
                onAuthSuccess = {
                    navController.navigate(Screen.Explorer.createRoute("/")) {
                        popUpTo(Screen.Auth.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.Explorer.route,
            arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "/" })
        ) {
            GooglePhotosMainScreen(
                navController = navController
            )
        }

        composable(
            route = Screen.ImageViewer.route,
            arguments = listOf(navArgument("fileId") { type = NavType.LongType })
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getLong("fileId") ?: 0L
            ImageViewerScreen(
                fileId = fileId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.VideoPlayer.route,
            arguments = listOf(navArgument("fileId") { type = NavType.LongType })
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getLong("fileId") ?: 0L
            VideoPlayerScreen(
                fileId = fileId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.AudioPlayer.route,
            arguments = listOf(navArgument("fileId") { type = NavType.LongType })
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getLong("fileId") ?: 0L
            AudioPlayerScreen(
                fileId = fileId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Transfers.route) {
            TransferScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLogout = {
                    navController.navigate(Screen.Auth.route) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToAuth = {
                    navController.navigate(Screen.Auth.route)
                }
            )
        }
    }
}
