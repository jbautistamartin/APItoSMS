// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.api

import com.capicua.apitosms.AlmacenEnMemoria
import com.capicua.apitosms.ClavesEnMemoria
import com.capicua.apitosms.ColaFalsa
import com.capicua.apitosms.EnviadorFalso
import com.capicua.apitosms.RegistroEnMemoria
import com.capicua.apitosms.RelojFijo
import com.capicua.apitosms.domain.model.Ambito
import com.capicua.apitosms.domain.model.ClaveApi
import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.domain.model.LogTipo
import com.capicua.apitosms.domain.seguridad.BloqueoPorFallos
import com.capicua.apitosms.domain.seguridad.LimitadorDeRitmo
import com.capicua.apitosms.domain.seguridad.SecretoDeClave
import com.capicua.apitosms.domain.telefono.PoliticaDeDestinos
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.time.ZoneOffset

class ApiDeMensajesTest {

    private val reloj = RelojFijo()
    private val almacen = AlmacenEnMemoria()
    private val registro = RegistroEnMemoria()
    private val cola = ColaFalsa()
    private val bloqueo = BloqueoPorFallos(reloj)
    private var ajustes = AjustesDeApi(prefijoPais = "+34", politica = PoliticaDeDestinos(), cuotaDiariaGlobal = 0, version = "test")
    private var siguienteId = 0

    private val secretoErp = SecretoDeClave.generar()
    private val secretoLector = SecretoDeClave.generar()
    private val secretoAdmin = SecretoDeClave.generar()
    private val secretoRevocado = SecretoDeClave.generar()

    private fun clave(id: String, secreto: String, vararg ambitos: Ambito, revocada: Boolean = false, redes: List<String> = emptyList(), cuota: Int = 0, ritmo: Int = 120) =
        ClaveApi(
            id = id, nombre = id, prefijo = SecretoDeClave.prefijoVisible(secreto), hash = SecretoDeClave.hash(secreto),
            ambitos = ambitos.toSet(), redesPermitidas = redes, cuotaDiaria = cuota, peticionesPorMinuto = ritmo,
            creadaEn = reloj.ahora(), revocadaEn = if (revocada) reloj.ahora() else null
        )

    private val claves = ClavesEnMemoria(
        clave("erp", secretoErp, Ambito.ENVIAR, Ambito.CONSULTAR),
        clave("lector", secretoLector, Ambito.CONSULTAR),
        clave("admin", secretoAdmin, Ambito.ENVIAR, Ambito.CONSULTAR, Ambito.ADMIN),
        clave("vieja", secretoRevocado, Ambito.ENVIAR, revocada = true)
    )

    private val api = ApiDeMensajes(
        almacen, claves, { ajustes }, cola, EnviadorFalso()::contarPartes, registro,
        LimitadorDeRitmo(reloj), bloqueo, reloj, ZoneOffset.UTC, { "id${siguienteId++}" }
    )

