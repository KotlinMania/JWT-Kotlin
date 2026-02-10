package io.github.kotlinmania.jwt.exceptions

/**
 * The exception that is thrown when any part of the token contained an invalid JWT or JSON format.
 */
class JWTDecodeException(message: String?, cause: Throwable? = null) : JWTVerificationException(message, cause)