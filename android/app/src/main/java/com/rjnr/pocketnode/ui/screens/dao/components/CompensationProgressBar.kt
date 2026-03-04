package com.rjnr.pocketnode.ui.screens.dao.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.rjnr.pocketnode.data.gateway.models.CyclePhase

private val TrackColor = Color(0xFF252525)
private val NormalGreen = Color(0xFF1ED882)
private val SuggestedAmber = Color(0xFFF59E0B)
private val EndingRed = Color(0xFFEF4444)

@Composable
fun CompensationProgressBar(
    progress: Float,
    phase: CyclePhase,
    modifier: Modifier = Modifier
) {
    val progressColor = when (phase) {
        CyclePhase.NORMAL -> NormalGreen
        CyclePhase.SUGGESTED -> SuggestedAmber
        CyclePhase.ENDING -> EndingRed
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
    ) {
        val barHeight = 8.dp.toPx()
        val triangleHeight = 6.dp.toPx()
        val barTop = triangleHeight + 2.dp.toPx()
        val cornerRadius = CornerRadius(4.dp.toPx())

        // Track
        drawRoundRect(
            color = TrackColor,
            topLeft = Offset(0f, barTop),
            cornerRadius = cornerRadius,
            size = Size(size.width, barHeight)
        )

        // Progress
        val clampedProgress = progress.coerceIn(0f, 1f)
        if (clampedProgress > 0f) {
            drawRoundRect(
                color = progressColor,
                topLeft = Offset(0f, barTop),
                cornerRadius = cornerRadius,
                size = Size(size.width * clampedProgress, barHeight)
            )
        }

        // Triangle marker above the progress position
        if (clampedProgress > 0f) {
            val halfBase = 4.dp.toPx()
            val minX = halfBase
            val maxX = (size.width - halfBase).coerceAtLeast(minX)
            val markerX = (size.width * clampedProgress).coerceIn(minX, maxX)
            val path = Path().apply {
                moveTo(markerX, barTop)                        // tip (points down to bar)
                lineTo(markerX - halfBase, barTop - triangleHeight) // top-left
                lineTo(markerX + halfBase, barTop - triangleHeight) // top-right
                close()
            }
            drawPath(path, color = progressColor)
        }
    }
}