    private suspend fun pedir(
        metodo: String, ruta: String, cuerpo: String? = null, secreto: String? = secretoErp,
        query: Map<String, String> = emptyMap(), extra: Map<String, String> = emptyMap(),
        ip: String = "192.168.1.10", tipo: String = "application/json"
    ): Pair<RespuestaHttp, JsonObject?> {
        val cabeceras = buildMap {
            secreto?.let { put("authorization", "Bearer $it") }
            if (cuerpo != null) put("content-type", tipo)
            putAll(extra)
        }
        val r = api.manejar(PeticionHttp(metodo, ruta, query, cabeceras, cuerpo?.toByteArray() ?: ByteArray(0), InetAddress.getByName(ip)))
        val json = r.cuerpo.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(String(it)).jsonObject }
        return r to json
    }

    private fun JsonObject.texto(campo: String) = this[campo]?.jsonPrimitive?.content

    // ── Autenticación ─────────────────────────────────────────────────────────

    @Test
    fun `salud no pide clave`() = runTest {
        val (r, j) = pedir("GET", "/api/v1/salud", secreto = null)
        assertEquals(200, r.estado)
        assertEquals("ok", j!!.texto("estado"))
    }

    @Test
    fun `sin clave, clave falsa o revocada dan el mismo 401`() = runTest {
        val respuestas = listOf(null, "Basic abc", SecretoDeClave.generar(), secretoRevocado).map { s ->
            val cab = s?.let { if (it.startsWith("Basic")) mapOf("authorization" to it) else emptyMap() } ?: emptyMap()
            pedir("GET", "/api/v1/mensajes", secreto = s?.takeIf { !it.startsWith("Basic") }, extra = cab)
        }
        respuestas.forEach { (r, j) ->
            assertEquals(401, r.estado)
            assertEquals("no-autenticado", j!!.texto("codigo"))
            assertNotNull(r.cabeceras["WWW-Authenticate"])
        }
        assertEquals(1, respuestas.map { String(it.first.cuerpo) }.toSet().size)
        assertTrue(registro.entradas.all { it.tipo == LogTipo.RECHAZADO })
    }

    @Test
    fun `diez fallos bloquean la IP`() = runTest {
        repeat(10) { pedir("GET", "/api/v1/mensajes", secreto = SecretoDeClave.generar(), ip = "10.0.0.9") }
        assertTrue(bloqueo.estaBloqueada("10.0.0.9"))
        assertTrue(registro.entradas.any { it.tipo == LogTipo.SEGURIDAD })
    }

    @Test
    fun `sin el ámbito da 403`() = runTest {
        val (r, j) = pedir("POST", "/api/v1/mensajes", """{"destino":"600123456","texto":"hola"}""", secreto = secretoLector)
        assertEquals(403, r.estado)
        assertEquals("sin-permiso", j!!.texto("codigo"))
        assertEquals(403, pedir("POST", "/api/v1/cola/pausa", secreto = secretoErp).first.estado)
    }

    @Test
    fun `una red propia rechaza otras IP`() = runTest {
        claves.porId["erp"] = claves.porId["erp"]!!.copy(redesPermitidas = listOf("192.168.1.0/24"))
        assertEquals(200, pedir("GET", "/api/v1/mensajes", ip = "192.168.1.50").first.estado)
        val (r, j) = pedir("GET", "/api/v1/mensajes", ip = "192.168.2.50")
        assertEquals(403, r.estado)
        assertEquals("ip-no-permitida", j!!.texto("codigo"))
    }

    @Test
    fun `el ritmo se limita por clave con Retry-After`() = runTest {
        claves.porId["erp"] = claves.porId["erp"]!!.copy(peticionesPorMinuto = 2)
        pedir("GET", "/api/v1/mensajes"); pedir("GET", "/api/v1/mensajes")
        val (r, _) = pedir("GET", "/api/v1/mensajes")
        assertEquals(429, r.estado)
        assertNotNull(r.cabeceras["Retry-After"])
    }

    // ── Rutas ─────────────────────────────────────────────────────────────────

    @Test
    fun `ruta desconocida da 404 y método equivocado 405 con Allow`() = runTest {
        assertEquals(404, pedir("GET", "/api/v1/nada").first.estado)
        assertEquals(404, pedir("GET", "/").first.estado)
        val (r, _) = pedir("PUT", "/api/v1/mensajes")
        assertEquals(405, r.estado)
        assertEquals("GET, POST", r.cabeceras["Allow"])
    }

    // ── Envío ─────────────────────────────────────────────────────────────────

    @Test
    fun `un mensaje se admite con 202, normalizado y en cola`() = runTest {
        val (r, j) = pedir("POST", "/api/v1/mensajes", """{"destino":"600 123 456","texto":"hola","referencia":"pedido-1"}""")
        assertEquals(202, r.estado)
        assertEquals("+34600123456", j!!.texto("destino"))
        assertEquals("EN_COLA", j.texto("estado"))
        assertEquals(1, j["posicionEnCola"]!!.jsonPrimitive.int)
        assertEquals("/api/v1/mensajes/id0", r.cabeceras["Location"])
        assertEquals(1, cola.despertares)
        assertEquals("erp", almacen.filas["id0"]!!.claveId)
    }

    @Test
    fun `cuerpo sin JSON da 415 y JSON roto 400`() = runTest {
        assertEquals(415, pedir("POST", "/api/v1/mensajes", "destino=1", tipo = "application/x-www-form-urlencoded").first.estado)
        assertEquals(400, pedir("POST", "/api/v1/mensajes", "{roto").first.estado)
        // Un campo desconocido es un error, no se ignora: así se ven las erratas del cliente.
        assertEquals(400, pedir("POST", "/api/v1/mensajes", """{"destino":"600123456","texto":"x","prioridad":1}""").first.estado)
    }

    @Test
    fun `los errores de validación vienen campo a campo`() = runTest {
        val (r, j) = pedir("POST", "/api/v1/mensajes", """{"destino":"abc","texto":"","caducaEn":"ayer"}""")
        assertEquals(422, r.estado)
        val campos = j!!["errores"]!!.jsonArray.map { it.jsonObject.texto("campo") }.toSet()
        assertEquals(setOf("destino", "texto", "caducaEn"), campos)
        assertTrue(almacen.filas.isEmpty())
    }

    @Test
    fun `un texto que pasa de las partes permitidas se rechaza`() = runTest {
        ajustes = ajustes.copy(maxPartesPorMensaje = 1)
        assertEquals(422, pedir("POST", "/api/v1/mensajes", """{"destino":"600123456","texto":"${"x".repeat(200)}"}""").first.estado)
    }

    @Test
    fun `un prefijo bloqueado se rechaza`() = runTest {
        ajustes = ajustes.copy(politica = PoliticaDeDestinos(bloqueados = listOf("+34806")))
        assertEquals(422, pedir("POST", "/api/v1/mensajes", """{"destino":"806123456","texto":"x"}""").first.estado)
    }

    // ── Idempotencia ──────────────────────────────────────────────────────────

    @Test
    fun `la misma Idempotency-Key con el mismo cuerpo devuelve el original`() = runTest {
        val cuerpo = """{"destino":"600123456","texto":"hola"}"""
        val clave = mapOf("idempotency-key" to "pedido-77")
        val (primera, j1) = pedir("POST", "/api/v1/mensajes", cuerpo, extra = clave)
        val (segunda, j2) = pedir("POST", "/api/v1/mensajes", cuerpo, extra = clave)
        assertEquals(202, primera.estado)
        assertEquals(202, segunda.estado)
        assertEquals(j1!!.texto("id"), j2!!.texto("id"))
        assertEquals("true", segunda.cabeceras["Idempotent-Replayed"])
        assertEquals(1, almacen.filas.size)
    }

    @Test
    fun `la misma Idempotency-Key con otro cuerpo da 409`() = runTest {
        val clave = mapOf("idempotency-key" to "pedido-77")
        pedir("POST", "/api/v1/mensajes", """{"destino":"600123456","texto":"hola"}""", extra = clave)
        val (r, j) = pedir("POST", "/api/v1/mensajes", """{"destino":"600123456","texto":"adiós"}""", extra = clave)
        assertEquals(409, r.estado)
        assertEquals("conflicto-idempotencia", j!!.texto("codigo"))
    }

    @Test
    fun `la Idempotency-Key es de cada clave`() = runTest {
        val cuerpo = """{"destino":"600123456","texto":"hola"}"""
        val clave = mapOf("idempotency-key" to "k1")
        pedir("POST", "/api/v1/mensajes", cuerpo, extra = clave)
        pedir("POST", "/api/v1/mensajes", cuerpo, secreto = secretoAdmin, extra = clave)
        assertEquals(2, almacen.filas.size)
    }

    // ── Lotes ─────────────────────────────────────────────────────────────────

    @Test
    fun `un lote se admite entero y en orden`() = runTest {
        val (r, j) = pedir("POST", "/api/v1/lotes",
            """{"mensajes":[{"destino":"600000001","texto":"a"},{"destino":"600000002","texto":"b"}]}""")
        assertEquals(202, r.estado)
        val lista = j!!["mensajes"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(1, 2), lista.map { it["posicionEnCola"]!!.jsonPrimitive.int })
        assertEquals(listOf("id0-000", "id0-001"), lista.map { it.texto("id") })
    }

    @Test
    fun `un lote con un mensaje malo no admite ninguno`() = runTest {
        val (r, j) = pedir("POST", "/api/v1/lotes",
            """{"mensajes":[{"destino":"600000001","texto":"a"},{"destino":"x","texto":"b"}]}""")
        assertEquals(422, r.estado)
        assertEquals(1, j!!["errores"]!!.jsonArray.single().jsonObject["indice"]!!.jsonPrimitive.int)
        assertTrue(almacen.filas.isEmpty())
    }

    @Test
    fun `un lote repetido con la misma clave no duplica`() = runTest {
        val cuerpo = """{"mensajes":[{"destino":"600000001","texto":"a"},{"destino":"600000002","texto":"b"}]}"""
        val clave = mapOf("idempotency-key" to "lote-1")
        pedir("POST", "/api/v1/lotes", cuerpo, extra = clave)
        val (r, _) = pedir("POST", "/api/v1/lotes", cuerpo, extra = clave)
        assertEquals(202, r.estado)
        assertEquals("true", r.cabeceras["Idempotent-Replayed"])
        assertEquals(2, almacen.filas.size)
    }

    @Test
    fun `un lote más grande que el máximo se rechaza`() = runTest {
        ajustes = ajustes.copy(maxMensajesPorLote = 1)
        assertEquals(422, pedir("POST", "/api/v1/lotes",
            """{"mensajes":[{"destino":"600000001","texto":"a"},{"destino":"600000002","texto":"b"}]}""").first.estado)
    }

    // ── Cuotas ────────────────────────────────────────────────────────────────

    @Test
    fun `la cuota global y la de la clave cuentan partes`() = runTest {
        ajustes = ajustes.copy(cuotaDiariaGlobal = 2)
        assertEquals(202, pedir("POST", "/api/v1/mensajes", """{"destino":"600000001","texto":"a"}""").first.estado)
        assertEquals(202, pedir("POST", "/api/v1/mensajes", """{"destino":"600000001","texto":"b"}""").first.estado)
        val (r, j) = pedir("POST", "/api/v1/mensajes", """{"destino":"600000001","texto":"c"}""")
        assertEquals(429, r.estado)
        assertEquals("cuota-agotada", j!!.texto("codigo"))
        assertNotNull(r.cabeceras["Retry-After"])

        ajustes = ajustes.copy(cuotaDiariaGlobal = 0)
        claves.porId["erp"] = claves.porId["erp"]!!.copy(cuotaDiaria = 2)
        assertEquals(429, pedir("POST", "/api/v1/mensajes", """{"destino":"600000001","texto":"d"}""").first.estado)
        assertEquals(202, pedir("POST", "/api/v1/mensajes", """{"destino":"600000001","texto":"d"}""", secreto = secretoAdmin).first.estado)
    }

    @Test
    fun `la cuota se renueva al día siguiente`() = runTest {
        ajustes = ajustes.copy(cuotaDiariaGlobal = 1)
        pedir("POST", "/api/v1/mensajes", """{"destino":"600000001","texto":"a"}""")
        reloj.avanzar(24 * 3600)
        assertEquals(202, pedir("POST", "/api/v1/mensajes", """{"destino":"600000001","texto":"b"}""").first.estado)
    }

    // ── Consulta, cancelación y listado ───────────────────────────────────────

    @Test
    fun `cada clave solo ve lo suyo, salvo la de administrador`() = runTest {
        pedir("POST", "/api/v1/mensajes", """{"destino":"600000001","texto":"a"}""")
        assertEquals(200, pedir("GET", "/api/v1/mensajes/id0").first.estado)
        assertEquals(404, pedir("GET", "/api/v1/mensajes/id0", secreto = secretoLector).first.estado)
        assertEquals(200, pedir("GET", "/api/v1/mensajes/id0", secreto = secretoAdmin).first.estado)
    }

    @Test
    fun `cancelar solo funciona en cola`() = runTest {
        pedir("POST", "/api/v1/mensajes", """{"destino":"600000001","texto":"a"}""")
        val (r, j) = pedir("DELETE", "/api/v1/mensajes/id0")
        assertEquals(200, r.estado)
        assertEquals("CANCELADO", j!!.texto("estado"))
        val (otra, j2) = pedir("DELETE", "/api/v1/mensajes/id0")
        assertEquals(409, otra.estado)
        assertEquals("no-cancelable", j2!!.texto("codigo"))
    }

    @Test
    fun `el listado pagina con cursor sin repetir ni saltar`() = runTest {
        // Un lote: todos con el mismo instante de admisión, que es el caso difícil.
        val cuerpo = (0 until 5).joinToString(",", """{"mensajes":[""", "]}") { """{"destino":"60000000$it","texto":"m$it"}""" }
        pedir("POST", "/api/v1/lotes", cuerpo)
        val vistos = mutableListOf<String>()
        var cursor: String? = null
        var paginas = 0
        do {
            val query = buildMap { put("limite", "2"); cursor?.let { put("cursor", it) } }
            val (_, j) = pedir("GET", "/api/v1/mensajes", query = query)
            vistos += j!!["mensajes"]!!.jsonArray.map { it.jsonObject.texto("id")!! }
            cursor = j.texto("siguiente")
            paginas++
        } while (cursor != null)
        assertEquals(3, paginas)
        assertEquals(5, vistos.toSet().size)
        assertEquals(vistos.sortedDescending(), vistos)
    }

    @Test
    fun `el listado filtra por estado y valida la consulta`() = runTest {
        pedir("POST", "/api/v1/mensajes", """{"destino":"600000001","texto":"a"}""")
        pedir("POST", "/api/v1/mensajes", """{"destino":"600000001","texto":"b"}""")
        pedir("DELETE", "/api/v1/mensajes/id0")
        val (_, j) = pedir("GET", "/api/v1/mensajes", query = mapOf("estado" to "CANCELADO"))
        assertEquals(listOf("id0"), j!!["mensajes"]!!.jsonArray.map { it.jsonObject.texto("id") })
        assertNull(j.texto("siguiente"))
        assertEquals(422, pedir("GET", "/api/v1/mensajes", query = mapOf("estado" to "RARO")).first.estado)
        assertEquals(422, pedir("GET", "/api/v1/mensajes", query = mapOf("limite" to "0")).first.estado)
    }

    // ── Cola ──────────────────────────────────────────────────────────────────

    @Test
    fun `el administrador pausa y reanuda la cola`() = runTest {
        val (r, j) = pedir("POST", "/api/v1/cola/pausa", secreto = secretoAdmin)
        assertEquals(200, r.estado)
        assertEquals("true", j!!.texto("pausada"))
        assertTrue(cola.pausada)
        pedir("POST", "/api/v1/cola/reanudar", secreto = secretoAdmin)
        assertTrue(!cola.pausada)
        assertEquals(1, cola.despertares)
    }

    @Test
    fun `el estado de la cola cuenta lo de hoy`() = runTest {
        pedir("POST", "/api/v1/mensajes", """{"destino":"600000001","texto":"a"}""")
        val (_, j) = pedir("GET", "/api/v1/cola")
        assertEquals(1, j!!["enCola"]!!.jsonPrimitive.int)
        assertEquals(1, j["hoy"]!!.jsonObject[EstadoMensaje.EN_COLA.name]!!.jsonPrimitive.int)
    }
}
