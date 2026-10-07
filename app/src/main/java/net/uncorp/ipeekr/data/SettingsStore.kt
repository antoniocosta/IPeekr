package net.uncorp.ipeekr.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settings by preferencesDataStore("settings")
private val TEMPLATE = stringPreferencesKey("template")
private val FONT = stringPreferencesKey("font")
private val SIZE = floatPreferencesKey("size")
private val BOLD = booleanPreferencesKey("bold")
private val SPACING = floatPreferencesKey("spacing")
private val SYSTEM_COLORS = booleanPreferencesKey("system_colors")
private val TEXT_COLOR = intPreferencesKey("text_color")
private val BG_COLOR = intPreferencesKey("bg_color")
private val INSTANT = booleanPreferencesKey("instant")

/** A system font family name (what `Typeface.create(name, …)` accepts) and its label in the app. */
data class Font(val family: String, val label: String)

/** Everything the user can configure: the template text and how it is drawn. */
data class Settings(
    val template: String = Template.DEFAULT,
    val font: String = FONTS.first().family,
    val sizeSp: Float = DEFAULT_SIZE_SP,
    val bold: Boolean = false,
    /** Line height as a multiple of the font size, so spacing follows the size automatically. */
    val lineSpacing: Float = DEFAULT_SPACING,
    /** true: Material You colours (text + background follow the wallpaper / dark mode). */
    val systemColors: Boolean = false,
    val textColor: Int = DEFAULT_TEXT_COLOR,     // ARGB, alpha = opacity
    val bgColor: Int = DEFAULT_BG_COLOR,         // ARGB; fully transparent = no background
    /** "Instant updates": a foreground service that sees every network change (see LiveService). */
    val instant: Boolean = false,
) {
    companion object {
        const val DEFAULT_SIZE_SP = 12f
        const val MIN_SIZE_SP = 8f
        const val MAX_SIZE_SP = 40f
        const val DEFAULT_TEXT_COLOR = 0xFFFFFFFF.toInt()  // white
        const val DEFAULT_BG_COLOR = 0x00000000            // none
        const val DEFAULT_SPACING = 1.2f
        const val MIN_SPACING = 1.0f
        const val MAX_SPACING = 2.5f

        /** Fonts that ship with every Android device, so the widget needs no font files. */
        val FONTS = listOf(
            Font("sans-serif", "Sans"),
            Font("sans-serif-condensed", "Condensed"),
            Font("sans-serif-light", "Light"),
            Font("sans-serif-black", "Black"),
            Font("serif", "Serif"),
            Font("monospace", "Mono"),
        )
    }
}

/** Persists [Settings]. Same DataStore as before, so an existing template is kept. */
object SettingsStore {
    fun flow(ctx: Context): Flow<Settings> =
        ctx.applicationContext.settings.data.map { p ->
            Settings(
                template = p[TEMPLATE]?.let(::migrate) ?: Template.DEFAULT,
                font = p[FONT]?.takeIf { f -> Settings.FONTS.any { it.family == f } } ?: Settings().font,
                sizeSp = (p[SIZE] ?: Settings.DEFAULT_SIZE_SP).coerceIn(Settings.MIN_SIZE_SP, Settings.MAX_SIZE_SP),
                bold = p[BOLD] ?: false,
                systemColors = p[SYSTEM_COLORS] ?: false,
                textColor = p[TEXT_COLOR] ?: Settings.DEFAULT_TEXT_COLOR,
                bgColor = p[BG_COLOR] ?: Settings.DEFAULT_BG_COLOR,
                instant = p[INSTANT] ?: false,
                lineSpacing = (p[SPACING] ?: Settings.DEFAULT_SPACING).coerceIn(Settings.MIN_SPACING, Settings.MAX_SPACING),
            )
        }

    suspend fun saveTemplate(ctx: Context, template: String) {
        ctx.applicationContext.settings.edit { it[TEMPLATE] = template }
    }

    /** Saves everything except the template. */
    suspend fun saveStyle(ctx: Context, s: Settings) {
        ctx.applicationContext.settings.edit {
            it[FONT] = s.font; it[SIZE] = s.sizeSp; it[BOLD] = s.bold; it[SPACING] = s.lineSpacing
            it[SYSTEM_COLORS] = s.systemColors; it[TEXT_COLOR] = s.textColor; it[BG_COLOR] = s.bgColor
            it[INSTANT] = s.instant
        }
    }

    /** Updates a template saved by an older build: drops <time> and ★, replaces old/duplicate tags. */
    private fun migrate(t: String): String = t.lines()
        .map { Template.migrate(it.replace("<time>", "").replace("★", "")).trimEnd() }
        .joinToString("\n")
        .let { if (it in Template.OLD_DEFAULTS) Template.DEFAULT else it }
}
