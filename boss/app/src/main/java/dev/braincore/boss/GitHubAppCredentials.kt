package dev.braincore.boss

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class GitHubAppTokenProvider(
    private val read: (String) -> GitHubAppTokens?,
    private val replace: (String, GitHubAppTokens) -> Boolean,
    private val refresh: suspend (String) -> GitHubAppTokens,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val rotation = Mutex()

    suspend fun access(id: String): String = rotation.withLock {
        val current = read(id) ?: reconnect()
        if (!current.needsRefresh(now())) return@withLock current.access
        // GitHub invalidates the old refresh token on use. Persist its replacement even if the caller leaves.
        withContext(NonCancellable) {
            val next = refresh(current.refresh)
            if (!replace(id, next)) reconnect()
            next.access
        }
    }

    private fun reconnect(): Nothing =
        throw GitHubAuthException("GitHub sign-in changed or expired. Reconnect in Settings.", reconnect = true)
}

internal class GitHubAppCredentialVault(ctx: Context) {
    private val file = AtomicFile(ctx.noBackupFilesDir.resolve("github-app-credentials"))
    private var loaded = false
    private var cached: Pair<String, GitHubAppTokens>? = null

    @Synchronized
    fun read(id: String): GitHubAppTokens? {
        if (!loaded) {
            if (file.baseFile.exists()) {
                val bytes = file.readFully()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
                val json = JSONObject(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString())
                cached = json.getString("id") to GitHubAppTokens.fromJson(json)
            }
            loaded = true
        }
        return cached?.takeIf { it.first == id }?.second
    }

    @Synchronized
    fun write(id: String, tokens: GitHubAppTokens) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == IV_BYTES)
        val json = tokens.toJson().put("id", id).toString()
        val bytes = cipher.iv + cipher.doFinal(json.encodeToByteArray())
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
        cached = id to tokens
        loaded = true
    }

    @Synchronized
    fun clear() {
        file.delete()
        cached = null
        loaded = true
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(KEY, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).run {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val KEY = "boss-github-app"
        const val IV_BYTES = 12
    }
}
