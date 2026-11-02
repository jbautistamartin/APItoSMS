// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.servidor

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.security.interfaces.ECKey
import java.security.interfaces.RSAKey
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * El certificado TLS del servidor, que vive en `AndroidKeyStore`.
 *
 * Tiene **dos orígenes** (decisión J del masterplan), y en los dos la clave privada acaba en el
 * almacén del sistema, de donde no se puede extraer —ni con root en los teléfonos con almacén por
 * hardware—:
 *
 * - **Generado** ([generar]): el par de claves nace dentro de `AndroidKeyStore`, con un
 *   certificado autofirmado. La clave privada no ha existido nunca fuera del teléfono. Es lo que
 *   pasa la primera vez que se enciende el servidor.
 * - **Importado** ([importar]): un `.p12` creado con `ApiToSms.Certificado` o firmado por la CA de
 *   la empresa. Lo que se gana es poder cambiar de teléfono sin tocar los clientes, importando el
 *   mismo fichero. Lo que se paga es que la clave existió en ese fichero: hay que borrarlo o
 *   guardarlo bien.
 *
 * El servidor no pide la clave privada en ningún momento: [sslContext] le da a TLS un gestor de
 * claves que firma con la de `AndroidKeyStore`, y Conscrypt sabe usar una clave opaca del almacén.
 */
@Singleton
class CertificadoDelServidor @Inject constructor() {

    enum class Origen { GENERADO, IMPORTADO }

    data class Info(
        val origen: Origen?,
        val sujeto: String,
        val emisor: String,
        val validoDesde: Date,
        val validoHasta: Date,
        val algoritmo: String,
        val huellas: Huellas
    )

