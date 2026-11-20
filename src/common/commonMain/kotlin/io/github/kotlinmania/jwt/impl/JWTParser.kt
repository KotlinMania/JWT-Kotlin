@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.kotlinmania.jwt.impl

import io.github.kotlinmania.jwt.exceptions.JWTDecodeException
import io.github.kotlinmania.jwt.interfaces.Claim
import io.github.kotlinmania.jwt.interfaces.Header
import io.github.kotlinmania.jwt.interfaces.JWTPartsParser
import io.github.kotlinmania.jwt.interfaces.Payload
import io.github.kotlinmania.serdejson.JsonMap
import io.github.kotlinmania.serdejson.Value
import io.github.kotlinmania.serdejson.fromStr
import kotlin.reflect.KClass
import kotlin.reflect.safeCast
import kotlin.time.Instant

/**
 * This class helps in decoding the Header and Payload of the JWT using
 * `serde-json-kotlin`.
 */
class JWTParser : JWTPartsParser {
    @Throws(JWTDecodeException::class)
    override fun parsePayload(json: String?): Payload {
        if (json == null) {
            throw decodeException(null)
        }

        try {
            val element = fromStr(json).getOrThrow()
            if (element !is Value.Object) throw decodeException(json)
            return PayloadImpl(element.value)
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
            val element = fromStr(json).getOrThrow()
            if (element !is Value.Object) throw decodeException(json)
            return HeaderImpl(element.value)
        } catch (e: Exception) {
            throw decodeException(json, e)
        }
    }

    companion object {
        private fun decodeException(
            json: String? = null,
            cause: Throwable? = null,
        ): JWTDecodeException = JWTDecodeException("The string '$json' doesn't have a valid JSON format.", cause)
    }
}

internal class HeaderImpl(
    private val tree: JsonMap,
) : Header {
    override val algorithm: String? get() = tree["alg"]?.asStr()
    override val type: String? get() = tree["typ"]?.asStr()
    override val contentType: String? get() = tree["cty"]?.asStr()
    override val keyId: String? get() = tree["kid"]?.asStr()

    override fun getHeaderClaim(name: String): Claim = JsonClaim(tree[name])
}

internal class PayloadImpl(
    private val tree: JsonMap,
) : Payload {
    override val issuer: String? get() = tree["iss"]?.asStr()
    override val subject: String? get() = tree["sub"]?.asStr()
    override val audience: List<String>?
        get() =
            when (val aud = tree["aud"]) {
                is Value.Array -> aud.value.mapNotNull { it.asStr() }
                is Value.Str -> listOf(aud.value)
                else -> null
            }
    override val expiresAt: Instant? get() = tree["exp"]?.asI64()?.let { Instant.fromEpochMilliseconds(it * 1000) }
    override val notBefore: Instant? get() = tree["nbf"]?.asI64()?.let { Instant.fromEpochMilliseconds(it * 1000) }
    override val issuedAt: Instant? get() = tree["iat"]?.asI64()?.let { Instant.fromEpochMilliseconds(it * 1000) }
    override val id: String? get() = tree["jti"]?.asStr()

    override fun getClaim(name: String): Claim = JsonClaim(tree[name])

    override val claims: Map<String, Claim>
        get() {
            val result = LinkedHashMap<String, Claim>()
            for ((k, v) in tree) {
                result[k] = JsonClaim(v)
            }
            return result
        }
}

internal class JsonClaim(
    private val element: Value?,
) : Claim {
    override fun asBoolean(): Boolean? = element?.asBool()

    override fun asInt(): Int? = element?.asI64()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    override fun asLong(): Long? = element?.asI64()

    override fun asDouble(): Double? = element?.asF64()

    override fun asString(): String? = element?.asStr()

    override fun asDate(): Instant? = element?.asI64()?.let { Instant.fromEpochMilliseconds(it * 1000) }

    override fun <T : Any> asList(clazz: KClass<T>): List<T>? {
        if (element !is Value.Array) return null

        return try {
            element.value.mapNotNull {
                clazz.safeCast(it.toClaimValue(clazz))
            }
        } catch (e: Exception) {
            throw JWTDecodeException("Couldn't map the claim's array contents to ${clazz.simpleName}", e)
        }
    }

    override fun asMap(): Map<String, Any>? {
        if (element !is Value.Object) return null
        return try {
            val result = LinkedHashMap<String, Any>()
            for ((k, v) in element.value) {
                result[k] = v.toClaimValue(Any::class) ?: Value.Null
            }
            result
        } catch (e: Exception) {
            throw JWTDecodeException("Couldn't map the claim's object contents to Map", e)
        }
    }

    override fun isNull(): Boolean = element == null || element is Value.Null

    private fun Value.toClaimValue(clazz: KClass<*>): Any? =
        when (clazz) {
            String::class -> asStr()
            Int::class -> asI64()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            Long::class -> asI64()
            Double::class -> asF64()
            Boolean::class -> asBool()
            Any::class -> toAnyClaimValue()
            else -> null
        }

    private fun Value.toAnyClaimValue(): Any? =
        when (this) {
            is Value.Null -> null
            is Value.Bool -> value
            is Value.Str -> value
            is Value.Number -> {
                val longVal = asI64()
                if (longVal != null) {
                    if (longVal in Int.MIN_VALUE..Int.MAX_VALUE) longVal.toInt() else longVal
                } else {
                    asF64()
                }
            }
            is Value.Array -> value.mapNotNull { it.toAnyClaimValue() }
            is Value.Object -> {
                val result = LinkedHashMap<String, Any>()
                for ((k, v) in value) {
                    result[k] = v.toAnyClaimValue() ?: Value.Null
                }
                result
            }
        }
}
