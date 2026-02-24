package com.rjnr.pocketnode.ui.screens.dao.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
            .height(8.dp)
    ) {
        val cornerRadius = CornerRadius(4.dp.toPx())

        // Track
        drawRoundRect(
            color = TrackColor,
            cornerRadius = cornerRadius,
            size = size
        )

        // Progress
        if (progress > 0f) {
            drawRoundRect(
                color = progressColor,
                cornerRadius = cornerRadius,
                size = Size(size.width * progress.coerceIn(0f, 1f), size.height)
            )
        }
    }
}
