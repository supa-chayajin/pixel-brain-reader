package cloud.wafflecommons.pixelbrainreader.data.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Google access the app asks for is read-only: both syncs only import (owner, 2026-11-10). */
class GoogleScopesTest {

    @Test
    fun `calendar and tasks are requested read-only, nothing else`() {
        assertEquals(
            listOf(
                "https://www.googleapis.com/auth/calendar.readonly",
                "https://www.googleapis.com/auth/tasks.readonly"
            ),
            GoogleScopes.READ_ONLY
        )
    }
}
