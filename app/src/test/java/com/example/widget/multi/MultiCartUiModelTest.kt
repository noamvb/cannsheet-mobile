package com.example.widget.multi

import com.example.data.Product
import com.example.data.ProductInteraction
import com.example.data.ProductTypeKey
import com.example.widget.PEN_WIDGET_PAYLOAD_VERSION
import com.example.widget.PenWidgetCommitPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiCartUiModelTest {
    @Test
    fun onlySelectablePensAreOffered() {
        val model = buildModel(
            products = listOf(
                pen("active"),
                pen("unopened", status = 2),
                Product("flower", "Flower", "F", 0),
                pen("finished", status = 1),
            ),
            interactions = emptyList(),
        )

        assertEquals(listOf("active", "unopened"), model.entries.map { it.productId })
    }

    @Test
    fun finishedCartsAreExcluded() {
        val model = buildModel(
            products = listOf(
                pen("finished", status = 1),
                pen("active"),
            ),
            interactions = listOf(
                ProductInteraction("finished", 900L, 1.0),
                ProductInteraction("active", 100L, 1.0),
            ),
        )

        assertEquals(listOf("active"), model.entries.map { it.productId })
    }

    @Test
    fun mostRecentlyLoggedCartsComeFirst() {
        val model = buildModel(
            products = listOf(pen("old"), pen("newest"), pen("middle")),
            interactions = listOf(
                ProductInteraction("old", 100L, 1.0),
                ProductInteraction("newest", 300L, 1.0),
                ProductInteraction("middle", 200L, 1.0),
            ),
        )

        assertEquals(
            listOf("newest", "middle", "old"),
            model.entries.map { it.productId },
        )
    }

    @Test
    fun atMostFourCartsAreShown() {
        val products = (1..6).map { pen("cart-$it") }
        val model = buildModel(
            products = products,
            interactions = products.mapIndexed { index, product ->
                ProductInteraction(product.id, index.toLong(), 1.0)
            },
        )

        assertEquals(4, model.entries.size)
        assertEquals(
            listOf("cart-6", "cart-5", "cart-4", "cart-3"),
            model.entries.map { it.productId },
        )
    }

    @Test
    fun remainingCartsBecomeTheOverflowCount() {
        val products = (1..6).map { pen("cart-$it") }
        val model = buildModel(products = products)

        assertEquals(2, model.overflowCount)
    }

    @Test
    fun eachEntryUsesItsOwnTypeRateAndFirstPreset() {
        val model = buildMultiCartUiModel(
            products = listOf(pen("first", type = "p"), pen("second", type = " P ")),
            interactions = listOf(
                ProductInteraction("first", 200L, 1.0),
                ProductInteraction("second", 100L, 1.0),
            ),
            globalPresets = listOf(0.5),
            presetOverrides = mapOf(ProductTypeKey("P") to listOf(1.5, 2.0)),
            secondsPerUseOverrides = mapOf(ProductTypeKey("P") to 8.0),
            pending = null,
        )

        assertEquals(listOf(12, 12), model.entries.map { it.seconds })
        assertEquals(listOf(8.0, 8.0), model.entries.map { it.secondsPerUse })
    }

    @Test
    fun pendingCommitSuppressesTheGrid() {
        val pending = pendingPayload()
        val model = buildModel(
            products = listOf(pen("active")),
            pending = pending,
        )

        assertEquals(pending, model.pending)
        assertEquals(listOf("active"), model.entries.map { it.productId })
    }

    private fun buildModel(
        products: List<Product>,
        interactions: List<ProductInteraction> = products.mapIndexed { index, product ->
            ProductInteraction(product.id, index.toLong(), 1.0)
        },
        pending: PenWidgetCommitPayload? = null,
    ) = buildMultiCartUiModel(
        products = products,
        interactions = interactions,
        globalPresets = listOf(0.5, 1.0, 2.0),
        presetOverrides = emptyMap(),
        secondsPerUseOverrides = mapOf(ProductTypeKey("P") to 10.0),
        pending = pending,
    )

    private fun pen(
        id: String,
        type: String = "p",
        status: Int = 0,
    ) = Product(id, "Pen $id", type, status)

    private fun pendingPayload() = PenWidgetCommitPayload(
        version = PEN_WIDGET_PAYLOAD_VERSION,
        commitId = "commit-1",
        eventId = "event-1",
        submittedAtEpochMillis = 1_000L,
        commitAtEpochMillis = 7_500L,
        productId = "active",
        productUuid = null,
        seconds = 30,
        secondsPerUse = 10.0,
        uses = 3.0,
        date = "2026-08-20",
        time = "12:00",
    )
}
