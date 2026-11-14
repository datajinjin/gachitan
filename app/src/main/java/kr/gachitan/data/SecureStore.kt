package kr.gachitan.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 앱 전용 내부 저장소에 AES-GCM으로 암호화해 저장합니다.
 *
 * Pleos 앱 보안 가이드 준수 사항
 * - 민감정보(동승자 이름)는 저장 위치와 관계없이 암호화합니다.
 * - 키는 Android Keystore에서 생성·보관하며 코드에 하드코딩하지 않습니다.
 * - AES-GCM을 쓰고, 매번 새 IV를 씁니다(같은 키로 IV 재사용 금지).
 * - 임시 파일에 평문을 남기지 않습니다. 암호문만 임시 파일에 쓰고 원자적으로 교체합니다.
 * - 자동 백업은 매니페스트에서 끕니다(android:allowBackup="false").
 */
class SecureStore(context: Context) : KeyValueStore {
    private val dir = File(context.filesDir, "secure").apply { mkdirs() }

    @Synchronized
    override fun write(name: String, text: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + sealed)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    @Synchronized
    override fun read(name: String): String? {
        val file = File(dir, name)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val ivLength = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivLength)
            val body = bytes.copyOfRange(1 + ivLength, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            // 키가 바뀌었거나 파일이 손상된 경우: 개인정보를 추측해 복구하지 않고 버립니다.
            Log.w(TAG, "unreadable $name, discarding", e)
            file.delete()
            null
        }
    }

    @Synchronized
    override fun delete(name: String) {
        File(dir, name).delete()
    }

    /** 설정 > 내 데이터 모두 지우기. 키까지 삭제해 이전 파일을 복호화할 수 없게 합니다. */
    @Synchronized
    override fun wipeAll() {
        dir.listFiles()?.forEach { it.delete() }
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun key(): SecretKey {
        (keyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val TAG = "SecureStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "gachitan.data.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
