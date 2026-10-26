package com.ebookreader.presentation

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ebookreader.domain.model.AppSettings
import com.ebookreader.presentation.common.ReaderTheme
import com.ebookreader.presentation.catalog.CatalogScreen
import com.ebookreader.presentation.library.LibraryScreen
import com.ebookreader.presentation.onboarding.OnboardingScreen
import com.ebookreader.presentation.reader.comic.ComicReaderScreen
import com.ebookreader.presentation.reader.epub.EpubReaderScreen
import com.ebookreader.presentation.reader.pdf.PdfReaderScreen
import com.ebookreader.presentation.settings.SettingsScreen
import com.ebookreader.presentation.stats.MonthlyHistoryScreen
import com.ebookreader.presentation.stats.SessionHistoryScreen
import com.ebookreader.presentation.stats.YearlyHistoryScreen
import com.ebookreader.presentation.stats.StatsScreen

@Composable
fun AppNavigation(
    navController: NavHostController,
    startDestination: String,
    settings: AppSettings,
    onEditSettings: ((AppSettings) -> AppSettings) -> Unit,
    pendingImportUri: Uri? = null,
    onPendingImportHandled: () -> Unit = {},
    pendingOpenBook: com.ebookreader.domain.model.Book? = null,
    onPendingOpenHandled: () -> Unit = {},
) {
    // The widget's book opens over the library, so Back from it lands there.
    LaunchedEffect(pendingOpenBook, startDestination) {
        val book = pendingOpenBook ?: return@LaunchedEffect
        if (startDestination != Screen.Library.route) return@LaunchedEffect
        navController.navigate(readerRoute(book)) { launchSingleTop = true }
        onPendingOpenHandled()
    }

    // With animations removed in the system settings, screens change without moving.
    val still = com.ebookreader.presentation.reader.rememberReducedMotion()
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            if (still) return@NavHost EnterTransition.None
            fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                initialOffsetX = { it / 4 },
                animationSpec = tween(300)
            )
        },
        exitTransition = {
            if (still) return@NavHost ExitTransition.None
            fadeOut(animationSpec = tween(300)) + slideOutHorizontally(
                targetOffsetX = { -it / 4 },
                animationSpec = tween(300)
            )
        },
        popEnterTransition = {
            if (still) return@NavHost EnterTransition.None
            fadeIn(animationSpec = tween(300)) + slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(300)
            )
        },
        popExitTransition = {
            if (still) return@NavHost ExitTransition.None
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
                onEditSettings = onEditSettings,
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToStats = { navController.navigate(Screen.Stats.route) },
                onNavigateToCatalog = { navController.navigate(Screen.Catalog.route) },
                pendingImportUri = pendingImportUri,
                onPendingImportHandled = onPendingImportHandled,
                onOpenBook = { book -> navController.navigate(readerRoute(book)) },
                onOpenBookAt = { book, chapter, query ->
                    navController.navigate(Screen.EpubReader.createRoute(book.id, chapter, query))
                },
                onOpenBookStats = { book -> navController.navigate(Screen.BookStats.createRoute(book.id)) },
                onOpenBookDetails = { book -> navController.navigate(Screen.BookDetail.createRoute(book.id)) },
            )
        }

        composable(
            route = Screen.BookDetail.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) {
            com.ebookreader.presentation.library.BookDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onRead = { book -> navController.navigate(readerRoute(book)) },
                onShowStats = { book -> navController.navigate(Screen.BookStats.createRoute(book.id)) },
            )
        }

        composable(
            route = Screen.PdfReader.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getLong("bookId") ?: return@composable
            ReaderTheme(settings.theme, settings.readingTheme) {
                PdfReaderScreen(
                    bookId = bookId,
                    settings = settings,
                    onEditSettings = onEditSettings,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Screen.EpubReader.route,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("chapter") { type = NavType.IntType; defaultValue = -1 },
                navArgument("q") { type = NavType.StringType; defaultValue = "" },
            )
        ) { backStackEntry ->
            val args = backStackEntry.arguments ?: return@composable
            val bookId = args.getLong("bookId")
            // Sets its own reading theme: a book can keep one of its own.
            EpubReaderScreen(
                bookId = bookId,
                startChapter = args.getInt("chapter", -1),
                searchQuery = args.getString("q")?.takeIf { it.isNotBlank() },
                settings = settings,
                onEditSettings = onEditSettings,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.ComicReader.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) {
            ReaderTheme(settings.theme, settings.readingTheme) {
                ComicReaderScreen(
                    keepScreenOn = settings.keepScreenOn,
                    rightToLeft = settings.comicRightToLeft,
                    onToggleDirection = { onEditSettings { it.copy(comicRightToLeft = !it.comicRightToLeft) } },
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Screen.BookStats.route,
            arguments = listOf(navArgument("bookId") { type = NavType.LongType })
        ) {
            com.ebookreader.presentation.stats.BookStatsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(Screen.Catalog.route) {
            CatalogScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenBook = { book -> navController.navigate(readerRoute(book)) },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onOpenSection = { section -> navController.navigate(Screen.SettingsSection.createRoute(section.route)) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.SettingsSection.route,
            arguments = listOf(navArgument("section") { type = NavType.StringType })
        ) { entry ->
            val section = com.ebookreader.presentation.settings.SettingsSection.of(entry.arguments?.getString("section"))
                ?: return@composable
            com.ebookreader.presentation.settings.SettingsSectionScreen(
                section = section,
                settings = settings,
                onEditSettings = onEditSettings,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Stats.route) {
            StatsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToMonthlyHistory = { navController.navigate(Screen.MonthlyHistory.route) },
                onNavigateToYearlyHistory = { navController.navigate(Screen.YearlyHistory.route) },
                onNavigateToSessionHistory = { navController.navigate(Screen.SessionHistory.route) },
                onNavigateToFinished = { navController.navigate(Screen.FinishedTimeline.route) },
            )
        }

        composable(Screen.FinishedTimeline.route) {
            com.ebookreader.presentation.stats.FinishedTimelineScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenBook = { book -> navController.navigate(Screen.BookDetail.createRoute(book.id)) },
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

        composable(Screen.SessionHistory.route) {
            SessionHistoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

/** Each kind of book has its own reader. */
private fun readerRoute(book: com.ebookreader.domain.model.Book): String = when (book.format) {
    com.ebookreader.domain.model.BookFormat.PDF -> Screen.PdfReader.createRoute(book.id)
    com.ebookreader.domain.model.BookFormat.COMIC -> Screen.ComicReader.createRoute(book.id)
    com.ebookreader.domain.model.BookFormat.EPUB -> Screen.EpubReader.createRoute(book.id)
}
