package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.example.widget.multi.MultiCartUpdater
import com.example.widget.multi.MultiCartWidgetProvider
import com.example.nfc.PEN_NFC_SURFACE_ID

/**
 * Sends a commit-driven refresh to whichever surface owns [appWidgetId]. The
 * pen widget, the multi-cart widget, and the Quick Settings tile share one
 * per-id DataStore, so the commit coordinator needs one place that knows which
 * of them to redraw. Adding a surface means adding a branch here and nowhere
 * else.
 */
object PenWidgetSurfaceRouter {
    suspend fun refresh(context: Context, appWidgetId: Int) {
        if (appWidgetId < 0) return
        val appContext = context.applicationContext
        if (appWidgetId == PEN_NFC_SURFACE_ID) {
            // NFC has a dedicated result Activity, not an AppWidget surface. The durable
            // DataStore transition is already complete; startup/WorkManager still flushes it.
            return
        }
        if (appWidgetId == PEN_TILE_WIDGET_ID) {
            PenQuickTileService.requestRefresh(appContext)
            return
        }
        val multiCartIds = AppWidgetManager.getInstance(appContext)
            .getAppWidgetIds(ComponentName(appContext, MultiCartWidgetProvider::class.java))
        when (resolveSurface(appWidgetId, multiCartIds)) {
            PenWidgetSurface.TILE -> PenQuickTileService.requestRefresh(appContext)
            PenWidgetSurface.MULTI_CART -> MultiCartUpdater.update(appContext, appWidgetId)
            PenWidgetSurface.PEN -> PenWidgetUpdater.update(appContext, appWidgetId)
            PenWidgetSurface.NFC -> Unit
        }
    }
}

/** The surface that owns a given widget/tile id, as decided by [resolveSurface]. */
internal enum class PenWidgetSurface { TILE, MULTI_CART, PEN, NFC }

/**
 * Pure decision logic behind [PenWidgetSurfaceRouter.refresh], extracted because
 * `AppWidgetManager` cannot be faked in a JVM unit test. [multiCartIds] is the result of
 * querying the multi-cart provider's own ids. The tile id always wins even if it were present
 * in that set, because [PEN_TILE_WIDGET_ID] is a reserved pseudo id that AppWidgetManager never
 * allocates to a real widget.
 */
internal fun resolveSurface(appWidgetId: Int, multiCartIds: IntArray): PenWidgetSurface {
    if (appWidgetId == PEN_TILE_WIDGET_ID) return PenWidgetSurface.TILE
    if (appWidgetId == PEN_NFC_SURFACE_ID) return PenWidgetSurface.NFC
    if (multiCartIds.contains(appWidgetId)) return PenWidgetSurface.MULTI_CART
    return PenWidgetSurface.PEN
}
