package dev.kaos.kaosgfx.patcher

import com.android.apksig.ApkSigner
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

object ApkSignerUtil {
    private const val DEFAULT_KEYSTORE_RESOURCE = "/kaosgfx_signing.p12"
    private const val KEYSTORE_PASS = "kaosgfx"
    private const val ALIAS = "kaosgfx"

    data class SignerCredentials(
        val privateKey: PrivateKey,
        val certificates: List<X509Certificate>
    )

    fun loadDefaultCredentials(): SignerCredentials {
        val stream: InputStream = ApkSignerUtil::class.java.getResourceAsStream(DEFAULT_KEYSTORE_RESOURCE)
            ?: throw IllegalStateException("Resource $DEFAULT_KEYSTORE_RESOURCE not found")

        stream.use { input ->
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(input, KEYSTORE_PASS.toCharArray())
            val key = ks.getKey(ALIAS, KEYSTORE_PASS.toCharArray()) as? PrivateKey
                ?: throw IllegalStateException("Key alias $ALIAS not found in keystore")
            val certChain = ks.getCertificateChain(ALIAS)
                ?: throw IllegalStateException("Certificate chain for alias $ALIAS not found")
            val certs = certChain.map { it as X509Certificate }
            return SignerCredentials(key, certs)
        }
    }

    fun signApk(
        inputApk: File,
        outputApk: File,
        credentials: SignerCredentials = loadDefaultCredentials(),
        minSdkVersion: Int = 28
    ) {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            ALIAS,
            credentials.privateKey,
            credentials.certificates
        ).setMinSdkVersion(minSdkVersion).build()

        val builder = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setMinSdkVersion(minSdkVersion)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .setLibraryPageAlignmentBytes(16384)

        builder.build().sign()
    }
}
