package com.example.widget

import com.example.nfc.PEN_NFC_SURFACE_ID
import org.junit.Assert.assertEquals
import org.junit.Test

class PenWidgetSurfaceRouterTest {
    @Test
    fun tileIdResolvesToTheTile() {
        assertEquals(
            PenWidgetSurface.TILE,
            resolveSurface(PEN_TILE_WIDGET_ID, intArrayOf()),
        )
    }

    @Test
    fun multiCartIdResolvesToMultiCart() {
        assertEquals(
            PenWidgetSurface.MULTI_CART,
            resolveSurface(7, intArrayOf(3, 7, 11)),
        )
    }

    @Test
    fun unknownIdResolvesToThePenWidget() {
        assertEquals(
            PenWidgetSurface.PEN,
            resolveSurface(42, intArrayOf(3, 7, 11)),
        )
    }

    @Test
    fun tileIdWinsEvenIfItAppearsInTheMultiCartIds() {
        assertEquals(
            PenWidgetSurface.TILE,
            resolveSurface(PEN_TILE_WIDGET_ID, intArrayOf(PEN_TILE_WIDGET_ID, 3, 7)),
        )
    }

    @Test
    fun nfcSurfaceIdIsNotTreatedAsARealWidget() {
        assertEquals(
            PenWidgetSurface.NFC,
            resolveSurface(PEN_NFC_SURFACE_ID, intArrayOf(PEN_NFC_SURFACE_ID, 3, 7)),
        )
    }
}
