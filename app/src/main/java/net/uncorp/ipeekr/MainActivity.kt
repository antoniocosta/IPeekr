package net.uncorp.ipeekr

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import android.net.Uri
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.lifecycleScope
import net.uncorp.ipeekr.data.Settings
import net.uncorp.ipeekr.data.SettingsStore
import net.uncorp.ipeekr.data.SnapshotStore
import net.uncorp.ipeekr.data.Template
import net.uncorp.ipeekr.refresh.LiveService
import net.uncorp.ipeekr.refresh.Triggers
import net.uncorp.ipeekr.widget.IPeekrWidget
import net.uncorp.ipeekr.widget.IPeekrWidgetReceiver
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.min
import androidx.compose.ui.text.PlatformTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


/**
 * Lays the content out at exactly [width] (so text wraps like on the home screen) and,
 * if that's wider than the space available, shrinks it uniformly to fit.
 */
private fun Modifier.scaledToWidth(width: Dp) = layout { measurable, constraints ->
    val w = width.roundToPx()
    val s = if (constraints.hasBoundedWidth) min(1f, constraints.maxWidth.toFloat() / w) else 1f
    val maxH = if (constraints.hasBoundedHeight) (constraints.maxHeight / s).toInt() else Constraints.Infinity
    val p = measurable.measure(Constraints(minWidth = w, maxWidth = w, maxHeight = maxH))
    layout((p.width * s).roundToInt(), (p.height * s).roundToInt()) {
        p.placeWithLayer(0, 0) { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0f) }
    }
}
private val Wallpaper = Color(0xFF111122)

