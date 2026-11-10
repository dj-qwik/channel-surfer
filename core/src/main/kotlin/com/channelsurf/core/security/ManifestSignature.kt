package com.channelsurf.core.security

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * ECDSA P-256 / SHA-256 signatures for published schedule manifests. Available on the JDK and on
 * Android API 26+, so the backend signs and the app verifies with identical code.
 */
object ManifestSignature {
    const val ALGORITHM = "SHA256withECDSA"
    const val HEADER_SIGNATURE = "X-Manifest-Signature"
    const val HEADER_REVISION = "X-Manifest-Revision"

    fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    fun sign(payload: ByteArray, key: PrivateKey): String {
        val s = Signature.getInstance(ALGORITHM)
        s.initSign(key)
        s.update(payload)
        return Base64.getEncoder().encodeToString(s.sign())
    }

    fun verify(payload: ByteArray, signatureBase64: String?, key: PublicKey): Boolean {
        if (signatureBase64.isNullOrBlank()) return false
        return try {
            val s = Signature.getInstance(ALGORITHM)
            s.initVerify(key)
            s.update(payload)
            s.verify(Base64.getDecoder().decode(signatureBase64.trim()))
        } catch (e: Exception) {
            false
        }
    }

    fun publicKeyFromBase64(x509Base64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(x509Base64.trim())))

    fun privateKeyFromPkcs8(der: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))

    fun publicKeyToBase64(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)
}
