package com.example.data.sync

import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulerTest {
    @Test
    fun immediateRequestRequiresNetworkAndUsesThirtySecondExponentialBackoff() {
        val request = SyncScheduler.immediateRequest()

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(
            TimeUnit.SECONDS.toMillis(30),
            request.workSpec.backoffDelayDuration,
        )
    }

    @Test
    fun periodicRequestRunsEverySixHoursWithOneHourFlex() {
        val request = SyncScheduler.periodicRequest()

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(TimeUnit.HOURS.toMillis(6), request.workSpec.intervalDuration)
        assertEquals(TimeUnit.HOURS.toMillis(1), request.workSpec.flexDuration)
    }
}
