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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import dev.tabletcomics.reader.PageTurnStyle
import dev.tabletcomics.reader.ReaderRestoreState
import dev.tabletcomics.reader.SeekState
import dev.tabletcomics.reader.TurnNavigation
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
private data class Book(
    val uri: Uri,
    val title: String,
    val format: String,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val folderPath: String = "",
)
private data class LibraryShelfCollection(
    val key: String,
    val name: String,
    val issueCount: Int,
    val cover: Book,
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
    var style by remember { mutableStateOf(PageTurnStyle.PHOTO_SLIDE) }
    var styleMenu by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.RECENTLY_READ) }
    var selectedFolderPath by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCustomCollection by rememberSaveable { mutableStateOf<String?>(null) }
    var coverWidth by remember { mutableIntStateOf(prefs.getInt("coverWidth", 164).coerceIn(120, 240)) }
    var accentName by remember { mutableStateOf(prefs.getString("accent", "violet") ?: "violet") }
    var showReaderProgress by remember { mutableStateOf(prefs.getBoolean("showReaderProgress", false)) }
    var keepScreenAwake by remember { mutableStateOf(prefs.getBoolean("keepScreenAwake", true)) }
    var settingsDialog by remember { mutableStateOf(false) }
    var detailBook by remember { mutableStateOf<Book?>(null) }
    var newCollectionName by remember { mutableStateOf("") }
    var finishDialog by remember { mutableStateOf(false) }
    var nextIssueConfirmation by remember { mutableStateOf<Book?>(null) }
    var showPageThumbnails by remember { mutableStateOf(false) }
    var pageThumbnails by remember { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }
    var showBookmarks by remember { mutableStateOf(false) }
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
        session.close()
        activeReaderState.value = activeReaderState.value.clearActiveBook()
        controlsVisible = false
    }

    fun openBook(book: Book, fromBeginning: Boolean = false, restoredPageIndex: Int? = null) {
        rightToLeft = prefs.getBoolean("rtl:${book.uri}", false)
        spreads = prefs.getBoolean("spreads:${book.uri}", false)
        style = runCatching { PageTurnStyle.valueOf(prefs.getString("turnStyle:${book.uri}", "PHOTO_SLIDE")!!) }
            .getOrDefault(PageTurnStyle.PHOTO_SLIDE)
        controlsVisible = false
        activeReaderState.value = ReaderRestoreState(
            uri = book.uri.toString(),
            title = book.title,
            format = book.format,
            logicalPage = if (fromBeginning) 0 else restoredPageIndex
                ?: libraryEntries.firstOrNull { it.uri == book.uri.toString() }?.pageIndex?.takeIf { it >= 0 }
                ?: prefs.getInt("position:${book.uri}", 0),
        )
        if (fromBeginning) scope.launch {
            repository.resetProgress(book.uri.toString())
            prefs.edit().remove("position:${book.uri}").remove("pages:${book.uri}").apply()
            libraryEntries = repository.entries()
        }
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
            currentIsBookmarked = repository.toggleBookmark(active.uri.toString(), page)
            bookmarks = repository.bookmarks(active.uri.toString())
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
            if (folder == uri) scan(uri) else folder = uri
        } catch (error: Exception) { libraryMessage = error.message ?: "Cannot retain folder access." }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { StagedArchive.cleanupExpired(context.cacheDir) }
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
        style = runCatching { PageTurnStyle.valueOf(prefs.getString("turnStyle:${book.uri}", "PHOTO_SLIDE")!!) }
            .getOrDefault(PageTurnStyle.PHOTO_SLIDE)
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
    BackHandler(enabled = currentBook != null) { closeBook() }
    LaunchedEffect(currentBook, controlsVisible) {
        val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, currentBook == null)
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
                val first = group.firstOrNull() ?: return@forEach
                val cover = booksByUri[first.uri] ?: return@forEach
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
    val accentColor = when (accentName) {
        "teal" -> Color(0xFF69DCCB)
        "amber" -> Color(0xFFFFC857)
        else -> Color(0xFFD7B8FF)
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = accentColor, secondary = accentColor, tertiary = accentColor)) {
        if (currentBook == null) {
            Scaffold(topBar = {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_brand_logo), contentDescription = null, modifier = Modifier.size(40.dp))
                    Text("Tablet Comics", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { licenseDialog = true }) { Text("About & licenses") }
                    TextButton(onClick = { settingsDialog = true }) { Text("Settings") }
                    TextButton(onClick = { picker.launch(null) }) { Text("Choose folder") }
                    if (folder != null) TextButton(onClick = { folder?.let(::scan) }) { Text("Refresh") }
                }
            }) { insets ->
                Column(Modifier.fillMaxSize().padding(insets)) {
                    if (libraryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    libraryMessage?.let { Text(it, Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Search comics") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        var sortMenu by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { sortMenu = true }) { Text("Sort: ${sort.label}") }
                            DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                                LibrarySort.entries.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.label) }, onClick = { sort = option; sortMenu = false })
                                }
                            }
                        }
                    }
                    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(LibraryFilter.entries) { option ->
                            FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option.label) })
                        }
                        item {
                            var collectionMenu by remember { mutableStateOf(false) }
                            Box {
                                FilterChip(
                                    selected = selectedFolderPath != null || selectedCustomCollection != null,
                                    onClick = { collectionMenu = true },
                                    label = { Text(selectedCustomCollection ?: selectedFolderPath?.substringAfterLast('/') ?: "Collections") },
                                )
                                DropdownMenu(collectionMenu, onDismissRequest = { collectionMenu = false }) {
                                    DropdownMenuItem(text = { Text("All comics") }, onClick = {
                                        selectedFolderPath = null; selectedCustomCollection = null; collectionMenu = false
                                    })
                                    folderPaths.forEach { path ->
                                        DropdownMenuItem(text = { Text("Folder · ${path.substringAfterLast('/')}") }, onClick = {
                                            selectedFolderPath = path; selectedCustomCollection = null; collectionMenu = false
                                        })
                                    }
                                    customCollections.forEach { name ->
                                        DropdownMenuItem(text = { Text("Collection · $name") }, onClick = {
                                            selectedFolderPath = null; selectedCustomCollection = name; collectionMenu = false
                                        })
                                    }
                                }
                            }
                        }
                    }
                    if (folder == null) Text("Choose a folder containing comic archives.", Modifier.padding(16.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = coverWidth.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (continueEntries.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text("Continue reading", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(continueEntries, key = { "continue:${it.uri}" }) { entry ->
                                        val book = booksByUri[entry.uri] ?: return@items
                                        ContinueReadingCard(book, entry, diskCoverCache, onClick = { openBook(book) })
                                    }
                                }
                            }
                        }
                        if (collectionShelves.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text("Collections", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(collectionShelves, key = { it.key }) { shelf ->
                                        val selected = (shelf.folderPath != null && selectedFolderPath == shelf.folderPath) ||
                                            (shelf.customName != null && selectedCustomCollection == shelf.customName)
                                        LibraryCollectionCard(shelf, selected, diskCoverCache) {
                                            selectedFolderPath = shelf.folderPath
                                            selectedCustomCollection = shelf.customName
                                        }
                                    }
                                }
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${shelfEntries.size} comics", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = { coverWidth = (coverWidth - 20).coerceAtLeast(120); prefs.edit().putInt("coverWidth", coverWidth).apply() }) { Text("−") }
                                Text("Cover size", style = MaterialTheme.typography.labelMedium)
                                TextButton(onClick = { coverWidth = (coverWidth + 20).coerceAtMost(240); prefs.edit().putInt("coverWidth", coverWidth).apply() }) { Text("+") }
                            }
                        }
                        if (shelfEntries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(if (books.isEmpty()) "No comics here yet." else "No comics match these filters.", Modifier.padding(24.dp))
                        }
                        items(shelfEntries, key = { it.uri }) { entry ->
                            val book = booksByUri[entry.uri] ?: return@items
                            ComicCoverCard(book, entry, diskCoverCache,
                                onClick = { openBook(book) }, onLongClick = { detailBook = book })
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                session.current?.let { unit ->
                    PageTurnReader(unit, session.previous, session.next, rightToLeft, style,
                        layoutKey = "$landscape:$spreadMode:$rightToLeft",
                        onTurn = { target -> session.navigate(target.startIndex) },
                        onToggleControls = { controlsVisible = !controlsVisible },
                        onEndReached = { finishDialog = true })
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
                    Surface(Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.errorContainer) {
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
                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = .88f))
                        .statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = ::closeBook) { Text("Library") }
                        Text(currentBook?.title.orEmpty(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (controlsVisible && session.current != null) {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .9f))
                        .navigationBarsPadding().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val shownPage = session.requestedPageIndex ?: session.current!!.startIndex
                            Text("${shownPage + 1} / ${session.totalPages}", Modifier.weight(1f))
                            Text("Right to left")
                            Switch(rightToLeft, onCheckedChange = {
                                rightToLeft = it
                                currentBook?.let { book -> prefs.edit().putBoolean("rtl:${book.uri}", it).apply() }
                            })
                            if (landscape) {
                                Text("Spreads", Modifier.padding(start = 12.dp))
                                Switch(spreads, onCheckedChange = {
                                    spreads = it
                                    currentBook?.let { book -> prefs.edit().putBoolean("spreads:${book.uri}", it).apply() }
                                })
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { turn(rightToLeft) }, enabled = rightToLeft || session.previous != null) {
                                Text(if (rightToLeft) "Next" else "Previous")
                            }
                            Box {
                                TextButton(onClick = { styleMenu = true }) { Text(style.label) }
                                DropdownMenu(styleMenu, onDismissRequest = { styleMenu = false }) {
                                    PageTurnStyle.entries.forEach { choice ->
                                        DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                                            style = choice
                                            currentBook?.let { book -> prefs.edit().putString("turnStyle:${book.uri}", choice.name).apply() }
                                            styleMenu = false
                                        })
                                    }
                                }
                            }
                            Button(onClick = { turn(!rightToLeft) }, enabled = !rightToLeft || session.previous != null) {
                                Text(if (rightToLeft) "Previous" else "Next")
                            }
                        }
                        if (session.totalPages > 1) Slider(
                            value = seekDraft.coerceIn(0f, (session.totalPages - 1).toFloat()),
                            onValueChange = { seekDraft = it },
                            onValueChangeFinished = {
                                val destination = SeekState(draftIndex = seekDraft.roundToInt())
                                    .destination(session.totalPages, spreadMode)
                                seekDraft = destination.toFloat()
                                session.navigate(destination)
                            },
                            valueRange = 0f..(session.totalPages - 1).toFloat(),
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = ::toggleBookmark) { Text(if (currentIsBookmarked) "Remove bookmark" else "Bookmark page") }
                            TextButton(onClick = { showBookmarks = true }) { Text("Bookmarks (${bookmarks.size})") }
                            TextButton(onClick = { showPageThumbnails = !showPageThumbnails }) {
                                Text(if (showPageThumbnails) "Hide pages" else "Page previews")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Keep awake", style = MaterialTheme.typography.labelSmall)
                                Switch(keepScreenAwake, onCheckedChange = {
                                    keepScreenAwake = it
                                    prefs.edit().putBoolean("keepScreenAwake", it).apply()
                                })
                            }
                        }
                        if (showPageThumbnails) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(pageThumbnails.keys.sorted()) { pageIndex ->
                                    Column(Modifier.width(68.dp).clickable { session.navigate(pageIndex) },
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        pageThumbnails[pageIndex]?.let { bitmap ->
                                            Image(bitmap.asImageBitmap(), contentDescription = "Page ${pageIndex + 1}",
                                                Modifier.size(width = 58.dp, height = 78.dp), contentScale = ContentScale.Fit)
                                        } ?: Surface(Modifier.size(width = 58.dp, height = 78.dp), color = MaterialTheme.colorScheme.surfaceVariant) {}
                                        Text("${pageIndex + 1}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
                        "RAR support uses RARLab UnRAR 7.30 beta 1 under the included UnRAR license. Tablet Comics reads and extracts existing RAR archives; it does not create RAR archives.\n\n$license",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = { TextButton(onClick = { licenseDialog = false }) { Text("Close") } },
            )
        }
        if (settingsDialog) {
            AlertDialog(
                onDismissRequest = { settingsDialog = false },
                title = { Text("Library and reader") },
                text = {
                    Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                        Text("Cover size · ${coverWidth} dp")
                        Slider(
                            value = coverWidth.toFloat(), onValueChange = { coverWidth = it.roundToInt() },
                            onValueChangeFinished = { prefs.edit().putInt("coverWidth", coverWidth).apply() },
                            valueRange = 120f..240f, steps = 5,
                        )
                        Text("Accent color", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("violet", "teal", "amber").forEach { name ->
                                FilterChip(selected = accentName == name, onClick = {
                                    accentName = name
                                    prefs.edit().putString("accent", name).apply()
                                }, label = { Text(name.replaceFirstChar { it.uppercase() }) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Thin reader progress line", Modifier.weight(1f))
                            Switch(showReaderProgress, onCheckedChange = {
                                showReaderProgress = it
                                prefs.edit().putBoolean("showReaderProgress", it).apply()
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep screen awake while reading", Modifier.weight(1f))
                            Switch(keepScreenAwake, onCheckedChange = {
                                keepScreenAwake = it
                                prefs.edit().putBoolean("keepScreenAwake", it).apply()
                            })
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { settingsDialog = false }) { Text("Done") } },
            )
        }
        detailBook?.let { selectedBook ->
            val entry = catalogEntries.firstOrNull { it.uri == selectedBook.uri.toString() }
            LaunchedEffect(selectedBook.uri) { newCollectionName = "" }
            AlertDialog(
                onDismissRequest = { detailBook = null },
                title = { Text(selectedBook.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                        Text("${selectedBook.format.uppercase()} · ${entry?.pageCount?.takeIf { it > 0 } ?: "Page count unknown"} pages")
                        Text("${entry?.let(::comicStatus)?.label ?: "Unread"} · ${entry?.let(::progressFraction)?.takeUnless { it.isNaN() }?.let { "${(it * 100).roundToInt()}% read" } ?: "Not started"}",
                            modifier = Modifier.padding(top = 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                    repository.resetProgress(selectedBook.uri.toString())
                                    prefs.edit().remove("position:${selectedBook.uri}").remove("pages:${selectedBook.uri}").apply()
                                    refreshLibrary()
                                }
                            }) { Text("Reset progress") }
                            TextButton(onClick = {
                                detailBook = null
                                openBook(selectedBook, fromBeginning = true)
                            }) { Text("Start from beginning") }
                        }
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
                },
                confirmButton = { TextButton(onClick = { detailBook = null }) { Text("Close") } },
            )
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

@Composable
private fun coverPreview(book: Book, diskCache: CoverDiskCache): Bitmap? {
    val context = LocalContext.current
    val previewKey = coverCacheKey(book.uri.toString(), book.size, book.lastModified)
    var preview by remember(previewKey) { mutableStateOf(coverCache.get(previewKey)) }
    LaunchedEffect(previewKey, book.format) {
        if (preview != null) return@LaunchedEffect
        preview = withContext(Dispatchers.IO) { diskCache.get(previewKey) }?.also { coverCache.put(previewKey, it) }
        if (preview != null) return@LaunchedEffect
        val job = currentCoroutineContext()[Job] ?: return@LaunchedEffect
        val cancellationCheck = { job.ensureActive() }
        val cancellationRegistration = archiveCancellationRegistrar(job)
        preview = archiveIoLock.withLock { withContext(Dispatchers.IO) {
            try {
                val format = detectArchiveFormat(context, book.uri, book.format, cancellationCheck, cancellationRegistration)
                archiveReader(context, format)
                    .readPreview(book.uri, cancellationCheck, cancellationRegistration)
                    ?.let { decodeImage(it, 320) }
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
private fun CoverPreview(book: Book, diskCache: CoverDiskCache, modifier: Modifier = Modifier) {
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
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column(Modifier.padding(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(10.dp))) {
                CoverPreview(book, diskCache, Modifier.fillMaxSize())
                Surface(Modifier.align(Alignment.TopStart).padding(7.dp), shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .9f)) {
                    Text(comicStatus(entry).label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
                if (entry.favorite) Surface(Modifier.align(Alignment.TopEnd).padding(7.dp), shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("★", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(book.title, Modifier.fillMaxWidth().padding(top = 9.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, minLines = 2)
            Text(
                if (entry.pageIndex >= 0 && entry.pageCount > 0)
                    "${(fraction * 100).roundToInt()}% · ${entry.pageIndex + 1}/${entry.pageCount} pages"
                else "${book.format.uppercase()} · Not started",
                Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall,
            )
            Box(Modifier.fillMaxWidth().padding(top = 7.dp).height(3.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                if (entry.pageIndex >= 0 && entry.pageCount > 0)
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(book: Book, entry: ComicLibraryEntry, diskCache: CoverDiskCache, onClick: () -> Unit) {
    val fraction = progressFraction(entry).let { if (it.isNaN()) 0f else it }
    Card(Modifier.width(250.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverPreview(book, diskCache, Modifier.size(width = 64.dp, height = 92.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text("Page ${entry.pageIndex + 1} of ${entry.pageCount}", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp))
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
                }
                Text("Resume", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp))
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
    Card(Modifier.width(190.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverPreview(collection.cover, diskCache,
                Modifier.size(width = 52.dp, height = 72.dp).clip(RoundedCornerShape(7.dp)))
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
