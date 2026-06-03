package me.bmax.upatch.util

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {

    @Test
    fun testUInt2String() {
        assertEquals("0.9.0", Version.uInt2String(0x000900u))
        assertEquals("1.0.0", Version.uInt2String(0x010000u))
        assertEquals("0.10.7", Version.uInt2String(0x000A07u))
        assertEquals("255.255.255", Version.uInt2String(0xFFFFFFu))
        assertEquals("0.0.0", Version.uInt2String(0x0u))
    }
}
