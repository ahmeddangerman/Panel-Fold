package dev.tabletcomics

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi
import dev.tabletcomics.library.ComicLibraryEntry
import dev.tabletcomics.library.CoverDiskCache
import dev.tabletcomics.library.LibraryFilter
import dev.tabletcomics.library.LibrarySort
import dev.tabletcomics.library.comicStatus
import dev.tabletcomics.library.progressFraction
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AppDestination(val label: String) { HOME("Home"), LIBRARY("Library"), COLLECTIONS("Collections"), SETTINGS("Settings") }

@Composable
internal fun ReadingHome(
    featured: Book?, entry: ComicLibraryEntry?, otherReads: List<Pair<Book, ComicLibraryEntry>>,
    diskCache: CoverDiskCache, loading: Boolean, message: String?, hasLibrary: Boolean,
    onResume: (Book) -> Unit, onDetails: (Book) -> Unit, onChooseFolder: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 32.dp, top = 30.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GOOD TO SEE YOU", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Your next chapter starts here.", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 5.dp))
            }
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        message?.let { Text(it, Modifier.padding(horizontal = 36.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
        if (featured != null) {
            val fraction = entry?.let(::progressFraction)?.takeUnless { it.isNaN() } ?: 0f
            val heroBitmap = coverPreview(featured, diskCache)
            val fallbackTint = MaterialTheme.colorScheme.primary
            var tint by remember(featured.uri) { mutableStateOf(fallbackTint) }
            androidx.compose.runtime.LaunchedEffect(heroBitmap) {
                if (heroBitmap != null) tint = withContext(Dispatchers.Default) {
                    var red = 0L; var green = 0L; var blue = 0L; var samples = 0
                    for (y in 0 until 12) for (x in 0 until 12) {
                        val pixel = heroBitmap.getPixel((x * heroBitmap.width / 12).coerceAtMost(heroBitmap.width - 1),
                            (y * heroBitmap.height / 12).coerceAtMost(heroBitmap.height - 1))
                        red += android.graphics.Color.red(pixel); green += android.graphics.Color.green(pixel); blue += android.graphics.Color.blue(pixel); samples++
                    }
                    Color(android.graphics.Color.rgb((red / samples / 2).toInt(), (green / samples / 2).toInt(), (blue / samples / 2).toInt()))
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                val landscapeFeature = maxWidth >= 680.dp
                val heroModifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surface, tint.copy(alpha = .12f), MaterialTheme.colorScheme.surface)))
                    .padding(22.dp)
                if (landscapeFeature) Row(heroModifier.heightIn(min = 250.dp, max = 340.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoverPreview(featured, diskCache, Modifier.fillMaxHeight().widthIn(min = 150.dp, max = 220.dp).aspectRatio(.70f).clip(RoundedCornerShape(18.dp)))
                    HomeFeatureDetails(featured, entry, fraction, onResume, onDetails, Modifier.weight(1f).padding(start = 30.dp, end = 18.dp))
                } else Column(heroModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    CoverPreview(featured, diskCache, Modifier.width(156.dp).height(220.dp).clip(RoundedCornerShape(18.dp)))
                    HomeFeatureDetails(featured, entry, fraction, onResume, onDetails, Modifier.fillMaxWidth().padding(top = 22.dp))
                }
            }
        } else if (!hasLibrary) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp), shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.Start) {
                    Text("Make room for a good story.", style = MaterialTheme.typography.headlineMedium)
                    Text("Choose a folder to bring your CBZ, ZIP, CBR and RAR comics onto your shelf.",
                        Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onChooseFolder, Modifier.padding(top = 20.dp), shape = RoundedCornerShape(14.dp)) { Text("Choose comic folder") }
                }
            }
        } else {
            Text("Your shelf is all caught up.", Modifier.padding(horizontal = 36.dp, vertical = 40.dp),
                style = MaterialTheme.typography.headlineSmall)
        }
        if (otherReads.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 32.dp, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                Text("On your reading shelf", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                Text("${otherReads.size} in progress", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            LazyRow(contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(otherReads, key = { it.first.uri.toString() }) { (book, readEntry) ->
                    ShelfReadCard(book, readEntry, diskCache) { onResume(book) }
                }
            }
        }
    }
}

@Composable
private fun HomeFeatureDetails(book: Book, entry: ComicLibraryEntry?, fraction: Float, onResume: (Book) -> Unit,
                              onDetails: (Book) -> Unit, modifier: Modifier) {
    Column(modifier) {
        Text(if (entry?.finished == true) "READY FOR ANOTHER READ" else "CONTINUE YOUR STORY",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(book.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.displaySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(entry?.let { if (it.pageIndex >= 0 && it.pageCount > 0) "Page ${it.pageIndex + 1} of ${it.pageCount} · ${(fraction * 100).roundToInt()}%" else "Ready to read" } ?: "Ready to read",
            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth(.78f).padding(top = 12.dp), color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant)
        Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onResume(book) }, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 13.dp)) {
                Text(if ((entry?.pageIndex ?: -1) >= 0) "Resume reading" else "Start reading")
            }
            TextButton(onClick = { onDetails(book) }, Modifier.padding(start = 8.dp)) { Text("Details") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ComicLibraryScreen(
    books: List<Pair<Book, ComicLibraryEntry>>, diskCache: CoverDiskCache, query: String,
    onQuery: (String) -> Unit, filter: LibraryFilter, onFilter: (LibraryFilter) -> Unit,
    sort: LibrarySort, onSort: () -> Unit, coverSize: Int, onCoverSize: (Int) -> Unit,
    onOpen: (Book) -> Unit, onDetails: (Book) -> Unit,
    scopeLabel: String? = null, onClearScope: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 28.dp, top = 28.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Your collection", style = MaterialTheme.typography.headlineMedium); Text("${books.size} comics", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            OutlinedTextField(query, onQuery, Modifier.widthIn(min = 180.dp, max = 330.dp), singleLine = true,
                placeholder = { Text("Search titles") }, shape = RoundedCornerShape(16.dp))
            TextButton(onClick = onSort) { Text("Sort · ${sort.label}") }
        }
        if (scopeLabel != null) Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(scopeLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onClearScope) { Text("All comics") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryFilter.entries) { option -> FilterChip(filter == option, { onFilter(option) }, label = { Text(option.label) }) }
            item { TextButton(onClick = { onCoverSize((coverSize + 20).coerceIn(120, 240)) }) { Text("Cover size +") } }
            item { TextButton(onClick = { onCoverSize((coverSize - 20).coerceIn(120, 240)) }) { Text("−") } }
        }
        if (books.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No comics match this view.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyVerticalGrid(GridCells.Adaptive(coverSize.dp), Modifier.fillMaxSize().testTag("library-grid"),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(books, key = { it.first.uri.toString() }) { (book, entry) ->
                PosterCover(book, entry, diskCache, onOpen = { onOpen(book) }, onDetails = { onDetails(book) })
            }
        }
    }
}

