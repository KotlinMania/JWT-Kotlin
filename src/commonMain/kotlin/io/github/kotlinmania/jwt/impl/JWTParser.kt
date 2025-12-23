@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.kotlinmania.jwt.impl

import io.github.kotlinmania.jwt.exceptions.JWTDecodeException
import io.github.kotlinmania.jwt.interfaces.Claim
import io.github.kotlinmania.jwt.interfaces.Header
import io.github.kotlinmania.jwt.interfaces.JWTPartsParser
import io.github.kotlinmania.jwt.interfaces.Payload
import kotlin.time.Instant
import kotlinx.serialization.json.*
import kotlin.reflect.KClass

/**
 * This class helps in decoding the Header and Payload of the JWT using
 * kotlinx.serialization.
 */
class JWTParser : JWTPartsParser {

    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }

    @Throws(JWTDecodeException::class)
    override fun parsePayload(json: String?): Payload {
        if (json == null) {
            throw decodeException(null)
        }

        try {
            val element = this@JWTParser.json.parseToJsonElement(json)
            if (element !is JsonObject) throw decodeException(json)
            return PayloadImpl(element)
        } catch (e: Exception) {
            throw decodeException(json, e)
        }
    }

    @Throws(JWTDecodeException::class)
    override fun parseHeader(json: String?): Header {
        if (json == null) {
            throw decodeException(null)
        }

        try {
            val element = this@JWTParser.json.parseToJsonElement(json)
            if (element !is JsonObject) throw decodeException(json)
            return HeaderImpl(element)
        } catch (e: Exception) {
            throw decodeException(json, e)
        }
    }

    companion object {
        private fun decodeException(json: String? = null, cause: Throwable? = null): JWTDecodeException {
            return JWTDecodeException("The string '$json' doesn't have a valid JSON format.", cause)
        }
    }
}

internal class HeaderImpl(private val tree: JsonObject) : Header {
    override val algorithm: String? get() = tree["alg"]?.jsonPrimitive?.contentOrNull
    override val type: String? get() = tree["typ"]?.jsonPrimitive?.contentOrNull
    override val contentType: String? get() = tree["cty"]?.jsonPrimitive?.contentOrNull
    override val keyId: String? get() = tree["kid"]?.jsonPrimitive?.contentOrNull

    override fun getHeaderClaim(name: String): Claim {
        return JsonClaim(tree[name])
    }
}

internal class PayloadImpl(private val tree: JsonObject) : Payload {
    override val issuer: String? get() = tree["iss"]?.jsonPrimitive?.contentOrNull
    override val subject: String? get() = tree["sub"]?.jsonPrimitive?.contentOrNull
    override val audience: List<String>? get() {
        val aud = tree["aud"]
        return when (aud) {
            is JsonArray -> aud.mapNotNull { it.jsonPrimitive.contentOrNull }
            is JsonPrimitive -> aud.contentOrNull?.let { listOf(it) }
            else -> null
        }
    }
    override val expiresAt: Instant? get() = tree["exp"]?.jsonPrimitive?.longOrNull?.let { Instant.fromEpochMilliseconds(it * 1000) }
    override val notBefore: Instant? get() = tree["nbf"]?.jsonPrimitive?.longOrNull?.let { Instant.fromEpochMilliseconds(it * 1000) }
    override val issuedAt: Instant? get() = tree["iat"]?.jsonPrimitive?.longOrNull?.let { Instant.fromEpochMilliseconds(it * 1000) }
    override val id: String? get() = tree["jti"]?.jsonPrimitive?.contentOrNull

    override fun getClaim(name: String): Claim {
        return JsonClaim(tree[name])
    }

    override val claims: Map<String, Claim>
        get() = tree.mapValues { JsonClaim(it.value) }
}

internal class JsonClaim(private val element: JsonElement?) : Claim {
    override fun asBoolean(): Boolean? = element?.jsonPrimitive?.booleanOrNull
    override fun asInt(): Int? = element?.jsonPrimitive?.intOrNull
    override fun asLong(): Long? = element?.jsonPrimitive?.longOrNull
    override fun asDouble(): Double? = element?.jsonPrimitive?.doubleOrNull
    override fun asString(): String? = element?.jsonPrimitive?.contentOrNull
    override fun asDate(): Instant? = element?.jsonPrimitive?.longOrNull?.let { Instant.fromEpochMilliseconds(it * 1000) }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> asList(clazz: KClass<T>): List<T>? {
        if (element !is JsonArray) return null

        return try {
            element.mapNotNull { 
                when (clazz) {
                    String::class -> it.jsonPrimitive.contentOrNull as T?
                    Int::class -> it.jsonPrimitive.intOrNull as T?
                    Long::class -> it.jsonPrimitive.longOrNull as T?
                    Boolean::class -> it.jsonPrimitive.booleanOrNull as T?
                    else -> null
                }
            }
        } catch (e: Exception) {
            throw JWTDecodeException("Couldn't map the claim's array contents to ${clazz.simpleName}", e)
        }
    }

    override fun asMap(): Map<String, Any>? {
        if (element !is JsonObject) return null
        // Recursive conversion not fully implemented for deep objects in this simple port
        // This is a simplification.
        return try {
            element.mapValues { entry -> 
                entry.value.jsonPrimitive.contentOrNull ?: entry.value.toString() 
            }
        } catch (e: Exception) {
            throw JWTDecodeException("Couldn't map the claim's object contents to Map", e)
        }
    }

    override fun isNull(): Boolean = element == null || element is JsonNull
}