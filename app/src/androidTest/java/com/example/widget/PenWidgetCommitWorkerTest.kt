package com.example.widget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PenWidgetCommitWorkerTest {
    @Test
    fun missingPendingPayloadIsACleanNoOp() {
        val worker = TestListenableWorkerBuilder<PenWidgetCommitWorker>(
            ApplicationProvider.getApplicationContext(),
        ).setInputData(
            workDataOf(
                INPUT_APP_WIDGET_ID to 987_654,
                INPUT_COMMIT_ID to "missing-commit",
            ),
        ).build()

        assertEquals(ListenableWorker.Result.success(), kotlinx.coroutines.runBlocking { worker.doWork() })
    }
}
