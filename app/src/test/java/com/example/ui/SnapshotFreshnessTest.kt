package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotFreshnessTest {
    @Test
    fun aFreshNetworkResponseStatesItsAge() {
        // The production gap this closes: before, a successful network response
        // rendered no age at all, so a backend cache hit up to six hours old
        // looked identical to a snapshot generated a second ago.
        assertEquals(
            "Updated Sep 2, 9:11 PM",
            snapshotFreshnessText(
                fromCache = false,
                stale = false,
                formattedUpdatedAt = "Sep 2, 9:11 PM",
            ),
        )
    }

    @Test
    fun aRoomCachedSnapshotKeepsItsExistingWording() {
        assertEquals(
            "Showing saved data from Sep 2, 9:11 PM",
            snapshotFreshnessText(
                fromCache = true,
                stale = false,
                formattedUpdatedAt = "Sep 2, 9:11 PM",
            ),
        )
    }

    @Test
    fun aStaleSnapshotKeepsItsExistingWording() {
        assertEquals(
            "Showing saved data from Sep 2, 9:11 PM",
            snapshotFreshnessText(
                fromCache = false,
                stale = true,
                formattedUpdatedAt = "Sep 2, 9:11 PM",
            ),
        )
    }

    @Test
    fun savedDataWithNoRecordedTimeStillAnnouncesItself() {
        assertEquals(
            "Showing saved data",
            snapshotFreshnessText(fromCache = true, stale = false, formattedUpdatedAt = null),
        )
    }

    @Test
    fun aFreshResponseWithNoRecordedTimeSaysNothing() {
        assertNull(
            snapshotFreshnessText(fromCache = false, stale = false, formattedUpdatedAt = null),
        )
    }
}
