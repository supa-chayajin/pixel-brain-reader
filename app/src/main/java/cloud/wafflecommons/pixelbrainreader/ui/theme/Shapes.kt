package cloud.wafflecommons.pixelbrainreader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * App shape scale — rounder than the M3 baseline for a softer, more expressive feel.
 * Wired into `MaterialExpressiveTheme(shapes = AppShapes)` so every component that reads
 * `MaterialTheme.shapes` (Card, Button, Chip, Dialog, TextField, …) picks these up.
 *
 * Baseline M3 → here:  xs 4→8 · sm 8→12 · md 12→16 · lg 16→24 · xl 28→32
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)
