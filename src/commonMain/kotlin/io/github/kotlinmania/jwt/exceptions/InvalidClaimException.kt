package io.github.kotlinmania.jwt.exceptions

/**
 * The exception that will be thrown while verifying Claims of a JWT.
 */
open class InvalidClaimException(message: String?) : JWTVerificationException(message)