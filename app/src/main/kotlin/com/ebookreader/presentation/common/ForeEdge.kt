package com.ebookreader.presentation.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A book's progress drawn as its fore-edge: the block of page edges, with the read
 * portion stained in bookcloth and every leaf ruled with a hairline, so it reads as a
 * property of the book rather than as a loading bar bolted onto it.
 *
 * Vertical (the default) fills from the bottom, like a level, for the right-hand edge of
 * a cover. Horizontal fills from the reading start edge, for a strip under the text.
 *
 * With [animate], a change in progress (a book marked finished, say) eases into place.
 * Compose scales that by the system animator duration, so with animations off it snaps.
 */
@Composable
fun ForeEdge(
    progress: Float,
    modifier: Modifier = Modifier,
    orientation: Orientation = Orientation.Vertical,
    thickness: Dp = 4.dp,
    animate: Boolean = true,
) {
    val target = progress.coerceIn(0f, 1f)
    val shown: State<Float> =
        if (animate) animateFloatAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
            label = "foreEdgeFill",
        )
        else rememberUpdatedState(target)
    val colors = Bindery.colors
    val vertical = orientation == Orientation.Vertical

    val sized = if (vertical) modifier.width(thickness).fillMaxHeight()
    else modifier.height(thickness).fillMaxWidth()

    // An unread book's edge is plain paper; announcing "0% read" on every one of them
    // would only add noise to a library read by TalkBack.
    val a11y = if (target > 0f) {
        val percent = (target * 100).roundToInt()
        Modifier
            .progressSemantics(target)
            .semantics { contentDescription = "$percent% read" }
    } else {
        Modifier.clearAndSetSemantics { }
    }

    Spacer(
        sized
            .then(a11y)
            .drawWithCache {
                val length = if (vertical) size.height else size.width
                val depth = if (vertical) size.width else size.height
                val hair = 1f
                // Horizontal edges grow from the start edge, which flips for RTL text.
                val fromEnd = !vertical && layoutDirection == LayoutDirection.Rtl

                /** The stretch from [from] to [to], measured from where filling starts. */
                fun span(from: Float, to: Float): Rect = when {
                    vertical -> Rect(0f, length - to, depth, length - from)
                    fromEnd -> Rect(length - to, 0f, length - from, depth)
                    else -> Rect(from, 0f, to, depth)
                }

                // The leaves are a repeating hard-edged stripe, one hairline per pitch,
                // so a block is one rect fill however many leaves it holds. The gradient
                // runs along the first pitch, in the direction the edge fills.
                val pitch = LeafPitch.toPx()
                val first = span(0f, pitch)
                val start = when {
                    vertical -> Offset(0f, first.bottom)
                    fromEnd -> Offset(first.right, 0f)
                    else -> Offset(first.left, 0f)
                }
                val end = when {
                    vertical -> Offset(0f, first.top)
                    fromEnd -> Offset(first.left, 0f)
                    else -> Offset(first.right, 0f)
                }
                val lineFrom = 1f - hair / pitch
                fun leaves(color: Color) = Brush.linearGradient(
                    0f to Color.Transparent,
                    lineFrom to Color.Transparent,
                    lineFrom to color,
                    1f to color,
                    start = start,
                    end = end,
                    tileMode = TileMode.Repeated,
                )
                // Rule-coloured on the unread block; through the stain, a thread of the
                // paper colour, so the leaves stay visible where the edge is coloured.
                val unreadLeaves = leaves(colors.rule)
                val readLeaves = leaves(colors.paper.copy(alpha = if (colors.isDark) 0.45f else 0.28f))
                val spine = colors.ink.copy(alpha = if (colors.isDark) 0.35f else 0.18f)
                val spineEnd = if (vertical) Offset(hair / 2, length) else Offset(length, hair / 2)

                onDrawBehind {
                    val filled = length * shown.value
                    val unread = span(filled, length)
                    val read = span(0f, filled)
                    drawRect(colors.page, unread.topLeft, unread.size)
                    drawRect(unreadLeaves, unread.topLeft, unread.size)
                    drawRect(colors.bookcloth, read.topLeft, read.size)
                    drawRect(readLeaves, read.topLeft, read.size)
                    // Where the block meets the cover or the text, so it stays a distinct
                    // object against any cover art.
                    drawLine(spine, Offset(hair / 2, hair / 2), spineEnd, hair)
                }
            }
    )
}

/** One hairline per leaf, spaced so the block reads as paper at arm's length. */
private val LeafPitch = 2.dp
