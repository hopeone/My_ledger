package com.hopeone.myledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerUpdatesTest {
    @Test
    fun usesExplicitVersionCodeIndependentlyOfTheTag() {
        assertEquals(42L, releaseVersionCode("v2.0", "새 기능\r\nversionCode: 42\r\n"))
        assertEquals(3L, releaseVersionCode("v1.0.3", "첨부 사진 기능"))
    }

    @Test
    fun rejectsMissingInvalidOrConflictingVersionCodes() {
        assertNull(releaseVersionCode("v2.0.0", "metadata missing"))
        assertNull(releaseVersionCode("v2.0.0", "versionCode: 0"))
        assertNull(releaseVersionCode("v2.0.0", "versionCode: 9\nversionCode: 10"))
        assertNull(releaseVersionCode("v2.0.0", "versionCode: 9223372036854775808"))
    }
}