    private fun almacen(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun existe(): Boolean = almacen().containsAlias(ALIAS)

    fun certificado(): X509Certificate? = almacen().getCertificate(ALIAS) as? X509Certificate

    fun cadena(): Array<X509Certificate>? =
        almacen().getCertificateChain(ALIAS)?.map { it as X509Certificate }?.toTypedArray()

    fun info(origen: Origen?): Info? {
        val cert = certificado() ?: return null
        return Info(
            origen = origen,
            sujeto = cert.subjectX500Principal.name,
            emisor = cert.issuerX500Principal.name,
            validoDesde = cert.notBefore,
            validoHasta = cert.notAfter,
            algoritmo = cert.publicKey.algorithm,
            huellas = Huellas.de(cert)
        )
    }

    /**
     * Genera un par EC P-256 nuevo, con certificado autofirmado de diez años, y sustituye al que
     * hubiera. Diez años porque con la huella fijada la caducidad no protege de nada y solo
     * puede dejar a los clientes sin servicio un día cualquiera.
     */
    fun generar() {
        borrar()
        val desde = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        val hasta = Calendar.getInstance().apply { add(Calendar.YEAR, 10) }.time
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setCertificateSubject(X500Principal("CN=APItoSMS, O=Capicua"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(desde)
            .setCertificateNotAfter(hasta)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    /** El resultado de leer un `.p12` antes de importarlo, para enseñarlo y confirmar. */
    sealed interface Lectura {
        data class Valido(
            val clave: PrivateKey,
            val cadena: Array<X509Certificate>,
            val huellas: Huellas,
            val sujeto: String,
            val validoHasta: Date,
            /** IP y nombres DNS del SAN, para avisar si no coinciden con la IP wifi. */
            val nombres: List<String>
        ) : Lectura

        data class Invalido(val motivo: String) : Lectura
    }

    /** Lee y valida un `.p12` sin tocar el almacén. */
    fun leer(bytes: ByteArray, contrasena: CharArray): Lectura {
        val p12 = try {
            KeyStore.getInstance("PKCS12").apply { load(bytes.inputStream(), contrasena) }
        } catch (e: Exception) {
            return Lectura.Invalido("no se pudo abrir el fichero: ¿contraseña correcta? (${e.javaClass.simpleName})")
        }
        val alias = p12.aliases().toList().firstOrNull { p12.isKeyEntry(it) }
            ?: return Lectura.Invalido("el fichero no trae ninguna clave privada")
        val clave = try {
            p12.getKey(alias, contrasena) as? PrivateKey
        } catch (e: Exception) {
            null
        } ?: return Lectura.Invalido("no se pudo leer la clave privada")
        val cadena = p12.getCertificateChain(alias)?.map { it as X509Certificate }?.toTypedArray()
            ?: return Lectura.Invalido("el fichero no trae el certificado de la clave")
        val cert = cadena.first()

        try {
            cert.checkValidity()
        } catch (e: CertificateExpiredException) {
            return Lectura.Invalido("el certificado caducó el ${cert.notAfter}")
        } catch (e: CertificateNotYetValidException) {
            return Lectura.Invalido("el certificado no es válido hasta el ${cert.notBefore}")
        }
        when (val k = cert.publicKey) {
            is RSAKey -> if (k.modulus.bitLength() < 2048) {
                return Lectura.Invalido("una clave RSA de ${k.modulus.bitLength()} bits es demasiado corta; hacen falta 2048")
            }
            is ECKey -> if (k.params.order.bitLength() < 256) {
                return Lectura.Invalido("la curva EC es demasiado corta; hace falta P-256 o mayor")
            }
            else -> return Lectura.Invalido("tipo de clave no admitido: ${k.algorithm}")
        }
        val nombres = runCatching {
            cert.subjectAlternativeNames?.mapNotNull { it.getOrNull(1)?.toString() }.orEmpty()
        }.getOrDefault(emptyList())
        return Lectura.Valido(clave, cadena, Huellas.de(cert), cert.subjectX500Principal.name, cert.notAfter, nombres)
    }

    /** Mete la clave y la cadena en `AndroidKeyStore`, sustituyendo a la que hubiera. */
    fun importar(lectura: Lectura.Valido) {
        val esRsa = lectura.clave.algorithm.equals("RSA", ignoreCase = true)
        val proteccion = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .apply {
                // TLS 1.3 firma con RSA-PSS; TLS 1.2, con PKCS#1. Hacen falta los dos.
                if (esRsa) setSignaturePaddings(
                    KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS
                )
            }
            .build()
        borrar()
        @Suppress("UNCHECKED_CAST")
        almacen().setEntry(
            ALIAS,
            KeyStore.PrivateKeyEntry(lectura.clave, lectura.cadena as Array<java.security.cert.Certificate>),
            proteccion
        )
    }

    fun borrar() {
        val ks = almacen()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
    }

    /** El contexto TLS del servidor, con la clave del almacén. Genera una si no hay ninguna. */
    fun sslContext(): SSLContext {
        if (!existe()) generar()
        val ks = almacen()
        val clave = ks.getKey(ALIAS, null) as PrivateKey
        val cadena = ks.getCertificateChain(ALIAS).map { it as X509Certificate }.toTypedArray()
        return SSLContext.getInstance("TLS").apply {
            init(arrayOf(GestorDeClave(clave, cadena)), null, null)
        }
    }

    /**
     * Un gestor de claves con una sola clave, la del almacén.
     *
     * No se usa el `KeyManagerFactory` estándar porque espera poder leer la clave de un almacén
     * con contraseña; aquí la clave es opaca y basta con entregarla tal cual a TLS.
     */
    private class GestorDeClave(
        private val clave: PrivateKey,
        private val cadena: Array<X509Certificate>
    ) : X509ExtendedKeyManager() {

        private fun sirvePara(tipo: String?): Boolean {
            val algoritmo = clave.algorithm.uppercase()
            val t = tipo?.uppercase() ?: return true
            return t == algoritmo || t.startsWith("${algoritmo}_") ||
                (algoritmo == "EC" && t == "ECDSA") || t == "UNKNOWN"
        }

        override fun chooseServerAlias(tipo: String?, emisores: Array<out Principal>?, socket: Socket?) =
            ALIAS.takeIf { sirvePara(tipo) }

        override fun chooseEngineServerAlias(tipo: String?, emisores: Array<out Principal>?, motor: SSLEngine?) =
            ALIAS.takeIf { sirvePara(tipo) }

        override fun getServerAliases(tipo: String?, emisores: Array<out Principal>?) =
            if (sirvePara(tipo)) arrayOf(ALIAS) else null

        override fun getCertificateChain(alias: String?) = cadena.takeIf { alias == ALIAS }

        override fun getPrivateKey(alias: String?) = clave.takeIf { alias == ALIAS }

        override fun getClientAliases(tipo: String?, emisores: Array<out Principal>?): Array<String>? = null

        override fun chooseClientAlias(tipos: Array<out String>?, emisores: Array<out Principal>?, socket: Socket?): String? = null
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "apitosms_tls"
    }
}
