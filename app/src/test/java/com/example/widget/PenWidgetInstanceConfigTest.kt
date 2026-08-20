package com.example.widget

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.example.R
import com.example.data.Product
import com.example.domain.PenQuickLogState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PenWidgetInstanceConfigTest {
    @Test
    fun defaultConfigFollowsTheLoadedCart() = runBlocking {
        assertEquals(
            PenWidgetInstanceConfig.DEFAULT,
            repository().read(42),
        )
    }

    @Test
    fun blankPinnedProductIdIsTreatedAsAbsent() = runBlocking {
        val repository = repository()

        repository.write(
            42,
            PenWidgetInstanceConfig(pinnedProductId = "  \t  "),
        )

        assertNull(repository.read(42).pinnedProductId)
    }

    @Test
    fun stepOverrideOutsideTheAllowedRangeIsIgnored() = runBlocking {
        val repository = repository()

        listOf(-1, 0, MAX_SECONDS + 1, Int.MAX_VALUE).forEach { invalidStep ->
            repository.write(
                42,
                PenWidgetInstanceConfig(stepSecondsOverride = invalidStep),
            )

            assertNull(repository.read(42).stepSecondsOverride)
        }
    }

    @Test
    fun discreetModelHidesTheProductName() {
        val model = discreetModel()

        assertEquals(
            PenWidgetText.Resource(R.string.pen_widget_generic_cart),
            model.productName,
        )
    }

    @Test
    fun discreetModelHidesUseCounts() {
        val model = discreetModel()

        assertEquals(
            PenWidgetText.Resource(R.string.pen_widget_discreet_subtitle),
            model.subtitle,
        )
    }

    @Test
    fun discreetModelStillShowsTheQueuedConfirmation() {
        val model = discreetModel(lastQueuedAtMillis = 1_000L, nowMillis = 2_000L)

        assertEquals(
            PenWidgetText.Resource(R.string.pen_widget_queued),
            model.subtitle,
        )
    }

    private fun repository() = PenWidgetConfigRepository(InMemoryPreferencesDataStore())

    private fun discreetModel(
        lastQueuedAtMillis: Long? = null,
        nowMillis: Long = 0L,
    ): PenWidgetUiModel.Composing = buildPenWidgetUiModel(
        penState = loaded(),
        draft = PenWidgetDraft.Composing(30),
        lastQueuedAtMillis = lastQueuedAtMillis,
        nowMillis = nowMillis,
        discreet = true,
    ) as PenWidgetUiModel.Composing

    private fun loaded() = PenQuickLogState.Loaded(
        product = Product(
            id = "pen-1",
            name = "Private cart name",
            type = "P",
            status = 0,
            totalUses = 12.0,
        ),
        presetUses = listOf(0.5, 1.0, 2.0),
        secondsPerUse = 10.0,
        syncedUses = 12.0,
        pendingUses = 2.0,
    )

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
