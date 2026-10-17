package io.github.kotlinmania.jwt.impl

import io.github.kotlinmania.jwt.exceptions.JWTDecodeException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

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
        // kotlinx.serialization throws something like JsonDecodingException or similar
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
}
