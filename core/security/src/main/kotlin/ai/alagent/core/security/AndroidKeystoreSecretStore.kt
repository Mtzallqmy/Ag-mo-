package ai.alagent.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.Base64

/** API keys are encrypted with an AES/GCM key whose key material never leaves Android Keystore. */
class AndroidKeystoreSecretStore(context:Context, private val masterAlias:String="al_agent_master_v1"):SecretStore {
    private val prefs=context.getSharedPreferences("al_agent_encrypted_secrets",Context.MODE_PRIVATE)
    private val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    private fun key():SecretKey { (ks.getKey(masterAlias,null) as? SecretKey)?.let{return it}; return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder(masterAlias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())}.generateKey() }
    override fun put(alias:String,secret:ByteArray){ val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key()); val packed=c.iv + c.doFinal(secret); prefs.edit().putString(alias,Base64.getEncoder().encodeToString(packed)).apply() }
    override fun get(alias:String):ByteArray? { val packed=prefs.getString(alias,null)?.let{Base64.getDecoder().decode(it)}?:return null; if(packed.size<=12)return null; val iv=packed.copyOfRange(0,12); val ct=packed.copyOfRange(12,packed.size); val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv)); return c.doFinal(ct) }
    override fun remove(alias:String){prefs.edit().remove(alias).apply()}
}
