package cloud.wafflecommons.pixelbrainreader.ui.main

import android.graphics.Typeface
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailPane(
    content: String?,
    fileName: String? = null,
    isLoading: Boolean,
    isFocusMode: Boolean,
    onToggleFocusMode: () -> Unit,
    isExpandedScreen: Boolean
) {
    // --- LOGIQUE DESIGN FENÊTRE ---

    // Forme : Si grand écran, on arrondit TOUS les coins pour faire une carte flottante
    val shape = if (isExpandedScreen) {
        RoundedCornerShape(24.dp)
    } else {
        RoundedCornerShape(0.dp)
    }

    // Marges :
    // - Sur Mobile : 0dp (Plein écran)
    // - Sur Tablette/Fold : On détache la vue des bords (Haut/Bas/Droite) et on laisse le Gutter à gauche
    val padding = if (isExpandedScreen) {
        if (isFocusMode) {
            // Mode Focus : On garde une marge "Zen" tout autour pour que ça reste une fenêtre centrée
            PaddingValues(16.dp)
        } else {
            // Mode Split : On détache du bord droit et du haut/bas,
            // la marge gauche est gérée par le Spacer du Scaffold principal ou ajoutée ici pour plus d'air
            PaddingValues(start = 8.dp, top = 12.dp, bottom = 12.dp, end = 12.dp)
        }
    } else {
        PaddingValues(0.dp)
    }

    // Couleurs Material Expressive
    // Header : Un ton légèrement coloré ou gris surface (Container High)
    val headerContainerColor = MaterialTheme.colorScheme.surfaceContainer
    // Body : Le fond de lecture (Surface ou SurfaceLowest pour un max de contraste)
    val contentContainerColor = MaterialTheme.colorScheme.surface

    Scaffold(
        modifier = Modifier
            .padding(padding) // Applique les marges externes
            .clip(shape)      // Coupe la vue selon la forme arrondie
            // AJOUT : Bordure subtile pour délimiter la carte du fond noir
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f), shape),
        containerColor = contentContainerColor,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (content != null) {
                            Column {
                                Text(
                                    text = fileName ?: "Document",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Markdown • Lecture seule",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    },
                    actions = {
                        if (isExpandedScreen) {
                            IconButton(onClick = onToggleFocusMode) {
                                Icon(
                                    imageVector = if (isFocusMode) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                                    contentDescription = "Mode Focus",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = headerContainerColor,
                        scrolledContainerColor = headerContainerColor
                    )
                )
                // Séparation explicite : Une ligne fine (Divider)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (content != null) {
                // Couleurs pour le contenu Markdown
                val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                val primaryColorInt = MaterialTheme.colorScheme.primary.toArgb()
                val tertiaryColorInt = MaterialTheme.colorScheme.tertiary.toArgb()

                // Fond des blocs de code : Surface Container High pour contraster avec le fond Surface
                val codeBgColor = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()

                val quoteColor = MaterialTheme.colorScheme.secondary.toArgb()
                val checkedColor = MaterialTheme.colorScheme.primary.toArgb()
                val uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

                AndroidView(
                    factory = { context ->
                        TextView(context).apply {
                            setTextColor(textColor)
                            textSize = 16f
                            // Marges internes du texte (dans la fenêtre)
                            setPadding(56, 24, 56, 200)
                            setLineSpacing(12f, 1.1f)
                        }
                    },
                    update = { tv ->
                        val markwon = Markwon.builder(tv.context)
                            .usePlugin(StrikethroughPlugin.create())
                            .usePlugin(TablePlugin.create(tv.context))
                            .usePlugin(LinkifyPlugin.create())
                            .usePlugin(TaskListPlugin.create(checkedColor, uncheckedColor, uncheckedColor))
                            .usePlugin(object : CorePlugin() {
                                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                                    // Titres
                                    builder.setFactory(org.commonmark.node.Heading::class.java) { _, _ ->
                                        arrayOf(
                                            RelativeSizeSpan(1.5f),
                                            StyleSpan(Typeface.BOLD),
                                            ForegroundColorSpan(primaryColorInt)
                                        )
                                    }

                                    // Code Blocks
                                    builder.setFactory(org.commonmark.node.FencedCodeBlock::class.java) { _, _ ->
                                        arrayOf(
                                            BackgroundColorSpan(codeBgColor),
                                            ForegroundColorSpan(tertiaryColorInt),
                                            TypefaceSpan("monospace"),
                                            RelativeSizeSpan(0.90f)
                                        )
                                    }

                                    // Code Inline
                                    builder.setFactory(org.commonmark.node.Code::class.java) { _, _ ->
                                        arrayOf(
                                            BackgroundColorSpan(codeBgColor),
                                            ForegroundColorSpan(tertiaryColorInt),
                                            TypefaceSpan("monospace"),
                                            RelativeSizeSpan(0.90f)
                                        )
                                    }

                                    // Citations
                                    builder.setFactory(org.commonmark.node.BlockQuote::class.java) { _, _ ->
                                        arrayOf(
                                            QuoteSpan(quoteColor),
                                            StyleSpan(Typeface.ITALIC)
                                        )
                                    }
                                }
                            })
                            .build()

                        markwon.setMarkdown(tv, content)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                )
            } else {
                Text(
                    text = "Sélectionnez un fichier",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
