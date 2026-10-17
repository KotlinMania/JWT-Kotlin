package io.github.kotlinmania.jwt.algorithms

import io.github.kotlinmania.jwt.exceptions.SignatureGenerationException
import io.github.kotlinmania.jwt.exceptions.SignatureVerificationException
import io.github.kotlinmania.jwt.interfaces.DecodedJWT
import io.github.kotlinmania.jwt.interfaces.ECDSAKeyProvider

internal class ECDSAAlgorithm(
    crypto: CryptoHelper,
    id: String?,
    algorithm: String?,
    keyProvider: ECDSAKeyProvider,
) : Algorithm(id ?: "ES256", algorithm ?: "ES256") {
    private val keyProvider: ECDSAKeyProvider
    private val crypto: CryptoHelper

    init {
        this.keyProvider = keyProvider
        this.crypto = crypto
    }

    constructor(id: String?, algorithm: String?, keyProvider: ECDSAKeyProvider) : this(
        CryptoHelper(),
        id,
        algorithm,
        keyProvider,
    )

    @Throws(SignatureVerificationException::class)
    override fun verify(jwt: DecodedJWT?): Unit = throw SignatureVerificationException(this)

    @Throws(SignatureGenerationException::class)
    override fun sign(headerBytes: ByteArray?, payloadBytes: ByteArray?): ByteArray = throw SignatureGenerationException(this, null)

    @Throws(SignatureGenerationException::class)
    override fun sign(contentBytes: ByteArray?): ByteArray = throw SignatureGenerationException(this, null)

    override val signingKeyId: String?
        get() = keyProvider.privateKeyId

    companion object {
        fun providerForKeys(publicKey: Any?, privateKey: Any?): ECDSAKeyProvider {
            require(!(publicKey == null && privateKey == null)) { "Both provided Keys cannot be null." }
            return StaticEcdsaKeyProvider(publicKey, privateKey)
        }
    }
}

private class StaticEcdsaKeyProvider(
    private val publicKeyValue: Any?,
    private val privateKeyValue: Any?,
) : ECDSAKeyProvider {
    override fun getPublicKeyById(keyId: String?): Any? = publicKeyValue

    override val privateKey: Any?
        get() = privateKeyValue

    override val privateKeyId: String?
        get() = null
}