@Composable
internal fun CollectionsExperience(collections: List<LibraryShelfCollection>, diskCache: CoverDiskCache,
                                   onSelect: (LibraryShelfCollection) -> Unit, onCreate: () -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().testTag("collections-list"),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Collections", style = MaterialTheme.typography.headlineMedium)
                    Text("A place for every series", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onCreate, shape = RoundedCornerShape(14.dp)) { Text("New collection") }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (collections.isEmpty()) item { Text("Collections you create will live here.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(collections, key = { it.key }) { shelf ->
            Surface(Modifier.fillMaxWidth().clickable { onSelect(shelf) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (shelf.cover != null) CoverPreview(shelf.cover, diskCache, Modifier.size(62.dp, 84.dp).clip(RoundedCornerShape(10.dp)))
                    else Surface(Modifier.size(62.dp, 84.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("▤", color = MaterialTheme.colorScheme.primary) }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(shelf.name, style = MaterialTheme.typography.titleLarge)
                        Text("${shelf.issueCount} ${if (shelf.issueCount == 1) "comic" else "comics"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Open  ›", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
internal fun SettingsExperience(
    coverSize: Int, onCoverSize: (Int) -> Unit, accent: String, onAccent: (String) -> Unit,
    lightMode: Boolean, onLightMode: (Boolean) -> Unit,
    progress: Boolean, onProgress: (Boolean) -> Unit, keepAwake: Boolean, onKeepAwake: (Boolean) -> Unit,
    hasFolder: Boolean, onChangeFolder: () -> Unit, onRefresh: () -> Unit, onAbout: () -> Unit,
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 36.dp, vertical = 30.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Make your reading space yours.", Modifier.padding(top = 5.dp, bottom = 22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsGroup("Appearance") {
            SettingSwitch("Light mode", lightMode, onLightMode)
            Text("Cover size · ${coverSize}dp", style = MaterialTheme.typography.titleSmall)
            Slider(coverSize.toFloat(), { onCoverSize(it.roundToInt()) }, valueRange = 120f..240f, steps = 5)
            Text("Accent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf("violet", "teal", "amber").forEach { name -> FilterChip(accent == name, { onAccent(name) }, label = { Text(name.replaceFirstChar { it.uppercase() }) }) }
            }
        }
        SettingsGroup("Reading") {
            SettingSwitch("Thin progress line while reading", progress, onProgress)
            SettingSwitch("Keep screen awake", keepAwake, onKeepAwake)
        }
        SettingsGroup("Library") {
            Text("${if (hasFolder) "A comic folder is selected" else "No folder selected"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                OutlinedButton(onClick = onChangeFolder, shape = RoundedCornerShape(14.dp)) { Text("Change folder") }
                if (hasFolder) TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }
        extraContent()
        SettingsGroup("About") { TextButton(onClick = onAbout) { Text("About & licenses") } }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}

@Composable
private fun SettingSwitch(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable
private fun ShelfReadCard(book: Book, entry: ComicLibraryEntry, cache: CoverDiskCache, onClick: () -> Unit) {
    val progress = progressFraction(entry).takeUnless { it.isNaN() } ?: 0f
    Surface(Modifier.width(270.dp).clickable(onClick = onClick), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverPreview(book, cache, Modifier.size(68.dp, 96.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text("${entry.pageIndex + 1} / ${entry.pageCount} pages", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCover(book: Book, entry: ComicLibraryEntry, cache: CoverDiskCache, onOpen: () -> Unit, onDetails: () -> Unit) {
    val progress = progressFraction(entry).takeUnless { it.isNaN() } ?: 0f
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onDetails)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.70f).clip(RoundedCornerShape(16.dp))) {
            CoverPreview(book, cache, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(68.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f)))))
            Text(comicStatus(entry).label.uppercase(), Modifier.align(Alignment.BottomStart).padding(10.dp), color = Color.White,
                style = MaterialTheme.typography.labelSmall)
            if (entry.favorite) Text("★", Modifier.align(Alignment.TopEnd).padding(10.dp), color = MaterialTheme.colorScheme.primary)
        }
        Text(book.title, Modifier.padding(top = 10.dp), maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        Text(if (entry.pageIndex >= 0 && entry.pageCount > 0) "${(progress * 100).roundToInt()}% · ${entry.pageIndex + 1}/${entry.pageCount}" else "${book.format.uppercase()} · Not started",
            Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().padding(top = 7.dp), color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant)
    }
}
