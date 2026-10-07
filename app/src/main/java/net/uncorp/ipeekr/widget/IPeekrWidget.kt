package net.uncorp.ipeekr.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.background
import androidx.glance.appwidget.cornerRadius
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import net.uncorp.ipeekr.data.SnapshotStore
import net.uncorp.ipeekr.data.Template
import net.uncorp.ipeekr.data.Settings
import net.uncorp.ipeekr.data.SettingsStore
import net.uncorp.ipeekr.refresh.LiveService
import net.uncorp.ipeekr.refresh.Triggers

/** Renders the user's template (see [Template]) with the latest snapshot. Tap = refresh. */
class IPeekrWidget : GlanceAppWidget() {
    companion object {
        /** Text inset from the widget's edges: the same as IP Widget's (measured on a Pixel). The app's preview uses it too. */
        val PAD_H = 2.6.dp
        val PAD_V = 6.2.dp
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapFlow = SnapshotStore.flow(context)
        val settingsFlow = SettingsStore.flow(context)
        val fontScale = context.resources.configuration.fontScale
        provideContent {
            val snap by snapFlow.collectAsState(initial = null)
            val settings by settingsFlow.collectAsState(initial = Settings())
            GlanceTheme { Content(Template.render(settings.template, Template.values(snap)), settings, fontScale) }
        }
    }

    @Composable
    private fun Content(lines: List<String>, settings: Settings, fontScale: Float) {
        // System colours = Material You (follow wallpaper + dark mode); otherwise the user's ARGB picks
        val text = if (settings.systemColors) GlanceTheme.colors.onSurface else ColorProvider(Color(settings.textColor))
        val bg = if (settings.systemColors) GlanceTheme.colors.widgetBackground else ColorProvider(Color(settings.bgColor))
        val style = TextStyle(
            color = text,
            fontSize = settings.sizeSp.sp,
            fontWeight = if (settings.bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily(settings.font),
        )
        Column(
            GlanceModifier.fillMaxSize()
                // Always set (transparent = none): RemoteViews keeps the old background if it's omitted
                .background(bg).cornerRadius(16.dp) // corners: Android 12+
                .padding(horizontal = PAD_H, vertical = PAD_V)
                .clickable(actionRunCallback<RefreshAction>()),
        ) {
            // Long lines wrap. Glance has no lineHeight, so spacing is a gap between template lines of
            // size × (spacing − 1), in dp via fontScale: it grows with the font size.
            val gap = (settings.sizeSp * fontScale * (settings.lineSpacing - 1f)).coerceAtLeast(0f).dp
            // Glance drops a Column's children beyond 10, so lines go in nested columns of up to 10
            lines.withIndex().chunked(10).forEach { chunk ->
                Column {
                    chunk.forEach { (i, line) ->
                        Text(line, style = style, modifier = if (i > 0) GlanceModifier.padding(top = gap) else GlanceModifier)
                    }
                }
            }
        }
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Triggers.refreshNow(context, force = true)
        LiveService.applySaved(context) // a widget tap may restart it (e.g. after an app update)
    }
}

class IPeekrWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = IPeekrWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Triggers.install(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        Triggers.install(context)
        Triggers.refreshNow(context)
    }
}
