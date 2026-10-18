package cloud.wafflecommons.pixelbrainreader.ui.utils

import androidx.compose.runtime.staticCompositionLocalOf

/** The vault's note names (no `.md`) the editor's `[[` completion offers; only what the mode shows. */
val LocalNoteNames = staticCompositionLocalOf { emptyList<String>() }
