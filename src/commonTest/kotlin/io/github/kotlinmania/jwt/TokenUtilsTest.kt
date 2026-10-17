package io.github.kotlinmania.jwt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class TokenUtilsTest {
    private val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0x3E)

    @Test
    fun shouldDecodeUrlSafeWithoutPadding() {
        assertContentEquals(bytes, TokenUtils.decodeBase64Url("-_8-"))
        assertContentEquals("ab".encodeToByteArray(), TokenUtils.decodeBase64Url("YWI"))
    }

    @Test
    fun shouldDecodePaddedAndStandardAlphabet() {
        assertContentEquals(bytes, TokenUtils.decodeBase64Url("+/8+"))
        assertContentEquals("ab".encodeToByteArray(), TokenUtils.decodeBase64Url("YWI="))
    }

    @Test
    fun shouldReturnNullForInvalidInput() {
        assertNull(TokenUtils.decodeBase64Url("a"))
        assertNull(TokenUtils.decodeBase64Url("ab*c"))
    }
}
