package com.example.data.sync

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QueueAlertDeliveryCoordinatorTest {
    @Test
    fun unavailablePlatformInspectsWithoutClaimingAndClearsOnlyWhenInactive() = runBlocking {
        val presenter = FakePresenter(available = false)
        var claimCalls = 0
        val coordinator = coordinator(
            presenter = presenter,
            inspectedAlert = null,
            claim = {
                claimCalls += 1
                QueueAlertClaim(null, null)
            },
        )

        coordinator.reconcile()

        assertEquals(0, claimCalls)
        assertEquals(1, presenter.clearCalls)
    }

    @Test
    fun unavailablePlatformKeepsAnActiveExistingCard() = runBlocking {
        val presenter = FakePresenter(available = false)
        val coordinator = coordinator(
            presenter = presenter,
            inspectedAlert = alert(),
        )

        coordinator.reconcile()

        assertEquals(0, presenter.clearCalls)
        assertTrue(presenter.presented.isEmpty())
    }

    @Test
    fun inactiveClaimClearsTheStableNotification() = runBlocking {
        val presenter = FakePresenter()
        val coordinator = coordinator(
            presenter = presenter,
            claim = { QueueAlertClaim(null, null) },
        )

        coordinator.reconcile()

        assertEquals(1, presenter.clearCalls)
    }

    @Test
    fun repeatSuppressedActiveAlertIsNeitherPostedNorCleared() = runBlocking {
        val presenter = FakePresenter()
        val coordinator = coordinator(
            presenter = presenter,
            claim = { QueueAlertClaim(alert(), null) },
        )

        coordinator.reconcile()

        assertTrue(presenter.presented.isEmpty())
        assertEquals(0, presenter.clearCalls)
    }

    @Test
    fun acceptedPostCompletesTheExactClaim() = runBlocking {
        val presenter = FakePresenter(postAccepted = true)
        val completed = mutableListOf<String>()
        val released = mutableListOf<String>()
        val coordinator = coordinator(
            presenter = presenter,
            completed = completed,
            released = released,
        )

        coordinator.reconcile()

        assertEquals(listOf(alert()), presenter.presented)
        assertEquals(listOf(CLAIM_ID), completed)
        assertTrue(released.isEmpty())
        assertEquals(0, presenter.clearCalls)
    }

    @Test
    fun rejectedOrThrowingPostReleasesTheExactClaim() = runBlocking {
        val released = mutableListOf<String>()
        val rejected = FakePresenter(postAccepted = false)
        coordinator(presenter = rejected, released = released).reconcile()

        val throwing = FakePresenter(postError = SecurityException("permission changed"))
        coordinator(presenter = throwing, released = released).reconcile()

        assertEquals(listOf(CLAIM_ID, CLAIM_ID), released)
    }

    @Test
    fun invalidatedClaimClearsTheJustPostedCard() = runBlocking {
        val presenter = FakePresenter(postAccepted = true)
        val coordinator = coordinator(
            presenter = presenter,
            completeResult = false,
        )

        coordinator.reconcile()

        assertEquals(1, presenter.clearCalls)
    }

    @Test
    fun completionFailureLeavesAnAcceptedCardVisibleAndNeverReleases() = runBlocking {
        val presenter = FakePresenter(postAccepted = true)
        val released = mutableListOf<String>()
        val coordinator = coordinator(
            presenter = presenter,
            completeError = IOException("DataStore unavailable"),
            released = released,
        )

        coordinator.reconcile()

        assertEquals(0, presenter.clearCalls)
        assertTrue(released.isEmpty())
        assertEquals(listOf(alert()), presenter.presented)
    }

    @Test
    fun postingCancellationReleasesTheClaimAndEscapes() = runBlocking {
        val presenter = FakePresenter(postError = CancellationException("stop"))
        val released = mutableListOf<String>()
        val coordinator = coordinator(presenter = presenter, released = released)

        try {
            coordinator.reconcile()
            fail("CancellationException should escape")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertEquals(listOf(CLAIM_ID), released)
    }

    @Test
    fun cancellationDuringFailedPostReleaseRetriesCleanupAndEscapes() = runBlocking {
        val presenter = FakePresenter(postAccepted = false)
        var releaseCalls = 0
        val coordinator = QueueAlertDeliveryCoordinator(
            presenter = { presenter },
            inspectCurrentAlert = { alert() },
            evaluateAndClaim = { QueueAlertClaim(alert(), CLAIM_ID) },
            completeClaim = { true },
            releaseClaim = {
                releaseCalls += 1
                if (releaseCalls == 1) throw CancellationException("stop")
            },
        )

        try {
            coordinator.reconcile()
            fail("CancellationException should escape")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertEquals(2, releaseCalls)
    }

    @Test
    fun failedCancellationCleanupStillPropagatesTheOriginalCancellation() = runBlocking {
        val presenter = FakePresenter(postAccepted = false)
        val originalCancellation = CancellationException("stop")
        var releaseCalls = 0
        val coordinator = QueueAlertDeliveryCoordinator(
            presenter = { presenter },
            inspectCurrentAlert = { alert() },
            evaluateAndClaim = { QueueAlertClaim(alert(), CLAIM_ID) },
            completeClaim = { true },
            releaseClaim = {
                releaseCalls += 1
                if (releaseCalls == 1) throw originalCancellation
                throw IOException("cleanup also failed")
            },
        )

        try {
            coordinator.reconcile()
            fail("CancellationException should escape")
        } catch (actual: CancellationException) {
            assertTrue(actual === originalCancellation)
        }

        assertEquals(2, releaseCalls)
    }

    @Test
    fun transientFailureDuringFailedPostReleaseRetriesExactCleanup() = runBlocking {
        val presenter = FakePresenter(postAccepted = false)
        var releaseCalls = 0
        val coordinator = QueueAlertDeliveryCoordinator(
            presenter = { presenter },
            inspectCurrentAlert = { alert() },
            evaluateAndClaim = { QueueAlertClaim(alert(), CLAIM_ID) },
            completeClaim = { true },
            releaseClaim = {
                releaseCalls += 1
                if (releaseCalls == 1) throw IOException("temporary DataStore failure")
            },
        )

        coordinator.reconcile()

        assertEquals(2, releaseCalls)
    }

    @Test
    fun clearTriggerRechecksCurrentStateAndKeepsAnActiveCard() = runBlocking {
        val presenter = FakePresenter()
        var inspected: QueueAlert? = alert()
        val coordinator = QueueAlertDeliveryCoordinator(
            presenter = { presenter },
            inspectCurrentAlert = { inspected },
            evaluateAndClaim = { QueueAlertClaim(null, null) },
            completeClaim = { true },
            releaseClaim = {},
        )

        coordinator.clearIfCurrentAlertInactive()
        inspected = null
        coordinator.clearIfCurrentAlertInactive()

        assertEquals(1, presenter.clearCalls)
    }

    @Test
    fun concurrentDeliveriesCannotClaimOrPostOutOfOrder() = runBlocking {
        val firstPostStarted = CountDownLatch(1)
        val releaseFirstPost = CountDownLatch(1)
        val claims = AtomicInteger()
        val activePosts = AtomicInteger()
        val maximumActivePosts = AtomicInteger()
        val presenter = object : QueueAlertPresenter {
            override fun canPresent(): Boolean = true

            override fun tryPresent(alert: QueueAlert): Boolean {
                val active = activePosts.incrementAndGet()
                maximumActivePosts.updateAndGet { previous -> maxOf(previous, active) }
                if (firstPostStarted.count == 1L) {
                    firstPostStarted.countDown()
                    assertTrue(releaseFirstPost.await(2, TimeUnit.SECONDS))
                }
                activePosts.decrementAndGet()
                return true
            }

            override fun clear() = Unit
        }
        val coordinator = QueueAlertDeliveryCoordinator(
            presenter = { presenter },
            inspectCurrentAlert = { alert() },
            evaluateAndClaim = {
                QueueAlertClaim(alert(), "claim-${claims.incrementAndGet()}")
            },
            completeClaim = { true },
            releaseClaim = {},
        )

        val first = async(Dispatchers.Default) { coordinator.reconcile() }
        assertTrue(firstPostStarted.await(2, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) { coordinator.reconcile() }
        delay(50L)
        assertEquals(1, claims.get())
        releaseFirstPost.countDown()
        awaitAll(first, second)

        assertEquals(2, claims.get())
        assertEquals(1, maximumActivePosts.get())
    }

    private fun coordinator(
        presenter: FakePresenter,
        inspectedAlert: QueueAlert? = alert(),
        claim: suspend () -> QueueAlertClaim = {
            QueueAlertClaim(alert(), CLAIM_ID)
        },
        completeResult: Boolean = true,
        completeError: Throwable? = null,
        completed: MutableList<String> = mutableListOf(),
        released: MutableList<String> = mutableListOf(),
    ) = QueueAlertDeliveryCoordinator(
        presenter = { presenter },
        inspectCurrentAlert = { inspectedAlert },
        evaluateAndClaim = claim,
        completeClaim = { claimId ->
            completed += claimId
            completeError?.let { throw it }
            completeResult
        },
        releaseClaim = { claimId -> released += claimId },
    )

    private class FakePresenter(
        private val available: Boolean = true,
        private val postAccepted: Boolean = true,
        private val postError: Throwable? = null,
    ) : QueueAlertPresenter {
        val presented = mutableListOf<QueueAlert>()
        var clearCalls = 0

        override fun canPresent(): Boolean = available

        override fun tryPresent(alert: QueueAlert): Boolean {
            presented += alert
            postError?.let { throw it }
            return postAccepted
        }

        override fun clear() {
            clearCalls += 1
        }
    }

    private companion object {
        const val CLAIM_ID = "claim-1"

        fun alert() = QueueAlert(
            reason = QueueAlertReason.STUCK_QUEUE,
            pendingActionCount = 2,
            queueAgeMillis = QUEUE_STUCK_THRESHOLD_MILLIS,
        )
    }
}
