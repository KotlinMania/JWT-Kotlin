package io.github.kotlinmania.jwt

import io.github.kotlinmania.base64.engine.generalpurpose.URL_SAFE_NO_PAD
import io.github.kotlinmania.jwt.exceptions.JWTDecodeException

internal object TokenUtils {
    /**
     * Splits the given token on the "." chars into a String array with 3 parts.
     *
     * @param token the string to split.
     * @return the array representing the 3 parts of the token.
     * @throws JWTDecodeException if the Token doesn't have 3 parts.
     */
    @Throws(JWTDecodeException::class)
    fun splitToken(token: String?): Array<String> {
        if (token == null) {
            throw JWTDecodeException("The token is null.")
        }

        val parts = token.split(".")

        if (parts.size != 3) {
            throw JWTDecodeException("The token was expected to have 3 parts, but got ${parts.size}.")
        }

        return parts.toTypedArray()
    }

    /**
     * Decodes a Base64 segment of a token. Both the URL-safe and the standard alphabet are
     * accepted, with or without trailing padding, and line breaks, tabs and spaces are ignored.
     *
     * @param segment the encoded token segment.
     * @return the decoded bytes, or null if the segment is not valid Base64.
     */
    fun decodeBase64Url(segment: String): ByteArray? {
        val normalized =
            buildString(segment.length) {
                for (c in segment) {
                    when (c) {
                        '+' -> append('-')
                        '/' -> append('_')
                        ' ', '\n', '\r', '\t' -> Unit
                        else -> append(c)
                    }
                }
            }.trimEnd('=')
        return URL_SAFE_NO_PAD.decode(normalized.encodeToByteArray()).getOrNull()
    }
}
