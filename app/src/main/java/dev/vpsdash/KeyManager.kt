package dev.vpsdash

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    /** The public key. Keys made before v1.6 were all named "...@android"; rename those
     *  to the descriptive name (the comment isn't part of the key, so nothing breaks). */
    fun publicKey(ctx: Context): String? {
        val f = File(ctx.filesDir, PUB).takeIf { it.exists() } ?: return null
        val parts = f.readText().trim().split(" ")
        if (parts.size >= 2 && parts.getOrNull(2)?.endsWith("@android") != false) {
            f.writeText("${parts[0]} ${parts[1]} ${keyName(ctx, f.lastModified())}")
        }
        return f.readText().trim()
    }

    /** e.g. "ClaudeWatch_Galaxy-Z-Fold5_2026-09-26", so the key is easy to spot in authorized_keys. */
    private fun keyName(ctx: Context, created: Long): String {
        val device = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(created))
        return "ClaudeWatch_${device}_$day".replace(Regex("[^A-Za-z0-9._-]+"), "-")
    }

    /** Creates a new RSA-3072 key pair. Slow-ish (~1s) - call off the main thread. */
    fun generate(ctx: Context): String {
        val kp = KeyPair.genKeyPair(JSch(), KeyPair.RSA, 3072)
        val priv = ByteArrayOutputStream().also { kp.writePrivateKey(it) }.toByteArray()
        val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, keyName(ctx, System.currentTimeMillis())) }
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

    /** One-liner to paste on the VPS. The key is locked to a single read-only command.
     *  Safe to paste again: it first drops any existing line for this same key. */
    fun installCommand(pub: String): String {
        val tail = pub.split(" ").getOrElse(1) { pub }.takeLast(40)
        return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && " +
            "{ grep -vF '$tail' ~/.ssh/authorized_keys > ~/.ssh/ak.tmp; mv ~/.ssh/ak.tmp ~/.ssh/authorized_keys; } && " +
            "echo 'command=\"/usr/local/bin/claude-dash --json\",restrict $pub' >> ~/.ssh/authorized_keys && " +
            "chmod 600 ~/.ssh/authorized_keys && echo 'ClaudeWatch key installed'"
    }

    /** Downloads and installs the server script from GitHub. */
    const val SERVER_INSTALL =
        "curl -fsSL https://raw.githubusercontent.com/myGIGlife-claude/claudewatch/main/server/claude-dash.py -o /tmp/claude-dash && " +
            "sudo install -m 755 /tmp/claude-dash /usr/local/bin/claude-dash && rm /tmp/claude-dash && " +
            "claude-dash --json > /dev/null && echo 'ClaudeWatch server script installed'"

    /** Prints just the plan-usage part of the script's output. */
    const val USAGE_CHECK = "claude-dash --json | python3 -c \"import json,sys; print(json.load(sys.stdin)['usage'])\""
}