/**
 * The whole app: a live preview of the widget, the template input and the style controls.
 * Also the widget's "settings" screen (launcher long-press), so it doubles as the configure activity.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opened by the launcher to (re)configure a widget: accept right away, settings apply live.
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        }
                Triggers.install(this)
        Triggers.refreshNow(this, force = true)
        lifecycleScope.launch { LiveService.applySaved(this@MainActivity) }
        setContent {
            AppTheme(this) {
                Scaffold(containerColor = Paper) { padding -> Screen(Modifier.padding(padding)) }
            }
        }
    }

    /** Portrait size (width, height in dp) of the first placed widget, or null if none is on the home screen. */
    private fun widgetSizeDp(): Pair<Int, Int>? {
        val mgr = AppWidgetManager.getInstance(this)
        val id = mgr.getAppWidgetIds(ComponentName(this, IPeekrWidgetReceiver::class.java)).firstOrNull() ?: return null
        val o = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return if (w > 0 && h > 0) w to h else null
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** The colours Glance's GlanceTheme uses for the widget: dynamic (Material You) on Android 12+. */
    @Composable
    private fun systemScheme(): ColorScheme {
        val dark = isSystemInDarkTheme()
        return when {
            Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
            Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
            dark -> darkColorScheme()
            else -> lightColorScheme()
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen(modifier: Modifier) {
        // Loaded once from the store; the template and the style are each saved (debounced) on change
        var field by remember { mutableStateOf<TextFieldValue?>(null) }
        var style by remember { mutableStateOf(Settings()) }
        var editing by remember { mutableStateOf<String?>(null) } // "text" / "bg" while the colour dialog is open
        var open by remember { mutableStateOf<String?>(Template.GROUPS.first().title) } // the one expanded section
        LaunchedEffect(Unit) {
            val s = SettingsStore.flow(this@MainActivity).first()
            style = s
            field = TextFieldValue(s.template, TextRange(s.template.length))
        }
        LaunchedEffect(field?.text) {
            val t = field?.text ?: return@LaunchedEffect
            delay(300)
            SettingsStore.saveTemplate(this@MainActivity, t)
            IPeekrWidget().updateAll(this@MainActivity)
        }
        LaunchedEffect(style) {
            if (field == null) return@LaunchedEffect // not loaded yet
            delay(300)
            SettingsStore.saveStyle(this@MainActivity, style)
            IPeekrWidget().updateAll(this@MainActivity)
        }
        val snap by remember { SnapshotStore.flow(this) }.collectAsState(initial = null)
        val typeface = remember(style.font, style.bold) {
            // BOLD style, like the widget's bold span: fakes bold for fonts without a bold file (e.g. Mono)
            FontFamily(Typeface.create(style.font, if (style.bold) Typeface.BOLD else Typeface.NORMAL))
        }
        val scheme = systemScheme()
        val textColor = if (style.systemColors) scheme.onSurface else Color(style.textColor)
        val bgColor = if (style.systemColors) scheme.surface else Color(style.bgColor)

        // Top: preview + input stay pinned, capped at half the visible height (keyboard excluded);
        // each scrolls inside itself. Bottom: tags and style controls scroll underneath.
        BoxWithConstraints(modifier.fillMaxSize().imePadding()) {
        val pinnedMax = maxHeight * 0.5f
        Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = pinnedMax).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PermissionBanner()
            AddWidgetButton()

            // Preview: the widget as it looks on a dark wallpaper. Tap = refresh.
            // Sized like the placed widget (if any), so text wraps and clips at the same spots.
            val lines = Template.render(field?.text ?: "", Template.values(snap))
            var widgetSize by remember { mutableStateOf(widgetSizeDp()) }
            LifecycleResumeEffect(Unit) { widgetSize = widgetSizeDp(); onPauseOrDispose { } }
            val textStyle = TextStyle(
                color = textColor, fontSize = style.sizeSp.sp, fontFamily = typeface,
                fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                // Match the widget's plain TextView, not Material's bodyLarge (letter spacing, line height)
                letterSpacing = 0.sp, lineHeight = TextUnit.Unspecified,
                platformStyle = PlatformTextStyle(includeFontPadding = true),
            )
            Box(
                Modifier.fillMaxWidth()
                    .weight(1f, fill = false)
                    .background(Wallpaper, RoundedCornerShape(16.dp))
                    .clickable { Triggers.refreshNow(this@MainActivity, force = true) }
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Clipped at the widget's height like the widget; scrolls if that's taller than the space here
                val size = widgetSize
                Column(
                    Modifier.verticalScroll(rememberScrollState())
                        .then(if (size != null) Modifier.scaledToWidth(size.first.dp).heightIn(max = size.second.dp).clipToBounds() else Modifier.fillMaxWidth())
                        .background(bgColor, RoundedCornerShape(16.dp))
                        .padding(horizontal = IPeekrWidget.PAD_H, vertical = IPeekrWidget.PAD_V),
                ) {
                    // Same layout as the widget: lines wrap, gap = size × (spacing − 1)
                    val gap = (style.sizeSp * (style.lineSpacing - 1f)).coerceAtLeast(0f).sp.toDp()
                    lines.forEachIndexed { i, line ->
                        Text(line, style = textStyle, modifier = if (i > 0) Modifier.padding(top = gap) else Modifier)
                    }
                }
            }

            field?.let { f ->
                OutlinedTextField(
                    value = f,
                    onValueChange = { field = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5, // scrolls inside beyond this
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Ink),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue, unfocusedBorderColor = Ink),
                    shape = RoundedCornerShape(12.dp),
                )
            }

        }
        HorizontalDivider(color = Line)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            // Accordion: tapping a section header opens it and closes the others
            val toggle = { title: String -> open = if (open == title) null else title }

            // Same instructions section as the other apps of the collection
            Section("How it works", open == "How it works", { toggle("How it works") }) {
                Step("1", "Add the widget from your home screen's widget list.")
                Step("2", "Type in the box above. Tap a tag to insert live data.")
                Step("3", "Lines with no data hide themselves.")
                Step("4", "It updates when the network changes, when you tap it, and every 15 minutes.")
            }

            // Opt-in foreground service: catches every network change (e.g. WiFi to WiFi) and unlocks
            Section("Updates", open == "Updates", { toggle("Updates") }) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Instant updates", color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = style.instant,
                        onCheckedChange = { style = style.copy(instant = it); LiveService.apply(this@MainActivity, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Blue),
                    )
                }
                Text(
                    "Updates on every network change and when you unlock. Needs a silent notification, which you can hide.",
                    color = Muted, fontSize = 13.sp,
                )
            }

            // Tap a tag to insert it at the cursor
            Template.GROUPS.forEach { group ->
                Section(group.title, open == group.title, { toggle(group.title) }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.tags.forEach { tag ->
                        Chip("<${tag.name}>", selected = false, mono = true) {
                            field = field?.let { f ->
                                val (text, cursor) = Template.insert(f.text, f.selection.min, f.selection.max, tag.name)
                                TextFieldValue(text, TextRange(cursor))
                            }
                        }
                    }
                }
                }
            }

            // Font: one chip per system font, drawn in that font; plus bold, size and line spacing
            Section("Text style", open == "Text style", { toggle("Text style") }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Settings.FONTS.forEach { (family, label) ->
                    Chip(label, selected = style.font == family, family = family) { style = style.copy(font = family) }
                }
                Chip("Bold", selected = style.bold, boldText = true) { style = style.copy(bold = !style.bold) }
            }

            // Size, and line spacing as a multiple of the size (so spacing follows the size)
            LabeledSlider("${style.sizeSp.roundToInt()} sp", style.sizeSp, Settings.MIN_SIZE_SP..Settings.MAX_SIZE_SP) {
                style = style.copy(sizeSp = it.roundToInt().toFloat())
            }
            LabeledSlider("×${"%.1f".format(style.lineSpacing)}", style.lineSpacing, Settings.MIN_SPACING..Settings.MAX_SPACING) {
                style = style.copy(lineSpacing = (it * 10).roundToInt() / 10f)
            }
            }

            // Colours
            Section("Colors", open == "Colors", { toggle("Colors") }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Use system colors", color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = style.systemColors,
                    onCheckedChange = { style = style.copy(systemColors = it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Blue),
                )
            }
            if (!style.systemColors) {
                ColorRow("Text", Color(style.textColor)) { editing = "text" }
                ColorRow("Background", Color(style.bgColor)) { editing = "bg" }
            }
            }
            ThemePicker()
            Footer()
        }
        }
        }

        editing?.let { which ->
            ColorDialog(
                title = if (which == "text") "Text color" else "Background color",
                initial = Color(if (which == "text") style.textColor else style.bgColor),
                onDismiss = { editing = null },
                onPick = { c ->
                    style = if (which == "text") style.copy(textColor = c.toArgb()) else style.copy(bgColor = c.toArgb())
                    editing = null
                },
            )
        }
    }

    /** "uncorp.net · v0.1.0" — same footer in every app of the collection; tap opens the site. */
    @Composable
    private fun Footer() {
        val version = remember { packageManager.getPackageInfo(packageName, 0).versionName }
        Text(
            "uncorp.net · v$version",
            modifier = Modifier.fillMaxWidth()
                .clickable { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://uncorp.net"))) }
                .padding(vertical = 20.dp),
            color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
        )
    }

    /** A numbered one-line step in "How it works". */
    @Composable
    private fun Step(number: String, text: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(number, color = Blue, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(14.dp))
            Text(text, color = Ink, fontSize = 14.sp)
        }
    }

    /** A drawn chevron (18dp) pointing down; rotates to point up while the section is open. */
    @Composable
    private fun Chevron(expanded: Boolean) {
        val angle by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
        val color = Blue
        Canvas(Modifier.size(18.dp).rotate(angle)) {
            val w = size.width
            val path = Path().apply {
                moveTo(w * 0.22f, w * 0.38f); lineTo(w * 0.5f, w * 0.66f); lineTo(w * 0.78f, w * 0.38f)
            }
            drawPath(path, color, style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    /** A collapsible section: header row (tap = toggle) and, when open, its content. */
    @Composable
    private fun Section(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Chevron(expanded)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            }
            HorizontalDivider(color = Line)
        }
    }

    /** "Text  [swatch]  #FFFFFF · 100%" — tap opens the colour dialog. */
    @Composable
    private fun ColorRow(label: String, color: Color, onClick: () -> Unit) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        ) {
            Text(label, color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(describe(color), color = Ink, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Swatch(color, selected = false, size = 32)
        }
    }

    /** A colour circle drawn over a checkerboard-ish base, so transparency is visible. */
    @Composable
    private fun Swatch(color: Color, selected: Boolean, size: Int = 36, onClick: (() -> Unit)? = null) {
        Box(
            Modifier.size(size.dp)
                .background(Color(0xFFDDDDDD), CircleShape)
                .border(if (selected) 3.dp else 1.dp, if (selected) Blue else Line, CircleShape)
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(if (selected) 3.dp else 1.dp)
                .background(color, CircleShape),
        )
    }

    /** Pick a colour (palette or hex) and its opacity. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ColorDialog(title: String, initial: Color, onDismiss: () -> Unit, onPick: (Color) -> Unit) {
        var rgb by remember { mutableIntStateOf(initial.copy(alpha = 1f).toArgb()) }
        var alpha by remember { mutableStateOf(initial.alpha) }
        var hex by remember { mutableStateOf(hexOf(rgb)) }
        val picked = Color(rgb).copy(alpha = alpha)
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Paper,
            title = { Text(title, color = Ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Result on the preview "wallpaper"
                    Box(
                        Modifier.fillMaxWidth().height(48.dp).background(Wallpaper, RoundedCornerShape(12.dp))
                            .padding(6.dp).background(picked, RoundedCornerShape(8.dp)),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PALETTE.forEach { c ->
                            Swatch(c, selected = c.toArgb() == rgb) { rgb = c.toArgb(); hex = hexOf(rgb) }
                        }
                    }
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { v ->
                            hex = v.uppercase().filter { it.isLetterOrDigit() }.take(6)
                            hex.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { rgb = (0xFF000000 or it).toInt() }
                        },
                        prefix = { Text("#", color = Ink) },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, color = Ink),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue, unfocusedBorderColor = Ink),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LabeledSlider("${(alpha * 100).roundToInt()}%", alpha, 0f..1f) { alpha = (it * 100).roundToInt() / 100f }
                }
            },
            confirmButton = { TextButton(onClick = { onPick(picked) }) { Text("OK", color = Blue) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Blue) } },
        )
    }

    @Composable
    private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.width(56.dp))
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue, inactiveTrackColor = Tint),
            )
        }
    }

    @Composable
    private fun TextUnit.toDp() = with(LocalDensity.current) { this@toDp.toDp() }

    /** A tappable pill. Selected = filled blue; otherwise pale blue. */
    @Composable
    private fun Chip(
        label: String,
        selected: Boolean,
        mono: Boolean = false,
        family: String? = null,
        boldText: Boolean = false,
        onClick: () -> Unit,
    ) {
        val ff = when {
            mono -> FontFamily.Monospace
            family != null -> remember(family) { FontFamily(Typeface.create(family, Typeface.NORMAL)) }
            else -> FontFamily.Default
        }
        Text(
            label,
            modifier = Modifier
                .background(if (selected) Blue else Tint, RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            color = if (selected) OnBlue else Blue,
            fontFamily = ff,
            fontWeight = if (boldText) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp,
        )
    }

    /** One line, only while something needed for the SSID / network type is missing. */
    @Composable
    private fun PermissionBanner() {
        var tick by remember { mutableIntStateOf(0) }
        LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
        val onResult = { _: Any -> tick++; Triggers.refreshNow(this, force = true) }
        val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions(), onResult)
        val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult)

        @Suppress("UNUSED_EXPRESSION") tick
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val bg = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val phone = granted(Manifest.permission.READ_PHONE_STATE)
        val locOn = getSystemService(LocationManager::class.java).isLocationEnabled

        val (label, action) = when {
            !fine || !phone -> "Allow location (WiFi name) and phone state (4G/5G)" to {
                multi.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_PHONE_STATE,
                ))
            }
            !bg -> "Set location to “Allow all the time” so the widget can see the WiFi name" to {
                single.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            !locOn -> "Turn on location services to see the WiFi name" to {
                startActivity(Intent(SysSettings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            else -> return
        }
        Text(
            label,
            modifier = Modifier.fillMaxWidth()
                .background(Blue, RoundedCornerShape(12.dp))
                .clickable { action() }
                .padding(12.dp),
            color = OnBlue,
            fontWeight = FontWeight.Bold,
        )
    }

    /**
     * "Add widget to home screen", only while none is placed: asks the launcher to pin it (Android 8+,
     * if the launcher supports it, like Pixel Launcher). The launcher shows its own sheet with an Add button.
     */
    @Composable
    private fun AddWidgetButton() {
        val mgr = remember { AppWidgetManager.getInstance(this) }
        val provider = remember { ComponentName(this, IPeekrWidgetReceiver::class.java) }
        var placed by remember { mutableStateOf(true) }
        LifecycleResumeEffect(Unit) { placed = mgr.getAppWidgetIds(provider).isNotEmpty(); onPauseOrDispose { } }
        if (placed || Build.VERSION.SDK_INT < 26 || !mgr.isRequestPinAppWidgetSupported) return
        Text(
            "Add widget to home screen",
            modifier = Modifier.fillMaxWidth()
                .background(Tint, RoundedCornerShape(12.dp))
                .clickable { mgr.requestPinAppWidget(provider, null, null) }
                .padding(12.dp),
            color = Blue,
            fontWeight = FontWeight.Bold,
        )
    }

    private companion object {
        val PALETTE = listOf(
            0xFFFFFFFF, 0xFFBDBDBD, 0xFF757575, 0xFF000000,
            0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
            0xFF00ACC1, 0xFF1E88E5, 0xFF0000EE, 0xFF8E24AA,
            0xFFD81B60, 0xFF6D4C41, 0xFF111122, 0xFF00E676,
        ).map { Color(it) }

        fun hexOf(argb: Int) = "%06X".format(argb and 0xFFFFFF)
        fun describe(c: Color) = "#${hexOf(c.toArgb())} · ${(c.alpha * 100).roundToInt()}%"
    }
}
