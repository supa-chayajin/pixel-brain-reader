package cloud.wafflecommons.pixelbrainreader.ui.utils

import androidx.compose.runtime.staticCompositionLocalOf
import cloud.wafflecommons.pixelbrainreader.data.utils.VaultFileIndex

/**
 * The vault's attachment index, provided once at the app root (MainActivity) so every markdown
 * surface — note view, chat bubbles — resolves `![[embeds]]` in memory instead of on the disk.
 */
val LocalVaultAttachments = staticCompositionLocalOf { VaultFileIndex.EMPTY }
