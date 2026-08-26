package com.mrquentinet.matrixcontroller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardAddressTest {

    @Test
    fun `a bare host defaults to port 80`() {
        assertEquals(BoardAddress("192.168.1.50", 80), parseBoardAddress("192.168.1.50"))
    }

    @Test
    fun `an explicit port is honoured`() {
        assertEquals(BoardAddress("192.168.1.50", 8080), parseBoardAddress("192.168.1.50:8080"))
    }

    @Test
    fun `an http prefix and a trailing slash are stripped`() {
        assertEquals(BoardAddress("matrix.local", 80), parseBoardAddress("http://matrix.local/"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(BoardAddress("matrix.local", 81), parseBoardAddress("  matrix.local:81  "))
    }

    @Test
    fun `https is rejected because the firmware has no TLS`() {
        assertNull(parseBoardAddress("https://x"))
    }

    @Test
    fun `out-of-range and malformed ports are rejected`() {
        assertNull(parseBoardAddress("a:0"))
        assertNull(parseBoardAddress("a:70000"))
        assertNull(parseBoardAddress("a:"))
        assertNull(parseBoardAddress("a:http"))
    }

    @Test
    fun `whitespace, paths and empty input are rejected`() {
        assertNull(parseBoardAddress("a b"))
        assertNull(parseBoardAddress(""))
        assertNull(parseBoardAddress("   "))
        assertNull(parseBoardAddress("a/b"))
        assertNull(parseBoardAddress("http://"))
    }
}
