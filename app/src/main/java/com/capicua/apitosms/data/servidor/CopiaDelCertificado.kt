// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.servidor

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El `.p12` importado, guardado cifrado para que pueda viajar en una copia con datos
 * confidenciales.
 *
 * Al importar, la clave privada entra en `AndroidKeyStore` y de ahí no se puede volver a sacar.
 * Para que un teléfono de repuesto enseñe **la misma huella** con solo importar la copia hace falta
 * el fichero original, así que se conserva aquí: el `.p12` y su contraseña, cifrados con AES-GCM
 * bajo una clave que vive en `AndroidKeyStore` y que tampoco sale de él. Lo que hay en disco no
 * sirve fuera de este teléfono.
 *
 * Un certificado **generado** no tiene copia: su clave no ha existido nunca fuera del almacén, y
 * así se queda. Generar uno nuevo borra la copia del anterior.
 *
 * El fichero está en `noBackupFilesDir`, que no entra en ninguna copia de Android.
 */
@Singleton
class CopiaDelCertificado @Inject constructor(
    @ApplicationContext private val context: Context
) {

    class Contenido(val p12: ByteArray, val contrasena: CharArray)

    private val fichero: File get() = File(context.noBackupFilesDir, FICHERO)

    fun existe(): Boolean = fichero.exists()

    /** Cifra y guarda el `.p12` y su contraseña, sustituyendo a la copia anterior. */
    fun guardar(p12: ByteArray, contrasena: CharArray) {
        val pass = Charsets.UTF_8.encode(CharBuffer.wrap(contrasena))
        val claro = ByteBuffer.allocate(4 + pass.remaining() + p12.size)
            .putInt(pass.remaining()).put(pass).put(p12).array()
        try {
            val cifrador = Cipher.getInstance(TRANSFORMACION).apply { init(Cipher.ENCRYPT_MODE, clave()) }
            val cifrado = cifrador.doFinal(claro)
            fichero.writeBytes(cifrador.iv + cifrado)
        } finally {
            claro.fill(0)
            if (pass.hasArray()) pass.array().fill(0)
        }
    }

    /** El `.p12` y su contraseña, o null si no hay copia o no se puede descifrar. */
    fun leer(): Contenido? {
        if (!existe()) return null
        return runCatching {
            val todo = fichero.readBytes()
            val iv = todo.copyOfRange(0, IV)
            val cifrador = Cipher.getInstance(TRANSFORMACION).apply {
                init(Cipher.DECRYPT_MODE, clave(), GCMParameterSpec(128, iv))
            }
            val claro = ByteBuffer.wrap(cifrador.doFinal(todo, IV, todo.size - IV))
            val largo = claro.int
            val pass = ByteArray(largo).also { claro.get(it) }
            val p12 = ByteArray(claro.remaining()).also { claro.get(it) }
            Contenido(p12, String(pass, Charsets.UTF_8).toCharArray().also { pass.fill(0) })
        }.getOrNull()
    }

    fun borrar() {
        fichero.delete()
    }

    private fun clave(): SecretKey {
        val almacen = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (almacen.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "apitosms_copia_p12"
        const val FICHERO = "certificado.p12.cifrado"
        const val TRANSFORMACION = "AES/GCM/NoPadding"
        const val IV = 12
    }
}
