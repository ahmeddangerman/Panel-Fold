package dev.tabletcomics

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import dev.tabletcomics.archive.ArchiveReader
import dev.tabletcomics.archive.ArchiveFormat
import dev.tabletcomics.archive.ArchiveLimits
import dev.tabletcomics.archive.ComicPage
import dev.tabletcomics.archive.OpenArchive
import dev.tabletcomics.archive.RarArchiveReader
import dev.tabletcomics.archive.StagedArchive
import dev.tabletcomics.archive.ZipArchiveReader
import dev.tabletcomics.archive.archiveCancellationRegistrar
import dev.tabletcomics.archive.detectArchiveFormat
import dev.tabletcomics.library.ComicLibraryEntry
import dev.tabletcomics.library.CoverDiskCache
import dev.tabletcomics.library.FolderScanGeneration
import dev.tabletcomics.library.LibraryFilter
import dev.tabletcomics.library.LibraryRepository
import dev.tabletcomics.library.LibrarySort
import dev.tabletcomics.library.PageBookmark
import dev.tabletcomics.library.ScannedComic
import dev.tabletcomics.library.comicStatus
import dev.tabletcomics.library.coverCacheKey
import dev.tabletcomics.library.filterAndSortLibrary
import dev.tabletcomics.library.nextIssueInNaturalOrder
import dev.tabletcomics.library.progressFraction
import dev.tabletcomics.reader.ComicReaderSession
import dev.tabletcomics.reader.PageTurnReader
import dev.tabletcomics.reader.PageFit
import dev.tabletcomics.reader.cleanupInactivePageSourceDirectories
import dev.tabletcomics.reader.ReaderRestoreState
import dev.tabletcomics.reader.SeekState
import dev.tabletcomics.reader.TurnNavigation
import dev.tabletcomics.ui.ComicsTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.math.roundToInt

