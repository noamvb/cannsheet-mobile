package com.example.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueAlertCheckWorkerTest {
    @Test
    fun thresholdWorkerRunsOnlyAlertReconciliationAndSucceeds() = runBlocking {
        val runtime = FakeRuntime()

        val result = worker(runtime).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, runtime.calls)
        assertEquals(1, runtime.scheduleCalls)
    }

    @Test
    fun transientAlertFailureRetriesWithoutTouchingSyncWork() = runBlocking {
        val runtime = FakeRuntime(IOException("DataStore unavailable"))

        val result = worker(runtime).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(1, runtime.calls)
    }

    @Test
    fun cancellationStillCancelsTheThresholdWorker() = runBlocking {
        val runtime = FakeRuntime(CancellationException("stop"))

        try {
            worker(runtime).doWork()
            fail("CancellationException should escape")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    private fun worker(runtime: QueueAlertCheckRuntime): QueueAlertCheckWorker =
        TestListenableWorkerBuilder<QueueAlertCheckWorker>(applicationContext)
            .setWorkerFactory(InjectedFactory(runtime))
            .build()

    private class InjectedFactory(
        private val runtime: QueueAlertCheckRuntime,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = if (workerClassName == QueueAlertCheckWorker::class.java.name) {
            QueueAlertCheckWorker(appContext, workerParameters, runtime)
        } else {
            null
        }
    }

    private class FakeRuntime(
        private val error: Throwable? = null,
    ) : QueueAlertCheckRuntime {
        var calls = 0
        var scheduleCalls = 0

        override suspend fun reconcileQueueAlert() {
            calls += 1
            error?.let { throw it }
        }

        override suspend fun reconcileSchedule() {
            scheduleCalls += 1
        }
    }

    private companion object {
        val applicationContext: Context
            get() = ApplicationProvider.getApplicationContext()
    }
}
