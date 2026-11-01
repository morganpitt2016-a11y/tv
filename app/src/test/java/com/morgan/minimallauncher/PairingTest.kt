package com.morgan.minimallauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingTest {

    @Before fun start() { Pairing.cancel() }

    private fun wrongFor(code: String) = if (code == "0000") "1111" else "0000"

    @Test fun codeIsFourDigits() {
        val code = Pairing.request()
        assertEquals(4, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test fun askingAgainStraightAwayKeepsTheSameCode() {
        val first = Pairing.request()
        assertEquals(first, Pairing.request())
    }

    @Test fun theRightCodePairsOnlyOnce() {
        val code = Pairing.request()
        assertEquals(-1, Pairing.confirm(code))
        assertEquals(0, Pairing.confirm(code))          // already used
    }

    @Test fun wrongCodesCountDown() {
        val code = Pairing.request()
        assertEquals(4, Pairing.confirm(wrongFor(code)))
        assertEquals(3, Pairing.confirm(wrongFor(code)))
    }

    @Test fun fiveWrongTriesLockTheCode() {
        val code = Pairing.request()
        repeat(4) { Pairing.confirm(wrongFor(code)) }
        assertEquals(0, Pairing.confirm(wrongFor(code)))
        assertEquals(0, Pairing.confirm(code))           // even the right one, now
    }

    @Test fun noCodeMeansNoPairing() {
        assertEquals(0, Pairing.confirm("1234"))
    }
}
