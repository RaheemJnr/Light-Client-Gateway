package com.rjnr.pocketnode.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val WALLET_COLORS = listOf(
    Color(0xFF6366F1),  // Indigo
    Color(0xFFF59E0B),  // Amber
    Color(0xFF10B981),  // Emerald
    Color(0xFFEF4444),  // Red
    Color(0xFF0EA5E9),  // Sky
    Color(0xFF8B5CF6),  // Violet
    Color(0xFFEC4899),  // Pink
    Color(0xFF14B8A6),  // Teal
)

@Composable
fun WalletAvatar(
    name: String,
    colorIndex: Int,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    val color = WALLET_COLORS[colorIndex.coerceIn(0, WALLET_COLORS.lastIndex)]
    val initial = name.firstOrNull()?.uppercase() ?: "?"
    val fontSize = (size.value * 0.4f).sp

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize
        )
    }
}
