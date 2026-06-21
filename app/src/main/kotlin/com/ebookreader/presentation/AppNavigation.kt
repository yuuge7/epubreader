package com.ebookreader.presentation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ebookreader.domain.model.AppSettings
import com.ebookreader.presentation.library.LibraryScreen
import com.ebookreader.presentation.onboarding.OnboardingScreen
import com.ebookreader.presentation.reader.epub.EpubReaderScreen
import com.ebookreader.presentation.reader.pdf.PdfReaderScreen
import com.ebookreader.presentation.settings.SettingsScreen
import com.ebookreader.presentation.stats.MonthlyHistoryScreen
import com.ebookreader.presentation.stats.YearlyHistoryScreen
import com.ebookreader.presentation.stats.StatsScreen

@Composable
fun AppNavigation(
    navController: NavHostController,
    startDestination: String,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                initialOffsetX = { it / 4 },
                animationSpec = tween(300)
            )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(300)) + slideOutHorizontally(
                targetOffsetX = { -it / 4 },
                animationSpec = tween(300)
            )
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(300)
            )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) + slideOutHorizontally(
                targetOffsetX = { it / 4 },
                animationSpec = tween(300)
            )
        }
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(Screen.Library.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Library.route) {
            LibraryScreen(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToStats = { navController.navigate(Screen.Stats.route) },
                onOpenBook = { book ->
                    if (book.format.name == "PDF") {
                        navController.navigate(Screen.PdfReader.createRoute(book.id))
                    } else {
                        navController.navigate(Screen.EpubReader.createRoute(book.id))
                    }
                }
            )
        }

        composable(
            route = Screen.PdfReader.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: return@composable
            PdfReaderScreen(
                bookId = bookId,
                settings = settings,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.EpubReader.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: return@composable
            EpubReaderScreen(
                bookId = bookId,
                settings = settings,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Stats.route) {
            StatsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToMonthlyHistory = { navController.navigate(Screen.MonthlyHistory.route) },
                onNavigateToYearlyHistory = { navController.navigate(Screen.YearlyHistory.route) }
            )
        }

        composable(Screen.MonthlyHistory.route) {
            MonthlyHistoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.YearlyHistory.route) {
            YearlyHistoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
