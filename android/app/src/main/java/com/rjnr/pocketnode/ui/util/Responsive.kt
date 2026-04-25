package com.rjnr.pocketnode.ui.util

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Phone form-factor awareness for Compose UI.
 *
 * Pocket Node's primary market is budget Android handsets in Nigeria — 5.0"–5.5"
 * screens are common, and large phones (6.7"+) and folded inner displays show up
 * too. Surfaces that hard-code `dp` values either truncate on small screens or
 * stretch awkwardly on large ones. The helpers below let screens branch on
 * width class without each one resolving the WindowSizeClass independently.
 *
 * Compact  : <600.dp (typical phones, including small)
 * Medium   : 600.dp <= w < 840.dp (large phones, small foldables, portrait tablets)
 * Expanded : >=840.dp (tablets, foldables unfolded, landscape large phones)
 */
val LocalWindowSizeClass = staticCompositionLocalOf<WindowSizeClass?> { null }

/**
 * True for typical phones (anything under 600.dp wide). Use to scope down
 * paddings and grid cell sizes so things don't crowd the edges.
 */
@Composable
@ReadOnlyComposable
fun isCompactWidth(): Boolean {
    val sizeClass = LocalWindowSizeClass.current ?: return true
    return sizeClass.widthSizeClass == WindowWidthSizeClass.Compact
}

/**
 * True when there's room to display tablet-style layouts (>=840.dp).
 * Used to constrain max-width on bottom sheets and centred dialogs so they
 * don't span the full width of a foldable.
 */
@Composable
@ReadOnlyComposable
fun isExpandedWidth(): Boolean {
    val sizeClass = LocalWindowSizeClass.current ?: return false
    return sizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
}

/**
 * Returns one of three dp values keyed on the current width class.
 * Use for paddings, card widths, button heights — the common responsive knobs.
 */
@Composable
@ReadOnlyComposable
fun responsiveDp(compact: Dp, medium: Dp = compact, expanded: Dp = medium): Dp {
    val sizeClass = LocalWindowSizeClass.current ?: return compact
    return when (sizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> compact
        WindowWidthSizeClass.Medium -> medium
        else -> expanded
    }
}

/**
 * Standard horizontal screen padding: 12dp on small phones, 16dp mid, 24dp wide.
 * Centralised so we don't pepper screens with scattered `padding(16.dp)` calls.
 */
@Composable
@ReadOnlyComposable
fun screenHorizontalPadding(): Dp = responsiveDp(compact = 12.dp, medium = 16.dp, expanded = 24.dp)

/**
 * Max width that a centred surface (modal sheet, dialog body) should consume on
 * Expanded. Phones return [Dp.Unspecified] so callers can use it in
 * `.widthIn(max = ...)` without changing layout.
 */
@Composable
@ReadOnlyComposable
fun centredContentMaxWidth(): Dp = if (isExpandedWidth()) 560.dp else Dp.Unspecified
