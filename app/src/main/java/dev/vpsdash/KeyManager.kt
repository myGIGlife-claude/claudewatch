package dev.vpsdash

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates the SSH key on the phone. The private key never leaves the device and is
 * stored encrypted with an AES key that lives in the Android Keystore (hardware-backed
 * on most phones).
 */
object KeyManager {
    private const val ALIAS = "vpsdash_wrap"
    private const val PRIV = "ssh_key.enc"
    private const val PUB = "ssh_key.pub"

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun publicKey(ctx: Context): String? =
        File(ctx.filesDir, PUB).takeIf { it.exists() }?.readText()?.trim()

    /** Creates a new RSA-3072 key pair. Slow-ish (~1s) - call off the main thread. */
    fun generate(ctx: Context): String {
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        val priv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toByteArray()
        val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, "claudewatch@android") }
            .toString(Charsets.UTF_8.name()).trim()
        kp.dispose()

        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        File(ctx.filesDir, PRIV).writeBytes(c.iv + c.doFinal(priv))
        priv.fill(0)
        File(ctx.filesDir, PUB).writeText(pub)
        return pub
    }

    fun privateKey(ctx: Context): ByteArray {
        val blob = File(ctx.filesDir, PRIV).readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /** One-liner to paste on the VPS. The key is locked to a single read-only command. */
    fun installCommand(pub: String): String =
        "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
            "echo 'command=\"/usr/local/bin/claude-dash --json\",restrict $pub' >> ~/.ssh/authorized_keys && " +
            "chmod 600 ~/.ssh/authorized_keys && echo 'ClaudeWatch key installed'"
}
