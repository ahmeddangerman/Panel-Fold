package dev.tabletcomics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

@Composable
internal fun DestinationContent(holder: SaveableStateHolder, key: String, content: @Composable () -> Unit) {
    holder.SaveableStateProvider(key, content)
}

internal const val MAX_BACKUP_FILE_BYTES = 5 * 1024 * 1024

internal fun readBackupDocument(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, MAX_BACKUP_FILE_BYTES - output.size() + 1))
        if (count < 0) return output.toByteArray()
        if (output.size() + count > MAX_BACKUP_FILE_BYTES) throw IOException("Backup exceeds the 5 MiB limit.")
        output.write(buffer, 0, count)
    }
}

@Composable
internal fun LibraryToolsPanel(
    pan: Float, onPan: (Float) -> Unit,
    momentum: Float, onMomentum: (Float) -> Unit,
    cacheBytes: Long, busy: Boolean, message: String?,
    onExport: () -> Unit, onImport: () -> Unit, onClearCache: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gesture feel", style = MaterialTheme.typography.titleLarge)
            Text("Pan sensitivity · ${String.format(Locale.ROOT, "%.1f×", pan)}")
            Slider(pan, onPan, valueRange = .5f..2.5f, steps = 19)
            Text(if (momentum == 0f) "Momentum · Off" else "Momentum · ${String.format(Locale.ROOT, "%.1f×", momentum)}")
            Slider(momentum, onMomentum, valueRange = 0f..2f, steps = 19)
            Text("Lower sensitivity moves the image less. Momentum controls how far it glides after release.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reading data", style = MaterialTheme.typography.titleLarge)
            Text("Save progress, bookmarks, favorites, collections and reading settings to a backup file. Restoring merges matching comics; choose your comic folder separately.",
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onExport, enabled = !busy) { Text("Export backup") }
                OutlinedButton(onImport, enabled = !busy) { Text("Restore backup") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    Card(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cover storage", style = MaterialTheme.typography.titleLarge)
            Text(String.format(Locale.ROOT, "%.1f MiB cached", cacheBytes / (1024.0 * 1024)))
            Text("Covers regenerate as needed. Long-press a comic to retry its cover or select a different page.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClearCache, enabled = !busy) { Text("Clear cover cache") }
        }
    }
}

@Composable
internal fun CoverToolsPanel(
    pageInput: String, onPageInput: (String) -> Unit, pageCount: Int,
    busy: Boolean, message: String?, onApply: () -> Unit, onReset: () -> Unit, onRetry: () -> Unit,
) {
    Text("Cover artwork", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
    OutlinedTextField(pageInput, { if (it.length <= 5 && it.all(Char::isDigit)) onPageInput(it) },
        label = { Text(if (pageCount > 0) "Cover page · 1–$pageCount" else "Cover page number") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onApply, enabled = !busy && pageInput.toIntOrNull()?.let { it in 1..20_000 && (pageCount <= 0 || it <= pageCount) } == true) { Text("Apply cover") }
        TextButton(onReset, enabled = !busy) { Text("Default cover") }
        TextButton(onRetry, enabled = !busy) { Text("Retry cover") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
