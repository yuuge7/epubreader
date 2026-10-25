package com.ebookreader.presentation.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.BoxScope
import coil.compose.AsyncImagePainter
import com.ebookreader.domain.model.Book
import com.ebookreader.domain.model.ReadingStatus

/** Book covers are 2:3. A cover drawn at any other shape is a cover cropped. */
const val COVER_ASPECT = 2f / 3f

/**
 * Bookcloth for covers the app has to draw itself, chosen from the title so a book keeps
 * its colour. Dark and muted, like library bindings; [ClothInk] reads on every one of
 * them (BinderyContrastTest).
 */
internal val ClothTones = listOf(
    Color(0xFF6E2A2C), // oxblood
    Color(0xFF23344F), // navy
    Color(0xFF2F4A3A), // forest
    Color(0xFF6E4E18), // ochre
    Color(0xFF3E4752), // slate
    Color(0xFF4B2E48), // plum
    Color(0xFF24504F), // teal
    Color(0xFF5A3F2B), // umber
)

/** Blocked-in lettering on cloth: warm, never pure white. */
internal val ClothInk = Color(0xFFF1EADB)

/** The cloth a book is bound in, the same on every launch and every device. */
fun clothFor(title: String): Color {
    // String.hashCode is specified, so this does not change between runs.
    val index = Math.floorMod(title.trim().lowercase().hashCode(), ClothTones.size)
    return ClothTones[index]
}

/**
 * A book as an object: its cover at its true shape, on its cloth where the art does not
 * fill it, with progress as the fore-edge, a ribbon when it is a favourite and a gilt top
 * edge once it is finished. Width comes from [modifier]; height follows at 2:3.
 */
@Composable
fun BookCover(
    book: Book,
    modifier: Modifier = Modifier,
    foreEdgeThickness: Dp = 4.dp,
    showDecorations: Boolean = true,
) {
    CoverArt(book.title, book.author, book.coverPath, modifier) {
        if (showDecorations) {
            if (book.readingStatus == ReadingStatus.FINISHED) Gilt(Modifier.align(Alignment.TopCenter))
            if (book.isFavorite) {
                Ribbon(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = foreEdgeThickness + 8.dp)
                )
            }
            ForeEdge(
                progress = book.readingProgress,
                modifier = Modifier.align(Alignment.CenterEnd),
                thickness = foreEdgeThickness,
            )
        }
    }
}

/**
 * A cover on its cloth: [image] (a file or an address) when there is one and it loads,
 * else the title typeset by [GeneratedCover]. With [typesetWhileLoading] the typeset
 * cover also stands in until the image arrives, for images that come over the network.
 */
@Composable
fun CoverArt(
    title: String,
    author: String,
    image: Any?,
    modifier: Modifier = Modifier,
    typesetWhileLoading: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier
            .aspectRatio(COVER_ASPECT)
            .clip(RectangleShape)
            .background(clothFor(title))
    ) {
        var typeset by remember(image) { mutableStateOf(image == null || typesetWhileLoading) }
        if (typeset) GeneratedCover(title, author)
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onState = { state ->
                    typeset = state is AsyncImagePainter.State.Error ||
                        (typesetWhileLoading && state !is AsyncImagePainter.State.Success)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        content()
    }
}

/**
 * A cover typeset for a book that has none: the title in Literata on its cloth inside a
 * blind-stamped frame, the author at the foot. Every coverless book used to be the same
 * grey tile with an icon on it.
 */
@Composable
fun GeneratedCover(title: String, author: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = maxWidth
        val inset = width * 0.07f
        val titleSize = (width.value * 0.115f).coerceIn(7f, 26f).sp
        val authorSize = (width.value * 0.06f).coerceIn(5f, 12f).sp
        Box(
            Modifier
                .fillMaxSize()
                .padding(inset)
                .border(0.75.dp, ClothInk.copy(alpha = 0.45f))
                .padding(inset * 0.9f)
        ) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(0.6f))
                Text(
                    title,
                    style = Bindery.type.title.copy(
                        fontSize = titleSize,
                        lineHeight = titleSize * 1.12f,
                        letterSpacing = (-0.01).em,
                    ),
                    color = ClothInk,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    author.uppercase(),
                    style = Bindery.type.eyebrow.copy(fontSize = authorSize, lineHeight = authorSize * 1.3f),
                    color = ClothInk.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The favourite mark: a cloth ribbon hanging from the top edge, notched at its tail. It
 * replaces a heart that sat on the cover art with no scrim and vanished on light covers;
 * the hairline round it keeps it apart from any art.
 */
@Composable
private fun Ribbon(modifier: Modifier = Modifier) {
    val colors = Bindery.colors
    Canvas(
        modifier
            .size(width = 11.dp, height = 26.dp)
            .semantics { contentDescription = "Favourite" }
    ) {
        val notch = size.height * 0.22f
        val shape = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(size.width / 2, size.height - notch)
            lineTo(0f, size.height)
            close()
        }
        drawPath(shape, colors.bookcloth)
        drawPath(shape, colors.paper.copy(alpha = 0.7f), style = Stroke(width = 1.dp.toPx()))
    }
}

/** Gilt along the top edge: the book has been read to the end. */
@Composable
private fun Gilt(modifier: Modifier = Modifier) {
    val foil = Bindery.colors.foil
    Canvas(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .semantics { contentDescription = "Finished" }
    ) {
        drawRect(
            Brush.linearGradient(
                0f to foil,
                0.45f to lerp(foil, Color.White, 0.35f),
                1f to foil,
                start = Offset.Zero,
                end = Offset(size.width, 0f),
            )
        )
    }
}
