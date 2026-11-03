package io.github.kotlinmania.jwt.algorithms

import io.github.kotlinmania.jwt.JWT
import io.github.kotlinmania.jwt.exceptions.SignatureVerificationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HMACAlgorithmTest {
    private val rfc4231Key = "Jefe"
    private val rfc4231Data = "what do ya want for nothing?".encodeToByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun roundTrip(algorithm: Algorithm, other: Algorithm) {
        val token =
            JWT
                .create()
                .withIssuer("auth0")
                .withKeyId("key-1")
                .sign(algorithm)
        val decoded = JWT.require(algorithm).build().verify(token)
        assertEquals("auth0", decoded.issuer)
        assertEquals(algorithm.name, decoded.algorithm)
        assertEquals("key-1", decoded.keyId)
        assertFailsWith<SignatureVerificationException> {
            JWT.require(other).build().verify(token)
        }
    }

    @Test
    fun shouldSignWithHmacSha256Rfc4231Vector() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Algorithm.hmac256(rfc4231Key).sign(rfc4231Data).toHex(),
        )
    }

    @Test
    fun shouldSignWithHmacSha384Rfc4231Vector() {
        assertEquals(
            "af45d2e376484031617f78d2b58a6b1b9c7ef464f5a01b47e42ec3736322445e" +
                "8e2240ca5e69e2c78b3239ecfab21649",
            Algorithm.hmac384(rfc4231Key).sign(rfc4231Data).toHex(),
        )
    }

    @Test
    fun shouldSignWithHmacSha512Rfc4231Vector() {
        assertEquals(
            "164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea250554" +
                "9758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737",
            Algorithm.hmac512(rfc4231Key).sign(rfc4231Data).toHex(),
        )
    }

    @Test
    fun shouldVerifyKnownHs256Token() {
        val token =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
                ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ" +
                ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val decoded = JWT.require(Algorithm.hmac256("your-256-bit-secret")).build().verify(token)
        assertEquals("1234567890", decoded.subject)
        assertEquals("John Doe", decoded.getClaim("name").asString())
    }

    @Test
    fun shouldRoundTripHs256() {
        roundTrip(Algorithm.hmac256("secret"), Algorithm.hmac256("other"))
    }

    @Test
    fun shouldRoundTripHs384() {
        roundTrip(Algorithm.hmac384("secret"), Algorithm.hmac384("other"))
    }

    @Test
    fun shouldRoundTripHs512() {
        roundTrip(Algorithm.hmac512("secret"), Algorithm.hmac512("other"))
    }

    @Test
    fun shouldRoundTripHs384WithByteSecret() {
        val secret = ByteArray(48) { it.toByte() }
        roundTrip(Algorithm.hmac384(secret), Algorithm.hmac384(secret.copyOf().also { it[0] = 1 }))
    }
}
