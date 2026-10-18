package cloud.wafflecommons.pixelbrainreader.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.wafflecommons.pixelbrainreader.data.vault.CodeBlocks
import cloud.wafflecommons.pixelbrainreader.ui.components.rememberCopyToClipboard
import cloud.wafflecommons.pixelbrainreader.ui.work.SnippetCard

/** Under a rendered note: its code blocks, each with a copy button. Nothing when it has none. */
@Composable
fun CodeBlocksFooter(path: String, content: String) {
    val blocks = remember(content) { CodeBlocks.extract(path, content) }
    if (blocks.isEmpty()) return
    val copy = rememberCopyToClipboard()
    Column(modifier = Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Code in this note", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        blocks.forEach { block ->
            SnippetCard(block, path.substringAfterLast('/').removeSuffix(".md"), onCopy = { copy(block.code) }, onOpen = null)
        }
    }
}