private val archiveIoLock = Mutex()
private val coverCache = object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}
private var coverRevision by mutableIntStateOf(0)
private const val COVER_PREVIEW_MAX_DIMENSION = 1024
internal data class Book(
    val uri: Uri,
    val title: String,
    val format: String,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val folderPath: String = "",
)
internal data class LibraryShelfCollection(
    val key: String,
    val name: String,
    val issueCount: Int,
    val cover: Book?,
    val folderPath: String? = null,
    val customName: String? = null,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ComicApp() }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ComicApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("reader", 0) }
    val scope = rememberCoroutineScope()
    val repository = remember(context) { LibraryRepository(context) }
    val diskCoverCache = remember(context) { CoverDiskCache(java.io.File(context.cacheDir, "comic-covers")) }
    val progressWriteMutex = remember { Mutex() }
    var libraryEntries by remember { mutableStateOf<List<ComicLibraryEntry>>(emptyList()) }
    var customCollections by remember { mutableStateOf<List<String>>(emptyList()) }
    val activeReaderState = rememberSaveable(stateSaver = ReaderRestoreState.Saver) {
        mutableStateOf(ReaderRestoreState.Empty)
    }
    val savedReader = activeReaderState.value
    val currentBook = remember(savedReader.uri, savedReader.title, savedReader.format) {
        if (savedReader.hasActiveBook) Book(Uri.parse(savedReader.uri!!), savedReader.title!!, savedReader.format!!)
        else null
    }
    val session = remember {
        ComicReaderSession(context.applicationContext, archiveIoLock) { uri, index, count ->
            prefs.edit().putInt("position:$uri", index).putInt("pages:$uri", count).apply()
            if (activeReaderState.value.uri == uri.toString()) {
                activeReaderState.value = activeReaderState.value.copy(logicalPage = index)
            }
            scope.launch {
                progressWriteMutex.withLock { repository.saveProgress(uri.toString(), index, count) }
                libraryEntries = repository.entries()
            }
        }
    }
    var folder by remember { mutableStateOf(prefs.getString("folder", null)?.let(Uri::parse)) }
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var libraryLoading by remember { mutableStateOf(false) }
    var libraryMessage by remember { mutableStateOf<String?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val scanGeneration = remember { FolderScanGeneration() }
    var controlsVisible by remember { mutableStateOf(false) }
    var licenseDialog by remember { mutableStateOf(false) }
    var seekDraft by remember { mutableFloatStateOf(0f) }
    var rightToLeft by remember { mutableStateOf(false) }
    var spreads by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.RECENTLY_READ) }
    var selectedFolderPath by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCustomCollection by rememberSaveable { mutableStateOf<String?>(null) }
    var coverWidth by remember { mutableIntStateOf(prefs.getInt("coverWidth", 164).coerceIn(120, 240)) }
    var accentName by remember { mutableStateOf(prefs.getString("accent", "violet") ?: "violet") }
    var lightMode by remember { mutableStateOf(prefs.getBoolean("lightMode", false)) }
    var showReaderProgress by remember { mutableStateOf(prefs.getBoolean("showReaderProgress", false)) }
    var keepScreenAwake by remember { mutableStateOf(prefs.getBoolean("keepScreenAwake", true)) }
    var panSensitivity by remember { mutableFloatStateOf(prefs.getFloat("panSensitivity", 1.5f).coerceIn(.5f, 2.5f)) }
    var momentumStrength by remember { mutableFloatStateOf(prefs.getFloat("momentumStrength", 1f).coerceIn(0f, 2f)) }
    var fitMode by remember(currentBook?.uri) { mutableStateOf(runCatching { PageFit.valueOf(prefs.getString("fit:${currentBook?.uri}", "PAGE")!!) }.getOrDefault(PageFit.PAGE)) }
    var cropBorders by remember(currentBook?.uri) { mutableStateOf(prefs.getBoolean("crop:${currentBook?.uri}", false)) }
    var keepZoom by remember(currentBook?.uri) { mutableStateOf(prefs.getBoolean("keepZoom:${currentBook?.uri}", true)) }
    var bookOpenGeneration by remember { mutableLongStateOf(0L) }
    var toolsBusy by remember { mutableStateOf(false) }
    var toolsMessage by remember { mutableStateOf<String?>(null) }
    var coverStorageBytes by remember { mutableLongStateOf(0L) }
    val destinationStates = rememberSaveableStateHolder()
    var detailBook by remember { mutableStateOf<Book?>(null) }
    var newCollectionName by remember { mutableStateOf("") }
    var finishDialog by remember { mutableStateOf(false) }
    var nextIssueConfirmation by remember { mutableStateOf<Book?>(null) }
    var showPageThumbnails by remember { mutableStateOf(false) }
    var pageThumbnails by remember { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }
    var showBookmarks by remember { mutableStateOf(false) }
    var readerOptionsVisible by remember { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    var readerReturnDestination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    var bookmarks by remember { mutableStateOf<List<PageBookmark>>(emptyList()) }
    var currentIsBookmarked by remember { mutableStateOf(false) }
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    val spreadMode = landscape && spreads

    fun scan(uri: Uri) {
        val generation = scanGeneration.begin()
        scanJob?.cancel()
        scanJob = scope.launch {
            libraryLoading = true
            libraryMessage = null
            try {
                val job = currentCoroutineContext()[Job] ?: return@launch
                val found = withContext(Dispatchers.IO) { scanFolder(context, uri) { job.ensureActive() } }
                repository.syncScannedComics(found.map {
                    ScannedComic(it.uri.toString(), it.title, it.format, it.size, it.lastModified, it.folderPath)
                }, prefs)
                val storedEntries = repository.entries()
                val storedCollections = repository.customCollections()
                if (scanGeneration.isCurrent(generation)) {
                    books = found
                    libraryEntries = storedEntries
                    customCollections = storedCollections
                    if (books.isEmpty()) libraryMessage = "No comic archives found in this folder."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (scanGeneration.isCurrent(generation))
                    libraryMessage = error.message ?: "Select this folder again to restore access."
            } finally {
                if (scanGeneration.isCurrent(generation)) libraryLoading = false
            }
        }
    }

    fun closeBook() {
        bookOpenGeneration++
        showBookmarks = false
        showPageThumbnails = false
        readerOptionsVisible = false
        finishDialog = false
        bookmarks = emptyList()
        pageThumbnails = emptyMap()
        session.close()
        activeReaderState.value = activeReaderState.value.clearActiveBook()
        controlsVisible = false
        destination = readerReturnDestination
    }

    fun openBook(book: Book, fromBeginning: Boolean = false, restoredPageIndex: Int? = null) {
        val generation = ++bookOpenGeneration
        // Stop old publications before joining the persistence queue for the next document.
        session.close()
        activeReaderState.value = ReaderRestoreState.Empty
        controlsVisible = false
        showBookmarks = false; showPageThumbnails = false; finishDialog = false
        bookmarks = emptyList(); pageThumbnails = emptyMap()
        scope.launch {
            try {
                val (stored, page) = progressWriteMutex.withLock {
                    if (fromBeginning) {
                        repository.resetProgress(book.uri.toString())
                        prefs.edit().remove("position:${book.uri}").remove("pages:${book.uri}").apply()
                    }
                    val entries = repository.entries()
                    val page = if (fromBeginning) 0 else restoredPageIndex
                        ?: entries.firstOrNull { it.uri == book.uri.toString() }?.pageIndex?.takeIf { it >= 0 }
                        ?: prefs.getInt("position:${book.uri}", 0)
                    entries to page
                }
                if (generation != bookOpenGeneration) return@launch
                libraryEntries = stored
                rightToLeft = prefs.getBoolean("rtl:${book.uri}", false)
                spreads = prefs.getBoolean("spreads:${book.uri}", false)
                activeReaderState.value = ReaderRestoreState(book.uri.toString(), book.title, book.format, page)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == bookOpenGeneration) libraryMessage = failure.message ?: "Could not open this comic."
            }
        }
    }

    fun startReading(book: Book, fromBeginning: Boolean = false) {
        readerReturnDestination = destination
        openBook(book, fromBeginning = fromBeginning)
    }

    fun turn(forward: Boolean) {
        val current = session.current ?: return
        val neighbor = TurnNavigation.neighbor(current.startIndex, session.totalPages, spreadMode, forward)
        if (neighbor != null) session.navigate(neighbor)
        else if (forward) finishDialog = true
    }

    fun refreshLibrary() {
        scope.launch {
            libraryEntries = repository.entries()
            customCollections = repository.customCollections()
        }
    }

    fun toggleBookmark() {
        val active = currentBook ?: return
        val page = session.current?.startIndex ?: return
        scope.launch {
            val marked = repository.toggleBookmark(active.uri.toString(), page)
            val updated = repository.bookmarks(active.uri.toString())
            if (activeReaderState.value.uri == active.uri.toString()) {
                currentIsBookmarked = marked
                bookmarks = updated
            }
        }
    }

    fun finishAndReturnToLibrary() {
        val active = currentBook ?: return
        finishDialog = false
        scope.launch {
            repository.setFinished(active.uri.toString(), true)
            refreshLibrary()
            closeBook()
        }
    }

    fun finishAndOpenNext(next: Book) {
        val active = currentBook ?: return
        nextIssueConfirmation = null
        finishDialog = false
        scope.launch {
            repository.setFinished(active.uri.toString(), true)
            val updated = repository.entries()
            libraryEntries = updated
            val savedPage = updated.firstOrNull { it.uri == next.uri.toString() }?.pageIndex?.takeIf { it >= 0 }
            openBook(next, restoredPageIndex = savedPage)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("folder", uri.toString()).apply()
            selectedFolderPath = null
            selectedCustomCollection = null
            if (folder == uri) scan(uri) else folder = uri
        } catch (error: Exception) { libraryMessage = error.message ?: "Cannot retain folder access." }
    }
    fun reloadToolSettings() {
        coverWidth = prefs.getInt("coverWidth", 164).coerceIn(120, 240)
        accentName = prefs.getString("accent", "violet") ?: "violet"
        lightMode = prefs.getBoolean("lightMode", false)
        showReaderProgress = prefs.getBoolean("showReaderProgress", false)
        keepScreenAwake = prefs.getBoolean("keepScreenAwake", true)
        panSensitivity = prefs.getFloat("panSensitivity", 1.5f).coerceIn(.5f, 2.5f)
        momentumStrength = prefs.getFloat("momentumStrength", 1f).coerceIn(0f, 2f)
        coverRevision++
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            toolsBusy = true
            try {
                val bytes = progressWriteMutex.withLock { repository.exportBackup(prefs) }
                withContext(Dispatchers.IO) {
                    (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write the selected backup document.")).use { it.write(bytes) }
                }
                toolsMessage = "Backup saved. Keep this file somewhere safe."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { toolsMessage = failure.message ?: "Could not export the backup." }
            finally { toolsBusy = false }
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            toolsBusy = true
            try {
                val bytes = withContext(Dispatchers.IO) {
                    (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read the selected backup document.")).use(::readBackupDocument)
                }
                val result = progressWriteMutex.withLock { repository.importBackup(bytes, prefs) }
                reloadToolSettings()
                libraryEntries = repository.entries()
                customCollections = repository.customCollections()
                selectedFolderPath = null; selectedCustomCollection = null
                folder?.let(::scan)
                toolsMessage = result.summary
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { toolsMessage = failure.message ?: "Could not restore the backup." }
            finally { toolsBusy = false }
        }
    }
    LaunchedEffect(destination, coverRevision) {
        if (destination == AppDestination.SETTINGS) coverStorageBytes = withContext(Dispatchers.IO) { diskCoverCache.sizeBytes() }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            StagedArchive.cleanupExpired(context.cacheDir)
            cleanupInactivePageSourceDirectories(context)
        }
    }
    LaunchedEffect(folder) { folder?.let(::scan) }
    LaunchedEffect(session.current?.startIndex, session.requestedPageIndex, session.totalPages) {
        seekDraft = (session.requestedPageIndex ?: session.current?.startIndex ?: 0).toFloat()
    }
    LaunchedEffect(currentBook?.uri, session.current?.startIndex) {
        val active = currentBook ?: return@LaunchedEffect
        val loaded = repository.bookmarks(active.uri.toString())
        bookmarks = loaded
        currentIsBookmarked = loaded.any { it.pageIndex == session.current?.startIndex }
    }
    LaunchedEffect(showPageThumbnails, controlsVisible, currentBook?.uri, session.current?.startIndex, session.totalPages) {
        if (!showPageThumbnails || !controlsVisible || currentBook == null || session.totalPages <= 0) {
            pageThumbnails = emptyMap()
            return@LaunchedEffect
        }
        val center = session.current?.startIndex ?: return@LaunchedEffect
        val first = (center - 5).coerceAtLeast(0)
        val last = (first + 10).coerceAtMost(session.totalPages - 1)
        pageThumbnails = emptyMap()
        for (index in first..last) {
            val thumbnail = session.thumbnail(index) ?: continue
            pageThumbnails = pageThumbnails + (index to thumbnail)
        }
    }
    LaunchedEffect(session, currentBook) {
        val book = currentBook ?: return@LaunchedEffect
        rightToLeft = prefs.getBoolean("rtl:${book.uri}", false)
        spreads = prefs.getBoolean("spreads:${book.uri}", false)
        session.open(book.uri, book.format, activeReaderState.value.logicalPage, spreads && landscape, rightToLeft)
    }
    LaunchedEffect(session.requiresFolderReselection, currentBook) {
        if (session.requiresFolderReselection && currentBook != null) {
            libraryMessage = "This comic is no longer accessible. Choose its folder again to restore access."
            closeBook()
        }
    }
    LaunchedEffect(spreadMode, rightToLeft) { if (currentBook != null) session.reconfigure(spreadMode, rightToLeft) }
    DisposableEffect(session) { onDispose { session.dispose() } }
    BackHandler(enabled = currentBook != null) {
        when {
            readerOptionsVisible -> readerOptionsVisible = false
            showBookmarks -> showBookmarks = false
            showPageThumbnails -> showPageThumbnails = false
            controlsVisible -> controlsVisible = false
            else -> closeBook()
        }
    }
    LaunchedEffect(currentBook, controlsVisible, lightMode) {
        val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, currentBook == null)
        controller.isAppearanceLightStatusBars = lightMode && currentBook == null
        controller.isAppearanceLightNavigationBars = lightMode && currentBook == null
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (currentBook != null && !controlsVisible) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(currentBook, keepScreenAwake) {
        val window = (context as? android.app.Activity)?.window
        if (currentBook != null && keepScreenAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val booksByUri = remember(books) { books.associateBy { it.uri.toString() } }
    val persistedByUri = remember(libraryEntries) { libraryEntries.associateBy { it.uri } }
    val catalogEntries = remember(books, persistedByUri) {
        books.map { book ->
            persistedByUri[book.uri.toString()] ?: ComicLibraryEntry(
                uri = book.uri.toString(), title = book.title, format = book.format, folderPath = book.folderPath,
            )
        }
    }
    val folderPaths = remember(books) {
        buildSet {
            books.forEach { book ->
                var path = ""
                book.folderPath.split('/').filter { it.isNotBlank() }.forEach { segment ->
                    path = if (path.isEmpty()) segment else "$path/$segment"
                    add(path)
                }
            }
        }.sorted()
    }
    val shelfEntries = remember(catalogEntries, searchQuery, filter, sort, selectedFolderPath, selectedCustomCollection) {
        filterAndSortLibrary(catalogEntries, searchQuery, filter, sort).filter { entry ->
            when {
                selectedFolderPath != null -> entry.folderPath == selectedFolderPath || entry.folderPath.startsWith("${selectedFolderPath}/")
                selectedCustomCollection != null -> selectedCustomCollection in entry.customCollections
                else -> true
            }
        }
    }
    val continueEntries = remember(catalogEntries) {
        catalogEntries.filter { !it.finished && it.lastReadAt > 0L }
            .sortedByDescending { it.lastReadAt }.take(10)
    }
    val collectionShelves = remember(folderPaths, catalogEntries, customCollections, booksByUri) {
        buildList {
            folderPaths.forEach { path ->
                val group = filterAndSortLibrary(catalogEntries.filter {
                    it.folderPath == path || it.folderPath.startsWith("$path/")
                }, "", LibraryFilter.ALL, LibrarySort.TITLE)
                val first = group.firstOrNull() ?: return@forEach
                val cover = booksByUri[first.uri] ?: return@forEach
                add(LibraryShelfCollection("folder:$path", path.substringAfterLast('/'), group.size, cover, folderPath = path))
            }
            customCollections.forEach { name ->
                val group = filterAndSortLibrary(catalogEntries.filter { name in it.customCollections },
                    "", LibraryFilter.ALL, LibrarySort.TITLE)
                val cover = group.firstOrNull()?.let { booksByUri[it.uri] }
                add(LibraryShelfCollection("custom:$name", name, group.size, cover, customName = name))
            }
        }
    }
    val currentEntry = currentBook?.let { active -> catalogEntries.firstOrNull { it.uri == active.uri.toString() } }
    val nextIssueEntry = currentEntry?.let { active ->
        val selectedCustom = selectedCustomCollection?.takeIf { it in active.customCollections }
            ?: active.customCollections.sorted().firstOrNull()
        val group = if (selectedCustom != null) catalogEntries.filter { selectedCustom in it.customCollections }
            else catalogEntries.filter { it.folderPath == active.folderPath }
        nextIssueInNaturalOrder(group, active.uri)
    }
    val nextIssueBook = nextIssueEntry?.let { booksByUri[it.uri] }
    val featuredEntry = remember(catalogEntries) {
        catalogEntries.filter { !it.finished && it.pageIndex >= 0 }.maxByOrNull { it.lastReadAt }
            ?: catalogEntries.filter { !it.finished }.maxByOrNull { it.addedAt }
            ?: catalogEntries.maxByOrNull { it.lastReadAt }
    }
    val featuredBook = featuredEntry?.let { booksByUri[it.uri] }
    val otherReads = remember(catalogEntries, booksByUri, featuredEntry) {
        catalogEntries.filter { !it.finished && it.pageIndex >= 0 && it.uri != featuredEntry?.uri }
            .sortedByDescending { it.lastReadAt }.take(8).mapNotNull { entry -> booksByUri[entry.uri]?.let { it to entry } }
    }
    var sortOptionsVisible by remember { mutableStateOf(false) }
    var createCollectionVisible by remember { mutableStateOf(false) }

    fun clearCollectionScope() { selectedFolderPath = null; selectedCustomCollection = null }
    fun navigateTo(target: AppDestination) {
        if (target == AppDestination.LIBRARY) clearCollectionScope()
        destination = target
    }
    val scopeLabel = selectedFolderPath?.let { "Folder · $it" } ?: selectedCustomCollection?.let { "Collection · $it" }
    val screenStateKey = if (destination == AppDestination.LIBRARY) "LIBRARY:${selectedFolderPath}:${selectedCustomCollection}" else destination.name
    @Composable fun AppSettings() {
        SettingsExperience(coverWidth, { coverWidth = it; prefs.edit().putInt("coverWidth", it).apply() },
            accentName, { accentName = it; prefs.edit().putString("accent", it).apply() },
            lightMode, { lightMode = it; prefs.edit().putBoolean("lightMode", it).apply() },
            showReaderProgress, { showReaderProgress = it; prefs.edit().putBoolean("showReaderProgress", it).apply() },
            keepScreenAwake, { keepScreenAwake = it; prefs.edit().putBoolean("keepScreenAwake", it).apply() },
            folder != null, { picker.launch(null) }, { folder?.let(::scan) }, { licenseDialog = true }) {
            LibraryToolsPanel(panSensitivity, { panSensitivity = it; prefs.edit().putFloat("panSensitivity", it).apply() },
                momentumStrength, { momentumStrength = it; prefs.edit().putFloat("momentumStrength", it).apply() },
                coverStorageBytes, toolsBusy, toolsMessage,
                { exportBackup.launch("tablet-comics-reading-backup.json") },
                { importBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                { scope.launch {
                    toolsBusy = true
                    try {
                        withContext(Dispatchers.IO) { archiveIoLock.withLock { diskCoverCache.clear(); coverCache.evictAll() } }
                        coverRevision++
                        coverStorageBytes = withContext(Dispatchers.IO) { diskCoverCache.sizeBytes() }
                        toolsMessage = "Cover cache cleared."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { toolsMessage = failure.message ?: "Could not clear the cover cache." }
                    finally { toolsBusy = false }
                } })
        }
    }
    ComicsTheme(accentName, darkTheme = !lightMode) {
        if (currentBook == null) {
            val wideLayout = LocalConfiguration.current.screenWidthDp >= 840
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                if (wideLayout) {
                    Row(Modifier.fillMaxSize()) {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.background, header = {
                            Image(painterResource(R.drawable.ic_brand_logo), contentDescription = "PanelFlow", modifier = Modifier.padding(vertical = 20.dp).size(42.dp))
                        }) {
                            AppDestination.entries.forEach { item ->
                                NavigationRailItem(selected = destination == item, onClick = { navigateTo(item) },
                                    icon = { Text(when (item) { AppDestination.HOME -> "⌂"; AppDestination.LIBRARY -> "▤"; AppDestination.COLLECTIONS -> "▱"; AppDestination.SETTINGS -> "⚙" }, style = MaterialTheme.typography.titleLarge) },
                                    label = { Text(item.label) }, alwaysShowLabel = true)
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            DestinationContent(destinationStates, screenStateKey) {
when (destination) {
                                AppDestination.HOME -> ReadingHome(featuredBook, featuredEntry, otherReads, diskCoverCache,
                                    libraryLoading, libraryMessage, books.isNotEmpty(), { startReading(it) }, { detailBook = it }, { picker.launch(null) })
                                AppDestination.LIBRARY -> ComicLibraryScreen(shelfEntries.mapNotNull { entry -> booksByUri[entry.uri]?.let { it to entry } },
                                    diskCoverCache, searchQuery, { searchQuery = it }, filter, { filter = it }, sort, { sortOptionsVisible = true },
                                    coverWidth, { coverWidth = it; prefs.edit().putInt("coverWidth", it).apply() }, { startReading(it) }, { detailBook = it }, scopeLabel, ::clearCollectionScope)
                                AppDestination.COLLECTIONS -> CollectionsExperience(collectionShelves, diskCoverCache, { shelf ->
                                    selectedFolderPath = shelf.folderPath; selectedCustomCollection = shelf.customName; destination = AppDestination.LIBRARY
                                }, { createCollectionVisible = true })
                                AppDestination.SETTINGS -> AppSettings()
                            }
                        }
                        }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        DestinationContent(destinationStates, screenStateKey) {
when (destination) {
                            AppDestination.HOME -> ReadingHome(featuredBook, featuredEntry, otherReads, diskCoverCache,
                                libraryLoading, libraryMessage, books.isNotEmpty(), { startReading(it) }, { detailBook = it }, { picker.launch(null) })
                            AppDestination.LIBRARY -> ComicLibraryScreen(shelfEntries.mapNotNull { entry -> booksByUri[entry.uri]?.let { it to entry } },
                                diskCoverCache, searchQuery, { searchQuery = it }, filter, { filter = it }, sort, { sortOptionsVisible = true },
                                coverWidth, { coverWidth = it; prefs.edit().putInt("coverWidth", it).apply() }, { startReading(it) }, { detailBook = it }, scopeLabel, ::clearCollectionScope)
                            AppDestination.COLLECTIONS -> CollectionsExperience(collectionShelves, diskCoverCache, { shelf ->
                                selectedFolderPath = shelf.folderPath; selectedCustomCollection = shelf.customName; destination = AppDestination.LIBRARY
                            }, { createCollectionVisible = true })
                            AppDestination.SETTINGS -> AppSettings()
                        }
                        }
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        AppDestination.entries.forEach { item ->
                            NavigationBarItem(selected = destination == item, onClick = { navigateTo(item) },
                                icon = { Text(when (item) { AppDestination.HOME -> "⌂"; AppDestination.LIBRARY -> "▤"; AppDestination.COLLECTIONS -> "▱"; AppDestination.SETTINGS -> "⚙" }) },
                                label = { Text(item.label) }, alwaysShowLabel = true)
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                session.current?.let { unit ->
                    PageTurnReader(unit, session.previous, session.next, rightToLeft = rightToLeft,
                        layoutKey = "$landscape:$spreadMode:$rightToLeft",
                        bookKey = currentBook.uri.toString(),
                        totalPages = session.totalPages,
                        fitMode = fitMode, cropBorders = cropBorders, keepZoom = keepZoom,
                        panSensitivity = panSensitivity, momentumStrength = momentumStrength,
                        initialZoomScale = prefs.getFloat("zoom:${currentBook.uri}", 1f),
                        onZoomScaleChanged = { prefs.edit().putFloat("zoom:${currentBook.uri}", it).apply() },
                        onTurn = { target -> session.navigate(target.startIndex) },
                        onToggleControls = { controlsVisible = !controlsVisible },
                        onEndReached = { finishDialog = true },
                        showZoomReset = controlsVisible,
                        onDecodeRegions = { source, regions, sample -> session.decodeRegions(source, regions, sample) })
                }
                if (session.loading && session.current == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (session.loading && session.current != null) {
                    LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
                }
                if (showReaderProgress && !controlsVisible && session.totalPages > 0) {
                    val page = (session.requestedPageIndex ?: session.current?.startIndex ?: 0).coerceIn(0, session.totalPages - 1)
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth((page + 1f) / session.totalPages).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary))
                    }
                }
                session.error?.let { message ->
                    Surface(Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(20.dp), shadowElevation = 12.dp) {
                        Column(Modifier.padding(16.dp)) {
                            Text(message)
                            Row {
                                TextButton(onClick = session::retry) { Text("Retry") }
                                if (session.current != null) {
                                    TextButton(onClick = session::returnToCurrentPage) { Text("Return to current page") }
                                } else {
                                    TextButton(onClick = ::closeBook) { Text("Back to library") }
                                }
                            }
                        }
                    }
                }
                if (controlsVisible || session.current == null) {
                    Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 16.dp)
                        .statusBarsPadding().padding(top = 10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape = RoundedCornerShape(18.dp), shadowElevation = 8.dp) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = ::closeBook) { Text("‹  Library") }
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(currentBook?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall)
                                Text("IMMERSIVE READER", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = ::toggleBookmark) { Text(if (currentIsBookmarked) "★" else "☆") }
                        }
                    }
                }
                if (controlsVisible && session.current != null) {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        if (showPageThumbnails) {
                            LazyRow(Modifier.fillMaxWidth().padding(bottom = 8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .94f), RoundedCornerShape(16.dp)).padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(pageThumbnails.keys.sorted()) { pageIndex ->
                                    Column(Modifier.width(58.dp).clickable { session.navigate(pageIndex) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                        pageThumbnails[pageIndex]?.let { bitmap -> Image(bitmap.asImageBitmap(), "Page ${pageIndex + 1}",
                                            Modifier.size(50.dp, 68.dp), contentScale = ContentScale.Fit) }
                                            ?: Surface(Modifier.size(50.dp, 68.dp), color = MaterialTheme.colorScheme.surfaceVariant) {}
                                        Text("${pageIndex + 1}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape = RoundedCornerShape(22.dp), shadowElevation = 12.dp) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { turn(rightToLeft) }, enabled = rightToLeft || session.previous != null) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                                if (session.totalPages > 1) Slider(value = seekDraft.coerceIn(0f, (session.totalPages - 1).toFloat()),
                                    onValueChange = { seekDraft = it }, onValueChangeFinished = {
                                        val target = SeekState(draftIndex = seekDraft.roundToInt()).destination(session.totalPages, spreadMode)
                                        seekDraft = target.toFloat(); session.navigate(target)
                                    }, valueRange = 0f..(session.totalPages - 1).toFloat(), modifier = Modifier.weight(1f))
                                val shownPage = session.requestedPageIndex ?: session.current!!.startIndex
                                Text("${shownPage + 1}/${session.totalPages}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                                IconButton(onClick = { turn(!rightToLeft) }, enabled = !rightToLeft || session.previous != null) { Text("›", style = MaterialTheme.typography.headlineSmall) }
                                TextButton(onClick = { showPageThumbnails = !showPageThumbnails }) { Text("Pages") }
                                TextButton(onClick = { readerOptionsVisible = true }) { Text("Options") }
                            }
                        }
                    }
                }
            }
        }
        if (readerOptionsVisible) {
            AlertDialog(onDismissRequest = { readerOptionsVisible = false }, title = { Text("Reading options") },
                text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                    Text("Page fit", style = MaterialTheme.typography.titleSmall)
                    PageFit.entries.forEach { mode ->
                        TextButton(onClick = {
                            fitMode = mode
                            currentBook?.let { prefs.edit().putString("fit:${it.uri}", mode.name).apply() }
                        }) { Text((if (fitMode == mode) "✓  " else "") + when (mode) {
                            PageFit.PAGE -> "Whole page"; PageFit.WIDTH -> "Fit width"; PageFit.HEIGHT -> "Fit height"
                        }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Trim plain borders", Modifier.weight(1f)); Switch(cropBorders, {
                            cropBorders = it; currentBook?.let { book -> prefs.edit().putBoolean("crop:${book.uri}", it).apply() }
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Keep zoom between pages", Modifier.weight(1f)); Switch(keepZoom, {
                            keepZoom = it; currentBook?.let { book -> prefs.edit().putBoolean("keepZoom:${book.uri}", it).apply() }
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Read right to left", Modifier.weight(1f)); Switch(rightToLeft, {
                        rightToLeft = it; currentBook?.let { book -> prefs.edit().putBoolean("rtl:${book.uri}", it).apply() }
                    }) }
                    if (landscape) Row(verticalAlignment = Alignment.CenterVertically) { Text("Two-page spreads", Modifier.weight(1f)); Switch(spreads, {
                        spreads = it; currentBook?.let { book -> prefs.edit().putBoolean("spreads:${book.uri}", it).apply() }
                    }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Keep screen awake", Modifier.weight(1f)); Switch(keepScreenAwake, {
                        keepScreenAwake = it; prefs.edit().putBoolean("keepScreenAwake", it).apply()
                    }) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Thin progress line", Modifier.weight(1f)); Switch(showReaderProgress, {
                        showReaderProgress = it; prefs.edit().putBoolean("showReaderProgress", it).apply()
                    }) }
                    TextButton(onClick = { readerOptionsVisible = false; showBookmarks = true }) { Text("Bookmarks (${bookmarks.size})") }
                    if (session.current != null) TextButton(onClick = { readerOptionsVisible = false; showPageThumbnails = true }) { Text("Show page previews") }
                    TextButton(onClick = { readerOptionsVisible = false; closeBook() }) { Text("Return to library") }
                } }, confirmButton = { TextButton(onClick = { readerOptionsVisible = false }) { Text("Done") } })
        }
        if (sortOptionsVisible) {
            AlertDialog(onDismissRequest = { sortOptionsVisible = false }, title = { Text("Sort comics") },
                text = { Column { LibrarySort.entries.forEach { choice ->
                    TextButton(onClick = { sort = choice; sortOptionsVisible = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (sort == choice) "✓  ${choice.label}" else choice.label)
                    }
                } } }, confirmButton = { TextButton(onClick = { sortOptionsVisible = false }) { Text("Done") } })
        }
        if (createCollectionVisible) {
            AlertDialog(onDismissRequest = { createCollectionVisible = false }, title = { Text("New collection") },
                text = { OutlinedTextField(newCollectionName, { newCollectionName = it }, label = { Text("Collection name") }, singleLine = true) },
                confirmButton = { TextButton(enabled = newCollectionName.isNotBlank(), onClick = {
                    val name = newCollectionName.trim()
                    scope.launch { repository.createCollection(name); customCollections = repository.customCollections() }
                    newCollectionName = ""; createCollectionVisible = false
                }) { Text("Create") } }, dismissButton = { TextButton(onClick = { createCollectionVisible = false }) { Text("Cancel") } })
        }
        if (licenseDialog) {
            val license = remember(context) {
                context.resources.openRawResource(R.raw.unrar_license).bufferedReader().use { it.readText() }
            }
            AlertDialog(
                onDismissRequest = { licenseDialog = false },
                title = { Text("About & third-party licenses") },
                text = {
                    Text(
                        "RAR support uses RARLab UnRAR 7.30 beta 1 under the included UnRAR license. PanelFlow reads and extracts existing RAR archives; it does not create RAR archives.\n\n$license",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = { TextButton(onClick = { licenseDialog = false }) { Text("Close") } },
            )
        }

        detailBook?.let { selectedBook ->
            val entry = catalogEntries.firstOrNull { it.uri == selectedBook.uri.toString() }
            var coverPageInput by remember(selectedBook.uri) { mutableStateOf((prefs.getInt("coverPage:${selectedBook.uri}", 0) + 1).toString()) }
            var coverWorkBusy by remember(selectedBook.uri) { mutableStateOf(false) }
            var coverMessage by remember(selectedBook.uri) { mutableStateOf<String?>(null) }
            LaunchedEffect(selectedBook.uri) { newCollectionName = "" }
            ModalBottomSheet(onDismissRequest = { detailBook = null }, containerColor = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoverPreview(selectedBook, diskCoverCache, Modifier.size(76.dp, 106.dp).clip(RoundedCornerShape(12.dp)))
                        Column(Modifier.padding(start = 14.dp)) {
                            Text(selectedBook.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(entry?.let(::comicStatus)?.label ?: "Unread", Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text("${selectedBook.format.uppercase()} comic", Modifier.padding(top = 3.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(top = 20.dp)) {
                        Text("${selectedBook.format.uppercase()} · ${entry?.pageCount?.takeIf { it > 0 } ?: "Page count unknown"} pages")
                        Text("${entry?.let(::comicStatus)?.label ?: "Unread"} · ${entry?.let(::progressFraction)?.takeUnless { it.isNaN() }?.let { "${(it * 100).roundToInt()}% read" } ?: "Not started"}",
                            modifier = Modifier.padding(top = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                detailBook = null
                                startReading(selectedBook)
                            }) { Text(if ((entry?.pageIndex ?: -1) >= 0) "Resume reading" else "Start reading") }
                            TextButton(onClick = {
                                scope.launch {
                                    repository.setFavorite(selectedBook.uri.toString(), entry?.favorite != true)
                                    refreshLibrary()
                                }
                            }) { Text(if (entry?.favorite == true) "Remove favorite" else "Add favorite") }
                            TextButton(onClick = {
                                scope.launch {
                                    repository.setFinished(selectedBook.uri.toString(), entry?.finished != true)
                                    refreshLibrary()
                                }
                            }) { Text(if (entry?.finished == true) "Mark reading" else "Mark finished") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                scope.launch {
                                    progressWriteMutex.withLock {
                                        repository.resetProgress(selectedBook.uri.toString())
                                        prefs.edit().remove("position:${selectedBook.uri}").remove("pages:${selectedBook.uri}").apply()
                                    }
                                    refreshLibrary()
                                }
                            }) { Text("Reset progress") }
                            TextButton(onClick = {
                                detailBook = null
                                startReading(selectedBook, fromBeginning = true)
                            }) { Text("Start from beginning") }
                        }
                        CoverToolsPanel(coverPageInput, { coverPageInput = it }, entry?.pageCount ?: 0,
                            coverWorkBusy, coverMessage,
                            onApply = {
                                val page = coverPageInput.toIntOrNull()?.minus(1)
                                if (page != null && page in 0 until ArchiveLimits.MAX_PAGES) scope.launch {
                                    coverWorkBusy = true
                                    try {
                                        val loaded = withContext(Dispatchers.IO) {
                                            val job = currentCoroutineContext()[Job] ?: throw CancellationException("Cover selection cancelled.")
                                            archiveIoLock.withLock {
                                                val bitmap = readCoverBitmap(context, selectedBook, page, { job.ensureActive() }, archiveCancellationRegistrar(job))
                                                    ?: throw IOException("No image was found on that page.")
                                                val key = coverPreviewKey(selectedBook, prefs, page)
                                                diskCoverCache.put(key, bitmap)
                                                key to bitmap
                                            }
                                        }
                                        prefs.edit().putInt("coverPage:${selectedBook.uri}", page).apply()
                                        coverCache.put(loaded.first, loaded.second)
                                        coverRevision++
                                        coverMessage = "Page ${page + 1} selected as the cover."
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { coverMessage = failure.message ?: "Could not select that cover page." }
                                    finally { coverWorkBusy = false }
                                }
                            }, onReset = {
                                prefs.edit().remove("coverPage:${selectedBook.uri}").apply()
                                coverPageInput = "1"; coverRevision++
                                coverMessage = "Default cover restored."
                            }, onRetry = {
                                val key = "coverAttempt:${selectedBook.uri}"
                                prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
                                coverRevision++
                                coverMessage = "Retrying the cover."
                            })
                        if (customCollections.isNotEmpty()) {
                            Text("Custom collections", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                            customCollections.forEach { name ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = name in (entry?.customCollections ?: emptySet()),
                                        onCheckedChange = { included ->
                                            scope.launch {
                                                repository.setComicInCollection(selectedBook.uri.toString(), name, included)
                                                refreshLibrary()
                                            }
                                        },
                                    )
                                    Text(name)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = newCollectionName, onValueChange = { newCollectionName = it },
                            label = { Text("New collection") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        TextButton(onClick = {
                            val name = newCollectionName.trim()
                            scope.launch {
                                if (name.isNotEmpty()) {
                                    repository.createCollection(name)
                                    repository.setComicInCollection(selectedBook.uri.toString(), name, true)
                                    newCollectionName = ""
                                }
                                refreshLibrary()
                            }
                        }, enabled = newCollectionName.isNotBlank()) { Text("Create and add") }
                    }
                }
            }
        }
        if (showBookmarks) {
            AlertDialog(
                onDismissRequest = { showBookmarks = false },
                title = { Text("Bookmarks") },
                text = {
                    if (bookmarks.isEmpty()) Text("No bookmarked pages in this comic.")
                    else Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                        bookmarks.forEach { bookmark ->
                            TextButton(onClick = {
                                session.navigate(bookmark.pageIndex)
                                showBookmarks = false
                            }, modifier = Modifier.fillMaxWidth()) { Text("Page ${bookmark.pageIndex + 1}") }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showBookmarks = false }) { Text("Close") } },
            )
        }
        if (finishDialog) {
            AlertDialog(
                onDismissRequest = { finishDialog = false },
                title = { Text("You reached the end") },
                text = { Text("Mark ${currentBook?.title.orEmpty()} as finished? You can still reopen it from the library.") },
                confirmButton = { TextButton(onClick = { finishDialog = false }) { Text("Keep reading") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = ::finishAndReturnToLibrary) { Text("Library") }
                        TextButton(onClick = {
                            val next = nextIssueBook
                            if (next == null) finishAndReturnToLibrary()
                            else {
                                finishDialog = false
                                nextIssueConfirmation = next
                            }
                        }) { Text(if (nextIssueBook == null) "Done" else "Next issue") }
                    }
                },
            )
        }
        nextIssueConfirmation?.let { next ->
            AlertDialog(
                onDismissRequest = { nextIssueConfirmation = null; finishDialog = true },
                title = { Text("Open next issue?") },
                text = { Text("Finish ${currentBook?.title.orEmpty()} and open ${next.title}?") },
                confirmButton = { TextButton(onClick = { finishAndOpenNext(next) }) { Text("Open next issue") } },
                dismissButton = { TextButton(onClick = { nextIssueConfirmation = null; finishDialog = true }) { Text("Cancel") } },
            )
        }
    }
}

private fun scanFolder(context: android.content.Context, uri: Uri, cancellationCheck: () -> Unit = {}): List<Book> {
    val root = DocumentFile.fromTreeUri(context, uri) ?: throw IOException("Cannot read selected folder.")
    val found = mutableListOf<Book>()
    val pending = ArrayDeque<Pair<DocumentFile, String>>()
    pending.add(root to "")
    while (pending.isNotEmpty()) {
        cancellationCheck()
        val (dir, relativePath) = pending.removeFirst()
        for (file in dir.listFiles()) {
            cancellationCheck()
            if (file.isDirectory) {
                val childPath = listOf(relativePath, file.name.orEmpty()).filter { it.isNotBlank() }.joinToString("/")
                pending.add(file to childPath)
            }
            else if (file.isFile) {
                val name = file.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in setOf("cbz", "zip", "cbr", "rar"))
                    found.add(Book(
                        file.uri,
                        name.substringBeforeLast('.'),
                        if (ext in setOf("cbz", "zip")) "zip" else "rar",
                        size = file.length(),
                        lastModified = file.lastModified(),
                        folderPath = relativePath,
                    ))
            }
        }
    }
    return found.sortedBy { it.title.lowercase() }
}

private fun coverPreviewKey(book: Book, prefs: android.content.SharedPreferences, page: Int = prefs.getInt("coverPage:${book.uri}", -1)): String =
    "${coverCacheKey(book.uri.toString(), book.size, book.lastModified)}#preview-v4-$COVER_PREVIEW_MAX_DIMENSION#page-$page#attempt-${prefs.getInt("coverAttempt:${book.uri}", 0)}"

private fun readCoverBitmap(
    context: android.content.Context, book: Book, page: Int, cancellationCheck: () -> Unit,
    registration: dev.tabletcomics.archive.ArchiveCancellationRegistrar,
): Bitmap? {
    val format = detectArchiveFormat(context, book.uri, book.format, cancellationCheck, registration)
    val reader = archiveReader(context, format)
    val bytes = if (page < 0) reader.readPreview(book.uri, cancellationCheck, registration)
    else reader.open(book.uri, cancellationCheck, registration).use { archive ->
        val selected = archive.pages.getOrNull(page) ?: throw IOException("This comic has ${archive.pages.size} pages. Choose a page in that range.")
        archive.readPage(selected, cancellationCheck, registration)
    }
    cancellationCheck()
    return bytes?.let { decodeImage(it, COVER_PREVIEW_MAX_DIMENSION) }
}

@Composable
internal fun coverPreview(book: Book, diskCache: CoverDiskCache): Bitmap? {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("reader", 0) }
    val revision = coverRevision
    val previewKey = coverPreviewKey(book, prefs)
    val selectedPage = prefs.getInt("coverPage:${book.uri}", -1)
    var preview by remember(previewKey, revision) { mutableStateOf(coverCache.get(previewKey)) }
    LaunchedEffect(previewKey, book.format, revision) {
        if (preview != null) return@LaunchedEffect
        preview = withContext(Dispatchers.IO) { diskCache.get(previewKey) }?.also { coverCache.put(previewKey, it) }
        if (preview != null) return@LaunchedEffect
        val job = currentCoroutineContext()[Job] ?: return@LaunchedEffect
        val cancellationCheck = { job.ensureActive() }
        val cancellationRegistration = archiveCancellationRegistrar(job)
        preview = archiveIoLock.withLock { withContext(Dispatchers.IO) {
            try {
                readCoverBitmap(context, book, selectedPage, cancellationCheck, cancellationRegistration)
                    ?.also {
                        coverCache.put(previewKey, it)
                        diskCache.put(previewKey, it)
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } }
    }
    return preview
}

@Composable
internal fun CoverPreview(book: Book, diskCache: CoverDiskCache, modifier: Modifier = Modifier) {
    val preview = coverPreview(book, diskCache)
    if (preview != null) {
        Image(preview.asImageBitmap(), contentDescription = "Cover preview for ${book.title}",
            modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(contentAlignment = Alignment.Center) { Text(book.format.uppercase()) }
        }
    }
}

@Composable
private fun LibrarySectionTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicCoverCard(
    book: Book,
    entry: ComicLibraryEntry,
    diskCache: CoverDiskCache,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val fraction = progressFraction(entry).let { if (it.isNaN()) 0f else it }
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(9.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.70f).clip(RoundedCornerShape(12.dp))) {
                CoverPreview(book, diskCache, Modifier.fillMaxSize())
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(76.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f)))))
                Surface(Modifier.align(Alignment.TopStart).padding(7.dp), shape = RoundedCornerShape(50),
                    color = Color(0xD9141720)) {
                    Text(comicStatus(entry).label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
                if (entry.favorite) Surface(Modifier.align(Alignment.TopEnd).padding(7.dp), shape = RoundedCornerShape(50),
                    color = Color(0xD9141720)) {
                    Text("★", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text(book.format.uppercase(), Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 9.dp),
                    style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .88f))
            }
            Text(book.title, Modifier.fillMaxWidth().padding(top = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, minLines = 2, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (entry.pageIndex >= 0 && entry.pageCount > 0)
                    "${(fraction * 100).roundToInt()}% · ${entry.pageIndex + 1}/${entry.pageCount} pages"
                else "${book.format.uppercase()} · Not started",
                Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.fillMaxWidth().padding(top = 9.dp).height(4.dp).background(Color(0xFF2B3040), RoundedCornerShape(4.dp))) {
                if (entry.pageIndex >= 0 && entry.pageCount > 0)
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(book: Book, entry: ComicLibraryEntry, diskCache: CoverDiskCache, onClick: () -> Unit) {
    val fraction = progressFraction(entry).let { if (it.isNaN()) 0f else it }
    Card(Modifier.width(292.dp).clickable(onClick = onClick), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverPreview(book, diskCache, Modifier.size(width = 76.dp, height = 108.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text("Page ${entry.pageIndex + 1} of ${entry.pageCount}", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 7.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp).background(Color(0xFF2B3040), RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
                }
                Text("RESUME READING  ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun LibraryCollectionCard(
    collection: LibraryShelfCollection,
    selected: Boolean,
    diskCache: CoverDiskCache,
    onClick: () -> Unit,
) {
    Card(Modifier.width(204.dp).clickable(onClick = onClick), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            collection.cover?.let { CoverPreview(it, diskCache,
                Modifier.size(width = 58.dp, height = 80.dp).clip(RoundedCornerShape(8.dp))) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(collection.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                Text("${collection.issueCount} ${if (collection.issueCount == 1) "comic" else "comics"}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                if (selected) Text("Selected", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

private fun archiveReader(context: android.content.Context, format: ArchiveFormat): ArchiveReader =
    if (format == ArchiveFormat.RAR) RarArchiveReader(context) else ZipArchiveReader(context)

private fun decodePage(
    archive: OpenArchive,
    page: ComicPage,
    maxDimension: Int = 2048,
    cancellationCheck: () -> Unit = {},
): Bitmap {
    return decodeImage(archive.readPage(page, cancellationCheck, dev.tabletcomics.archive.ArchiveCancellationRegistrar.NONE), maxDimension)
}

private fun decodeImage(bytes: ByteArray, maxDimension: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
    if (!ArchiveLimits.isImageSizeAllowed(bounds.outWidth, bounds.outHeight))
        throw IOException("Unsupported or oversized image page.")
    var sample = 1
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
    return BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null,
        BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw IOException("Cannot decode image page.")
}
