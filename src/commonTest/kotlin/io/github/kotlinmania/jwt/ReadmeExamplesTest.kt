package io.github.kotlinmania.jwt

import io.github.kotlinmania.jwt.algorithms.Algorithm
import io.github.kotlinmania.jwt.exceptions.JWTVerificationException
import io.github.kotlinmania.jwt.exceptions.TokenExpiredException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** The code samples in README.md, kept compiling and behaving as documented. */
class ReadmeExamplesTest {
    private val algorithm = Algorithm.hmac256("super-secret")

    private fun createToken(expiresIn: kotlin.time.Duration = 1.hours): String {
        val now = Clock.System.now()
        return JWT
            .create()
            .withIssuer("auth.example.com")
            .withSubject("user-42")
            .withAudience("api.example.com")
            .withIssuedAt(now)
            .withExpiresAt(now + expiresIn)
            .withClaim("role", "admin")
            .sign(algorithm)
    }

    @Test
    fun createThenVerify() {
        val token = createToken()
        val verifier =
            JWT
                .require(algorithm)
                .withIssuer("auth.example.com")
                .withAudience("api.example.com")
                .withClaim("role", "admin")
                .acceptLeeway(5)
                .build()
        val decoded = verifier.verify(token)
        assertEquals("user-42", decoded.subject)
    }

    @Test
    fun customClaimPredicate() {
        val token = createToken()
        val verifier =
            JWT
                .require(algorithm)
                .withClaim("role") { claim, _ -> claim.asString() in setOf("admin", "owner") }
                .build()
        assertEquals("user-42", verifier.verify(token).subject)
    }

    @Test
    fun decodeWithoutVerifying() {
        val decoded = JWT.decode(createToken())
        assertEquals("HS256", decoded.algorithm)
        assertEquals("admin", decoded.getClaim("role").asString())
        assertEquals("auth.example.com", decoded.issuer)
        assertEquals(listOf("api.example.com"), decoded.audience)
    }

    @Test
    fun wrongSecretFailsVerification() {
        val token = createToken()
        assertFailsWith<JWTVerificationException> {
            JWT.require(Algorithm.hmac256("another-secret")).build().verify(token)
        }
    }

    @Test
    fun expiredTokenFailsVerification() {
        val token = createToken(expiresIn = (-1).hours)
        assertFailsWith<TokenExpiredException> {
            JWT.require(algorithm).build().verify(token)
        }
    }
}
