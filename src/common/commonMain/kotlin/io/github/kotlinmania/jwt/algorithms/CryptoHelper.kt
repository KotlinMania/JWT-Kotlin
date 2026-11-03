package io.github.kotlinmania.jwt.algorithms

import io.github.kotlinmania.constanttimeeq.constantTimeEq
import io.github.kotlinmania.hmac.Hmac
import io.github.kotlinmania.hmac.registerHasher
import io.github.kotlinmania.sha2.Sha256
import io.github.kotlinmania.sha2.Sha384
import io.github.kotlinmania.sha2.Sha512

/**
 * Class used to perform the signature hash calculations.
 *
 *
 * This class is thread-safe.
 */
internal class CryptoHelper {
    /**
     * Verify signature for JWT header and payload.
     *
     * @param algorithm      algorithm name.
     * @param secretBytes    algorithm secret.
     * @param header         JWT header.
     * @param payload        JWT payload.
     * @param signatureBytes JWT signature.
     * @return true if signature is valid.
     */
    fun verifySignatureFor(
        algorithm: String?,
        secretBytes: ByteArray,
        header: String,
        payload: String,
        signatureBytes: ByteArray,
    ): Boolean =
        verifySignatureFor(
            algorithm,
            secretBytes,
            header.encodeToByteArray(),
            payload.encodeToByteArray(),
            signatureBytes,
        )

    /**
     * Verify signature for JWT header and payload.
     *
     * @param algorithm      algorithm name.
     * @param secretBytes    algorithm secret.
     * @param headerBytes    JWT header.
     * @param payloadBytes   JWT payload.
     * @param signatureBytes JWT signature.
     * @return true if signature is valid.
     */
    fun verifySignatureFor(
        algorithm: String?,
        secretBytes: ByteArray,
        headerBytes: ByteArray,
        payloadBytes: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean {
        val calculatedSignature = createSignatureFor(algorithm, secretBytes, headerBytes, payloadBytes)
        return constantTimeEq(calculatedSignature, signatureBytes)
    }

    /**
     * Create signature for JWT header and payload.
     *
     * @param algorithm    algorithm name.
     * @param secretBytes  algorithm secret.
     * @param headerBytes  JWT header.
     * @param payloadBytes JWT payload.
     * @return the signature bytes.
     */
    fun createSignatureFor(
        algorithm: String?,
        secretBytes: ByteArray,
        headerBytes: ByteArray,
        payloadBytes: ByteArray,
    ): ByteArray {
        val content = headerBytes + JWT_PART_SEPARATOR + payloadBytes
        return createSignatureFor(algorithm, secretBytes, content)
    }

    /**
     * Create signature.
     * To get the correct JWT Signature, ensure the content is in the format {HEADER}.{PAYLOAD}
     *
     * @param algorithm    algorithm name.
     * @param secretBytes  algorithm secret.
     * @param contentBytes the content to be signed.
     * @return the signature bytes.
     */
    fun createSignatureFor(algorithm: String?, secretBytes: ByteArray, contentBytes: ByteArray): ByteArray =
        when (algorithm) {
            "HmacSHA256" -> Sha2Hmac.sha256(secretBytes, contentBytes)
            "HmacSHA384" -> Sha2Hmac.sha384(secretBytes, contentBytes)
            "HmacSHA512" -> Sha2Hmac.sha512(secretBytes, contentBytes)
            else -> throw IllegalArgumentException("Unsupported algorithm: $algorithm")
        }

    companion object {
        private const val JWT_PART_SEPARATOR = 46.toByte()
    }
}

/**
 * HMAC over the SHA-2 family. The digests are registered with the HMAC engine once, when this
 * object is first used.
 */
private object Sha2Hmac {
    init {
        registerHasher(
            type = Sha256::class,
            blockSize = 64,
            outputSize = 32,
            algName = "Sha256",
            create = { Sha256() },
            update = { h, d -> h.update(d) },
            finalize = { h -> h.finalize() },
            reset = { h -> h.reset() },
            digest = { d -> Sha256.digest(d) },
        )
        registerHasher(
            type = Sha384::class,
            blockSize = 128,
            outputSize = 48,
            algName = "Sha384",
            create = { Sha384() },
            update = { h, d -> h.update(d) },
            finalize = { h -> h.finalize() },
            reset = { h -> h.reset() },
            digest = { d -> Sha384.digest(d) },
        )
        registerHasher(
            type = Sha512::class,
            blockSize = 128,
            outputSize = 64,
            algName = "Sha512",
            create = { Sha512() },
            update = { h, d -> h.update(d) },
            finalize = { h -> h.finalize() },
            reset = { h -> h.reset() },
            digest = { d -> Sha512.digest(d) },
        )
    }

    fun sha256(key: ByteArray, content: ByteArray): ByteArray = Hmac.new<Sha256>(key).chain(content).finalizeFixed()

    fun sha384(key: ByteArray, content: ByteArray): ByteArray = Hmac.new<Sha384>(key).chain(content).finalizeFixed()

    fun sha512(key: ByteArray, content: ByteArray): ByteArray = Hmac.new<Sha512>(key).chain(content).finalizeFixed()
}
