@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.kotlinmania.jwt.impl

import io.github.kotlinmania.jwt.JWT
import io.github.kotlinmania.jwt.algorithms.Algorithm
import io.github.kotlinmania.jwt.exceptions.JWTDecodeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class JWTParserTest {
    @Test
    fun shouldThrowWithCauseWhenParsingInvalidPayload() {
        val parser = JWTParser()
        val invalidJson = "{ invalid }"

        val exception =
            assertFailsWith<JWTDecodeException> {
                parser.parsePayload(invalidJson)
            }

        assertNotNull(exception.cause, "Exception should have a cause")
        // serde-json-kotlin throws SerdeException when JSON parsing fails
    }

    @Test
    fun shouldThrowWithCauseWhenParsingNonObjectPayload() {
        val parser = JWTParser()
        val nonObjectJson = "[]"

        val exception =
            assertFailsWith<JWTDecodeException> {
                parser.parsePayload(nonObjectJson)
            }

        assertNotNull(exception.cause, "Exception should have a cause")
    }

    @Test
    fun shouldRoundTripAllClaimTypesViaSerdeJson() {
        val alg = Algorithm.hmac256("serde-secret")
        val issuedAt = Instant.fromEpochSeconds(1_700_000_000L)
        val token =
            JWT
                .create()
                .withHeader("""{"kid":"k1","ver":2,"active":true}""")
                .withIssuer("auth0")
                .withSubject("user-42")
                .withAudience("api", "admin")
                .withIssuedAt(issuedAt)
                .withClaim("flag", true)
                .withClaim("count", 42)
                .withClaim("big", 5_000_000_000L)
                .withClaim("ratio", 3.5)
                .withClaim("tags", listOf("alpha", "beta"))
                .withClaim("meta", mapOf("env" to "prod", "tier" to 1))
                .withNullClaim("empty")
                .sign(alg)

        val decoded = JWT.require(alg).build().verify(token)
        assertEquals("k1", decoded.keyId)
        assertEquals(2, decoded.getHeaderClaim("ver").asInt())
        assertEquals(true, decoded.getHeaderClaim("active").asBoolean())
        assertEquals("auth0", decoded.issuer)
        assertEquals("user-42", decoded.subject)
        assertEquals(listOf("api", "admin"), decoded.audience)
        assertEquals(issuedAt.epochSeconds, decoded.issuedAt?.epochSeconds)
        assertEquals(true, decoded.getClaim("flag").asBoolean())
        assertEquals(42, decoded.getClaim("count").asInt())
        assertEquals(5_000_000_000L, decoded.getClaim("big").asLong())
        assertEquals(3.5, decoded.getClaim("ratio").asDouble())
        assertEquals(listOf("alpha", "beta"), decoded.getClaim("tags").asList(String::class))
        val metaMap = decoded.getClaim("meta").asMap()
        assertNotNull(metaMap)
        assertEquals("prod", metaMap["env"])
        assertEquals(1, metaMap["tier"])
        assertTrue(decoded.getClaim("empty").isNull())
    }

    @Test
    fun shouldRejectNonObjectAndInvalidHeaderJson() {
        assertFailsWith<IllegalArgumentException> {
            JWT.create().withHeader("[1, 2, 3]")
        }
        assertFailsWith<IllegalArgumentException> {
            JWT.create().withHeader("{ bad json")
        }
    }
}
