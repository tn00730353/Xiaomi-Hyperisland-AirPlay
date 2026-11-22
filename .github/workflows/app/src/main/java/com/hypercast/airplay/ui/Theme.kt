// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay.ui

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** 三種外觀：幻彩（HyperOS 風微彩色）、純白、純黑。三者都帶高斯模糊的液態玻璃。 */
enum class AppTheme(val label: String) { Iridescent("幻彩"), White("純白"), Black("純黑") }

class GlassPalette(
    val dark: Boolean,
    val text: Color,
    val text2: Color,
    val fillA: Color,
    val fillB: Color,
    val borderA: Color,
    val borderB: Color,
    val highlight: Color,
    val chip: Color,
    val track: Color,
    val sliderFill: List<Color>,
    val accent: Color,
    val podDot: Color,
)

fun paletteFor(t: AppTheme): GlassPalette = when (t) {
    AppTheme.Iridescent -> GlassPalette(
        dark = false,
        text = Color(0xFF1B1B20), text2 = Color(0x991B1B20),
        fillA = Color(0x99FFFFFF), fillB = Color(0x40FFFFFF),
        borderA = Color(0xF2FFFFFF), borderB = Color(0x40FFFFFF),
        highlight = Color(0x80FFFFFF),
        chip = Color(0x66FFFFFF), track = Color(0x24000000),
        sliderFill = listOf(Color(0xFFFF8FB8), Color(0xFFB79CFF), Color(0xFF7FB8FF)),
        accent = Color(0xFFFF4F86),
        podDot = Color(0x2A000000),
    )
    AppTheme.White -> GlassPalette(
        dark = false,
        text = Color(0xFF111114), text2 = Color(0x99111114),
        fillA = Color(0x14000000), fillB = Color(0x08000000),
        borderA = Color(0x2E000000), borderB = Color(0x0F000000),
        highlight = Color.Transparent,
        chip = Color(0x14000000), track = Color(0x1F000000),
        sliderFill = listOf(Color(0xFF48484A), Color(0xFF1C1C1E)),
        accent = Color(0xFFFA2D48),
        podDot = Color(0x2E000000),
    )
    AppTheme.Black -> GlassPalette(
        dark = true,
        text = Color.White, text2 = Color(0x99FFFFFF),
        fillA = Color(0x2EFFFFFF), fillB = Color(0x0FFFFFFF),
        borderA = Color(0x80FFFFFF), borderB = Color(0x14FFFFFF),
        highlight = Color(0x33FFFFFF),
        chip = Color(0x26FFFFFF), track = Color(0x38FFFFFF),
        sliderFill = listOf(Color(0xFFD1D1D6), Color(0xFFFFFFFF)),
        accent = Color(0xFFFA2D48),
        podDot = Color(0x40FFFFFF),
    )
}

val LocalAppTheme = compositionLocalOf { AppTheme.Iridescent }
val LocalPalette = compositionLocalOf { paletteFor(AppTheme.Iridescent) }

object ThemeStore {
    private const val FILE = "hypercast_prefs"
    private const val KEY = "theme"

    fun load(ctx: Context): AppTheme {
        val name = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        return AppTheme.values().firstOrNull { it.name == name } ?: AppTheme.Iridescent
    }

    fun save(ctx: Context, t: AppTheme) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, t.name).apply()
    }
}
