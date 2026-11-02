// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.api

import com.capicua.apitosms.domain.cola.AlmacenDeClaves
import com.capicua.apitosms.domain.cola.AlmacenDeMensajes
import com.capicua.apitosms.domain.cola.ControlDeCola
import com.capicua.apitosms.domain.cola.FiltroDeMensajes
import com.capicua.apitosms.domain.cola.RegistroDeAuditoria
import com.capicua.apitosms.domain.model.Ambito
import com.capicua.apitosms.domain.model.ClaveApi
import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.domain.model.LogEntry
import com.capicua.apitosms.domain.model.LogTipo
import com.capicua.apitosms.domain.model.MensajeSaliente
import com.capicua.apitosms.domain.seguridad.BloqueoPorFallos
import com.capicua.apitosms.domain.seguridad.LimitadorDeRitmo
import com.capicua.apitosms.domain.seguridad.ListaDeRedes
import com.capicua.apitosms.domain.seguridad.Reloj
import com.capicua.apitosms.domain.seguridad.SecretoDeClave
import com.capicua.apitosms.domain.telefono.NormalizadorE164
import com.capicua.apitosms.domain.telefono.PoliticaDeDestinos
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

/**
 * Lo que la API admite en cada petición. Lo arma `ApiServerService` desde `AppConfig` en cada
 * petición, de modo que un cambio en Ajustes se aplica a la siguiente sin reiniciar el servidor.
 */
data class AjustesDeApi(
    val prefijoPais: String = "",
    val politica: PoliticaDeDestinos = PoliticaDeDestinos(),
    val maxPartesPorMensaje: Int = 3,
    val maxMensajesPorLote: Int = 100,
    /** SMS al día (contando partes) entre todas las claves. 0: sin límite. */
    val cuotaDiariaGlobal: Int = 0,
    val pausaEntreMensajesSegundos: Int = 3,
    val version: String = ""
)

/**
 * La API REST de APItoSMS, sin saber nada de sockets ni de TLS.
 *
 * El servidor (`data/servidor/ServidorHttps`) le entrega una [PeticionHttp] ya leída y escribe la
 * [RespuestaHttp] que devuelve. Todo lo que la API decide —quién es el cliente, qué puede hacer,
 * si el mensaje es válido, si cabe en la cuota— está aquí y se prueba en la JVM.
 *
 * ## Orden de las comprobaciones
 * 1. Ruta y método (`404`, `405`). `GET /salud` no pide autenticación.
 * 2. Autenticación (`401`). Cada fallo cuenta para el bloqueo por fuerza bruta de la IP.
 * 3. IP permitida para esa clave (`403`).
 * 4. Ritmo de peticiones de la clave (`429` con `Retry-After`).
 * 5. Ámbito (`403`).
 * 6. Cuerpo: tipo (`415`), JSON (`400`), campos (`422`), idempotencia (`409`), cuota (`429`).
 *
 * El bloqueo de IPs y la lista global de redes no están aquí: los aplica el servidor **antes del
 * saludo TLS**, al aceptar la conexión.
 *
 * Contrato completo: `docs/openapi.yaml`. El test `ContratoOpenApiTest` comprueba que las rutas
 * de [RUTAS] y las del fichero son las mismas.
 */
class ApiDeMensajes(
    private val mensajes: AlmacenDeMensajes,
    private val claves: AlmacenDeClaves,
    private val ajustes: suspend () -> AjustesDeApi,
    private val cola: ControlDeCola,
    private val contarPartes: (String) -> Int,
    private val registro: RegistroDeAuditoria,
    private val limitador: LimitadorDeRitmo,
    private val bloqueo: BloqueoPorFallos,
    private val reloj: Reloj = Reloj.SISTEMA,
    private val zona: ZoneId = ZoneId.systemDefault(),
    private val nuevoId: () -> String = { UUID.randomUUID().toString() }
) : ManejadorHttp {

    /**
     * Serializa las admisiones. La comprobación de idempotencia y la de cuota leen y luego
     * insertan, y entre las dos lecturas y la escritura no puede colarse otra admisión: dos
     * reintentos simultáneos de la misma petición crearían dos mensajes. En un teléfono que manda
     * un SMS cada pocos segundos, serializar la admisión no cuesta nada.
     */
    private val admision = Mutex()

    private val json = JsonApi.formato

    override suspend fun manejar(peticion: PeticionHttp): RespuestaHttp = try {
        enrutar(peticion)
    } catch (e: Rechazo) {
        if (e.estado != 404) registrarRechazo(peticion, e)
        problema(e)
    }

    // ── Rutas ─────────────────────────────────────────────────────────────

    private suspend fun enrutar(p: PeticionHttp): RespuestaHttp {
        val ruta = p.ruta.trimEnd('/').ifEmpty { "/" }
        val segmentos = ruta.split('/').filter { it.isNotEmpty() }
        if (segmentos.size < 2 || segmentos[0] != "api" || segmentos[1] != "v1") {
            throw Rechazo(404, "no-encontrado", "No existe", "No hay nada en ${p.ruta}")
        }
        val resto = segmentos.drop(2)

        return when {
            resto == listOf("salud") -> metodo(p, "GET") { salud() }

            resto == listOf("mensajes") -> when (p.metodo) {
                "POST" -> conClave(p, Ambito.ENVIAR) { crearMensaje(p, it) }
                "GET" -> conClave(p, Ambito.CONSULTAR) { listarMensajes(p, it) }
                else -> throw metodoNoPermitido("GET, POST")
            }

            resto.size == 2 && resto[0] == "mensajes" -> when (p.metodo) {
                "GET" -> conClave(p, Ambito.CONSULTAR) { consultarMensaje(resto[1], it) }
                "DELETE" -> conClave(p, Ambito.ENVIAR) { cancelarMensaje(p, resto[1], it) }
                else -> throw metodoNoPermitido("GET, DELETE")
            }

            resto == listOf("lotes") ->
                metodo(p, "POST") { conClave(p, Ambito.ENVIAR) { crearLote(p, it) } }

            resto == listOf("cola") ->
                metodo(p, "GET") { conClave(p, Ambito.CONSULTAR) { estadoCola(it) } }

            resto == listOf("cola", "pausa") ->
                metodo(p, "POST") { conClave(p, Ambito.ADMIN) { cambiarPausa(p, it, true) } }

            resto == listOf("cola", "reanudar") ->
                metodo(p, "POST") { conClave(p, Ambito.ADMIN) { cambiarPausa(p, it, false) } }

            else -> throw Rechazo(404, "no-encontrado", "No existe", "No hay nada en ${p.ruta}")
        }
    }

    private inline fun metodo(p: PeticionHttp, permitido: String, bloque: () -> RespuestaHttp): RespuestaHttp {
        if (p.metodo != permitido) throw metodoNoPermitido(permitido)
        return bloque()
    }

    private fun metodoNoPermitido(permitidos: String) =
        Rechazo(405, "metodo-no-permitido", "Método no permitido", "Esta ruta admite $permitidos",
            cabeceras = mapOf("Allow" to permitidos))

    // ── Autenticación y autorización ──────────────────────────────────────

    private suspend fun conClave(
        p: PeticionHttp,
        ambito: Ambito,
        bloque: suspend (ClaveApi) -> RespuestaHttp
    ): RespuestaHttp {
        val clave = autenticar(p)
        val ip = p.ip.hostAddress ?: "?"

        if (clave.redesPermitidas.isNotEmpty()) {
            val (redes, _) = ListaDeRedes.leer(clave.redesPermitidas)
            if (!redes.contiene(p.ip)) {
                throw Rechazo(403, "ip-no-permitida", "IP no permitida",
                    "La clave no admite peticiones desde $ip", clave = clave)
            }
        }

        when (val decision = limitador.consumir(clave.id, clave.peticionesPorMinuto)) {
            is LimitadorDeRitmo.Decision.Denegada -> throw Rechazo(
                429, "ritmo-excedido", "Demasiadas peticiones",
                "La clave admite ${clave.peticionesPorMinuto} peticiones por minuto",
                cabeceras = mapOf("Retry-After" to decision.reintentarEnSegundos.toString()),
                clave = clave
            )
            LimitadorDeRitmo.Decision.Permitida -> Unit
        }

        if (!clave.puede(ambito)) {
            throw Rechazo(403, "sin-permiso", "Sin permiso",
                "La clave no tiene el ámbito ${ambito.name}", clave = clave)
        }

        // El último uso se escribe como mucho una vez por minuto: un cliente que consulta cada
        // segundo no tiene por qué ser una escritura en disco por segundo.
        val ahora = reloj.ahora()
        val ultimo = clave.ultimoUsoEn
        if (ultimo == null || Duration.between(ultimo, ahora) > Duration.ofMinutes(1) || clave.ultimaIp != ip) {
            claves.registrarUso(clave.id, ahora, ip)
        }
        return bloque(clave)
    }

    /**
     * Devuelve la clave, o lanza `401`. Todos los motivos —sin cabecera, mal formada, inexistente,
     * revocada— dan **la misma respuesta**: distinguirlos le diría a quien prueba claves cuáles
     * existieron. El motivo real va al registro.
     */
    private suspend fun autenticar(p: PeticionHttp): ClaveApi {
        val ip = p.ip.hostAddress ?: "?"
        val cabecera = p.cabecera("authorization")
        val secreto = cabecera
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()

        val (clave, motivo) = when {
            cabecera == null -> null to "sin cabecera Authorization"
            secreto == null -> null to "Authorization no es Bearer"
            !SecretoDeClave.tieneFormato(secreto) -> null to "la clave no tiene el formato ats_…"
            else -> {
                val hash = SecretoDeClave.hash(secreto)
                val encontrada = claves.porHash(hash)
                    ?.takeIf { SecretoDeClave.mismoHash(it.hash, hash) }
                when {
                    encontrada == null -> null to "clave desconocida (${SecretoDeClave.prefijoVisible(secreto)}…)"
                    !encontrada.activa -> null to "clave revocada (${encontrada.nombre})"
                    else -> encontrada to null
                }
            }
        }

        if (clave != null) {
            bloqueo.registrarAcierto(ip)
            return clave
        }
        val bloqueada = bloqueo.registrarFallo(ip)
        if (bloqueada) {
            registro.registrar(LogEntry(
                tipo = LogTipo.SEGURIDAD, ip = ip, timestamp = reloj.ahora(),
                detalle = "IP $ip bloqueada por fallos de autenticación repetidos"
            ))
        }
        throw Rechazo(401, "no-autenticado", "No autenticado",
            "Hace falta una clave válida en Authorization: Bearer ats_…",
            cabeceras = mapOf("WWW-Authenticate" to "Bearer realm=\"APItoSMS\""),
            motivoInterno = motivo)
    }

    // ── Salud ─────────────────────────────────────────────────────────────

    private suspend fun salud(): RespuestaHttp =
        ok(200, json.encodeToString(SaludJson("ok", ajustes().version)))

    // ── Mensajes ──────────────────────────────────────────────────────────

    private suspend fun crearMensaje(p: PeticionHttp, clave: ClaveApi): RespuestaHttp {
        val cuerpo = leer<PeticionMensaje>(p)
        val idempotencia = idempotencyKey(p)
        val config = ajustes()
        val ahora = reloj.ahora()

        val (nuevo, errores) = validar(cuerpo, null, config, ahora)
        if (nuevo == null) throw validacion(errores, clave)

        return admision.withLock {
            if (idempotencia != null) {
                val previo = mensajes.porIdempotencia(clave.id, listOf(idempotencia)).firstOrNull()
                if (previo != null) {
                    if (previo.huellaPeticion != nuevo.huella) throw conflictoIdempotencia(clave)
                    return@withLock respuestaMensaje(202, previo, repetida = true)
                }
            }
            comprobarCuota(clave, nuevo.partes, ahora)

            val mensaje = nuevo.aMensaje(nuevoId(), clave, idempotencia, ahora)
            mensajes.insertarTodos(listOf(mensaje))
            registrarAdmitido(p, clave, mensaje)
            cola.despertar()
            respuestaMensaje(202, mensaje, repetida = false)
        }
    }

    private suspend fun crearLote(p: PeticionHttp, clave: ClaveApi): RespuestaHttp {
        val cuerpo = leer<PeticionLote>(p)
        val idempotencia = idempotencyKey(p)
        val config = ajustes()
        val ahora = reloj.ahora()

        val lista = cuerpo.mensajes
        if (lista.isNullOrEmpty()) {
            throw validacion(listOf(ErrorDeCampo(null, "mensajes", "hace falta al menos un mensaje")), clave)
        }
        if (lista.size > config.maxMensajesPorLote) {
            throw validacion(listOf(ErrorDeCampo(null, "mensajes",
                "un lote admite como mucho ${config.maxMensajesPorLote} mensajes, y este trae ${lista.size}")), clave)
        }

        val validados = mutableListOf<Validado>()
        val errores = mutableListOf<ErrorDeCampo>()
        lista.forEachIndexed { i, m ->
            val (v, e) = validar(m, i, config, ahora)
            if (v != null) validados += v else errores += e
        }
        // Todo o nada: un lote con un mensaje inválido no admite los otros. El cliente corrige y
        // reenvía el lote entero, con la misma Idempotency-Key si quiere.
        if (errores.isNotEmpty()) throw validacion(errores, clave)

        return admision.withLock {
            if (idempotencia != null) {
                val clavesLote = validados.indices.map { "$idempotencia#$it" }
                val previos = mensajes.porIdempotencia(clave.id, clavesLote)
                if (previos.isNotEmpty()) {
                    val porClave = previos.associateBy { it.idempotencyKey }
                    val mismo = previos.size == validados.size &&
                        validados.indices.all { porClave["$idempotencia#$it"]?.huellaPeticion == validados[it].huella }
                    if (!mismo) throw conflictoIdempotencia(clave)
                    val ordenados = validados.indices.map { porClave.getValue("$idempotencia#$it") }
                    return@withLock respuestaLote(ordenados, repetida = true)
                }
            }
            comprobarCuota(clave, validados.sumOf { it.partes }, ahora)

            // Mismo instante de admisión para todo el lote; el orden lo da el desempate por un id
            // que crece con el índice, así salen en el orden en que venían.
            val prefijo = nuevoId()
            val nuevos = validados.mapIndexed { i, v ->
                v.aMensaje(
                    id = "$prefijo-${i.toString().padStart(3, '0')}",
                    clave = clave,
                    idempotencia = idempotencia?.let { "$it#$i" },
                    ahora = ahora
                )
            }
            mensajes.insertarTodos(nuevos)
            nuevos.forEach { registrarAdmitido(p, clave, it) }
            cola.despertar()
            respuestaLote(nuevos, repetida = false)
        }
    }

    private suspend fun consultarMensaje(id: String, clave: ClaveApi): RespuestaHttp {
        val mensaje = mensajes.obtener(id)?.takeIf { visiblePara(it, clave) }
            ?: throw Rechazo(404, "no-encontrado", "No existe", "No hay ningún mensaje $id")
        return respuestaMensaje(200, mensaje, repetida = false)
    }

    private suspend fun cancelarMensaje(p: PeticionHttp, id: String, clave: ClaveApi): RespuestaHttp {
        val mensaje = mensajes.obtener(id)?.takeIf { visiblePara(it, clave) }
            ?: throw Rechazo(404, "no-encontrado", "No existe", "No hay ningún mensaje $id")

        if (!mensajes.cancelar(id, reloj.ahora())) {
            val actual = mensajes.obtener(id) ?: mensaje
            throw Rechazo(409, "no-cancelable", "No se puede cancelar",
                "El mensaje está ${actual.estado.name}; solo se cancela lo que sigue EN_COLA", clave = clave)
        }
        val cancelado = mensajes.obtener(id) ?: mensaje
        registro.registrar(LogEntry(
            tipo = LogTipo.CANCELADO, mensajeId = id, destino = cancelado.destino,
            clave = etiqueta(clave), ip = p.ip.hostAddress, timestamp = reloj.ahora(),
            detalle = "Cancelado por la API"
        ))
        return respuestaMensaje(200, cancelado, repetida = false)
    }

    private suspend fun listarMensajes(p: PeticionHttp, clave: ClaveApi): RespuestaHttp {
        val errores = mutableListOf<ErrorDeCampo>()
        val estado = p.query["estado"]?.let {
            EstadoMensaje.de(it) ?: null.also { _ ->
                errores += ErrorDeCampo(null, "estado", "no es un estado: ${EstadoMensaje.entries.joinToString()}")
            }
        }
        val desde = p.query["desde"]?.let {
            instante(it) ?: null.also { _ -> errores += ErrorDeCampo(null, "desde", "no es una fecha ISO 8601") }
        }
        val limite = p.query["limite"]?.let {
            it.toIntOrNull()?.takeIf { n -> n in 1..LIMITE_MAXIMO_PAGINA }
                ?: null.also { _ -> errores += ErrorDeCampo(null, "limite", "tiene que ir de 1 a $LIMITE_MAXIMO_PAGINA") }
        } ?: LIMITE_PAGINA_POR_DEFECTO
        val cursor = p.query["cursor"]?.let {
            leerCursor(it) ?: null.also { _ -> errores += ErrorDeCampo(null, "cursor", "cursor no válido") }
        }
        if (errores.isNotEmpty()) throw validacion(errores, clave)

        val pagina = mensajes.listar(FiltroDeMensajes(
            claveId = if (clave.puede(Ambito.ADMIN)) null else clave.id,
            estado = estado,
            referencia = p.query["referencia"],
            desde = desde,
            antesDe = cursor,
            limite = limite + 1
        ))
        val hayMas = pagina.size > limite
        val visibles = pagina.take(limite)
        val siguiente = if (hayMas) visibles.last().let { escribirCursor(it.admitidoEn, it.id) } else null
        return ok(200, json.encodeToString(PaginaJson(visibles.map { MensajeJson.de(it) }, siguiente)))
    }

    // ── Cola ──────────────────────────────────────────────────────────────

    private suspend fun estadoCola(clave: ClaveApi): RespuestaHttp {
        val config = ajustes()
        val inicioDia = inicioDeHoy()
        val deTodas = clave.puede(Ambito.ADMIN)
        val resumen = mensajes.resumenDesde(inicioDia, if (deTodas) null else clave.id)
        val cuerpo = ColaJson(
            pausada = cola.estaPausada(),
            enCola = mensajes.contarEnEstado(EstadoMensaje.EN_COLA),
            enviando = mensajes.contarEnEstado(EstadoMensaje.ENVIANDO),
            pausaEntreMensajesSegundos = config.pausaEntreMensajesSegundos,
            hoy = EstadoMensaje.entries.associate { it.name to (resumen[it] ?: 0) },
            cuotaGlobal = CuotaJson(config.cuotaDiariaGlobal, mensajes.partesAdmitidasDesde(inicioDia, null)),
            cuotaClave = CuotaJson(clave.cuotaDiaria, mensajes.partesAdmitidasDesde(inicioDia, clave.id))
        )
        return ok(200, json.encodeToString(cuerpo))
    }

    private suspend fun cambiarPausa(p: PeticionHttp, clave: ClaveApi, pausar: Boolean): RespuestaHttp {
        cola.cambiarPausa(pausar)
        if (!pausar) cola.despertar()
        registro.registrar(LogEntry(
            tipo = LogTipo.SEGURIDAD, clave = etiqueta(clave), ip = p.ip.hostAddress,
            timestamp = reloj.ahora(),
            detalle = if (pausar) "Cola pausada por la API" else "Cola reanudada por la API"
        ))
        return estadoCola(clave)
    }

    // ── Validación ────────────────────────────────────────────────────────

    /** Un mensaje que ha pasado la validación, a falta de id y de clave. */
    private class Validado(
        val destino: String,
        val texto: String,
        val referencia: String?,
        val caducaEn: Instant?,
        val partes: Int,
        val huella: String
    ) {
        fun aMensaje(id: String, clave: ClaveApi, idempotencia: String?, ahora: Instant) = MensajeSaliente(
            id = id,
            destino = destino,
            texto = texto,
            claveId = clave.id,
            claveNombre = "${clave.nombre} · ${clave.prefijo}…",
            idempotencyKey = idempotencia,
            huellaPeticion = huella,
            referencia = referencia,
            partes = partes,
            admitidoEn = ahora,
            caducaEn = caducaEn
        )
    }

    private fun validar(
        m: PeticionMensaje,
        indice: Int?,
        config: AjustesDeApi,
        ahora: Instant
    ): Pair<Validado?, List<ErrorDeCampo>> {
        val errores = mutableListOf<ErrorDeCampo>()
        fun error(campo: String, motivo: String) { errores += ErrorDeCampo(indice, campo, motivo) }

        val destino = when {
            m.destino.isNullOrBlank() -> null.also { error("destino", "es obligatorio") }
            else -> when (val r = NormalizadorE164.normalizar(m.destino, config.prefijoPais)) {
                is NormalizadorE164.Resultado.Invalido -> null.also { error("destino", r.motivo) }
                is NormalizadorE164.Resultado.Valido ->
                    config.politica.motivoDeRechazo(r.numero)?.let { motivo -> null.also { error("destino", motivo) } }
                        ?: r.numero
            }
        }

        val texto = m.texto
        var partes = 0
        when {
            texto.isNullOrEmpty() || texto.isBlank() -> error("texto", "es obligatorio")
            texto.length > MAX_CARACTERES -> error("texto", "admite como mucho $MAX_CARACTERES caracteres")
            else -> {
                partes = contarPartes(texto)
                if (partes > config.maxPartesPorMensaje) {
                    error("texto", "ocupa $partes SMS y este teléfono admite como mucho ${config.maxPartesPorMensaje} por mensaje")
                }
            }
        }

        val referencia = m.referencia?.trim()?.ifEmpty { null }
        if (referencia != null && referencia.length > MAX_REFERENCIA) {
            error("referencia", "admite como mucho $MAX_REFERENCIA caracteres")
        }

        val caducaEn = m.caducaEn?.let { valor ->
            val instante = instante(valor)
            when {
                instante == null -> null.also { error("caducaEn", "no es una fecha ISO 8601 con zona (2026-11-01T18:30:00Z)") }
                !instante.isAfter(ahora) -> null.also { error("caducaEn", "ya ha pasado") }
                else -> instante
            }
        }

        if (errores.isNotEmpty() || destino == null || texto == null) return null to errores
        val huella = huella(destino, texto, referencia, caducaEn)
        return Validado(destino, texto, referencia, caducaEn, partes, huella) to emptyList()
    }

    private fun huella(destino: String, texto: String, referencia: String?, caducaEn: Instant?): String {
        val contenido = listOf(destino, texto, referencia.orEmpty(), caducaEn?.toString().orEmpty())
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(contenido.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun idempotencyKey(p: PeticionHttp): String? {
        val valor = p.cabecera("idempotency-key") ?: return null
        if (!IDEMPOTENCY_KEY.matches(valor)) {
            throw Rechazo(422, "validacion", "Petición no válida",
                "Idempotency-Key tiene que tener de 1 a 100 caracteres: letras, dígitos, - _ : .",
                errores = listOf(ErrorDeCampo(null, "Idempotency-Key", "formato no válido")))
        }
        return valor
    }

    private suspend fun comprobarCuota(clave: ClaveApi, partesNuevas: Int, ahora: Instant) {
        val config = ajustes()
        val inicio = inicioDeHoy()
        val hastaManana = Duration.between(ahora, inicio.plus(Duration.ofDays(1))).seconds.coerceAtLeast(1)
        if (config.cuotaDiariaGlobal > 0) {
            val usadas = mensajes.partesAdmitidasDesde(inicio, null)
            if (usadas + partesNuevas > config.cuotaDiariaGlobal) {
                throw Rechazo(429, "cuota-agotada", "Cuota diaria agotada",
                    "El teléfono admite ${config.cuotaDiariaGlobal} SMS al día y van $usadas; esta petición son $partesNuevas",
                    cabeceras = mapOf("Retry-After" to hastaManana.toString()), clave = clave)
            }
        }
        if (clave.cuotaDiaria > 0) {
            val usadas = mensajes.partesAdmitidasDesde(inicio, clave.id)
            if (usadas + partesNuevas > clave.cuotaDiaria) {
                throw Rechazo(429, "cuota-agotada", "Cuota diaria agotada",
                    "La clave admite ${clave.cuotaDiaria} SMS al día y van $usadas; esta petición son $partesNuevas",
                    cabeceras = mapOf("Retry-After" to hastaManana.toString()), clave = clave)
            }
        }
    }

    private inline fun <reified T> leer(p: PeticionHttp): T {
        val tipo = p.cabecera("content-type")?.substringBefore(';')?.trim()?.lowercase()
        if (tipo != "application/json") {
            throw Rechazo(415, "tipo-no-soportado", "Tipo no soportado",
                "El cuerpo tiene que ser application/json")
        }
        val texto = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(p.cuerpo)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw Rechazo(400, "json-no-valido", "JSON no válido", "El cuerpo no es UTF-8 válido")
        }
        return try {
            json.decodeFromString<T>(texto)
        } catch (e: SerializationException) {
            throw Rechazo(400, "json-no-valido", "JSON no válido", e.message?.lineSequence()?.firstOrNull())
        } catch (e: IllegalArgumentException) {
            throw Rechazo(400, "json-no-valido", "JSON no válido", e.message?.lineSequence()?.firstOrNull())
        }
    }

    // ── Respuestas ────────────────────────────────────────────────────────

    private suspend fun respuestaMensaje(estado: Int, m: MensajeSaliente, repetida: Boolean): RespuestaHttp {
        val posicion = if (m.estado == EstadoMensaje.EN_COLA) mensajes.delanteEnCola(m) + 1 else null
        val cabeceras = buildMap {
            put("Location", "/api/v1/mensajes/${m.id}")
            if (repetida) put("Idempotent-Replayed", "true")
        }
        return ok(estado, json.encodeToString(MensajeJson.de(m, posicion)), cabeceras)
    }

    private suspend fun respuestaLote(lista: List<MensajeSaliente>, repetida: Boolean): RespuestaHttp {
        val cuerpo = LoteJson(lista.map { m ->
            MensajeJson.de(m, if (m.estado == EstadoMensaje.EN_COLA) mensajes.delanteEnCola(m) + 1 else null)
        })
        val cabeceras = if (repetida) mapOf("Idempotent-Replayed" to "true") else emptyMap()
        return ok(202, json.encodeToString(cuerpo), cabeceras)
    }

    private fun ok(estado: Int, cuerpo: String, cabeceras: Map<String, String> = emptyMap()) =
        RespuestaHttp(estado, RespuestaHttp.JSON, cuerpo.toByteArray(Charsets.UTF_8), cabeceras)

    private fun problema(r: Rechazo): RespuestaHttp {
        val cuerpo = ProblemaJson(
            type = "$URL_ERRORES#${r.codigo}",
            title = r.titulo,
            status = r.estado,
            detail = r.detalle,
            codigo = r.codigo,
            errores = r.errores
        )
        return RespuestaHttp(r.estado, RespuestaHttp.PROBLEMA,
            json.encodeToString(cuerpo).toByteArray(Charsets.UTF_8), r.cabeceras)
    }

    // ── Registro ──────────────────────────────────────────────────────────

    private suspend fun registrarAdmitido(p: PeticionHttp, clave: ClaveApi, m: MensajeSaliente) {
        registro.registrar(LogEntry(
            tipo = LogTipo.ADMITIDO, mensajeId = m.id, destino = m.destino, clave = etiqueta(clave),
            ip = p.ip.hostAddress, timestamp = reloj.ahora(),
            detalle = "Admitido (${m.partes} parte(s))" + (m.referencia?.let { ", referencia $it" } ?: "")
        ))
    }

    private suspend fun registrarRechazo(p: PeticionHttp, r: Rechazo) {
        val detalle = buildString {
            append("${p.metodo} ${p.ruta} → ${r.estado} ${r.codigo}")
            (r.motivoInterno ?: r.detalle)?.let { append(": $it") }
        }
        registro.registrar(LogEntry(
            tipo = LogTipo.RECHAZADO, clave = r.clave?.let(::etiqueta), ip = p.ip.hostAddress,
            timestamp = reloj.ahora(), detalle = detalle
        ))
    }

    // ── Utilidades ────────────────────────────────────────────────────────

    private fun validacion(errores: List<ErrorDeCampo>, clave: ClaveApi) =
        Rechazo(422, "validacion", "Petición no válida",
            errores.joinToString("; ") { e -> (e.indice?.let { "[$it] " } ?: "") + "${e.campo}: ${e.motivo}" },
            errores = errores, clave = clave)

    private fun conflictoIdempotencia(clave: ClaveApi) =
        Rechazo(409, "conflicto-idempotencia", "Idempotency-Key reutilizada",
            "Esa Idempotency-Key ya se usó con otro contenido", clave = clave)

    private fun visiblePara(m: MensajeSaliente, clave: ClaveApi): Boolean =
        clave.puede(Ambito.ADMIN) || m.claveId == clave.id

    private fun etiqueta(clave: ClaveApi) = "${clave.nombre} · ${clave.prefijo}…"

    private fun inicioDeHoy(): Instant =
        reloj.ahora().atZone(zona).toLocalDate().atStartOfDay(zona).toInstant()

    private fun instante(texto: String): Instant? = try {
        java.time.OffsetDateTime.parse(texto).toInstant()
    } catch (e: DateTimeParseException) {
        null
    }

    private fun escribirCursor(admitido: Instant, id: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("${admitido.toEpochMilli()}|$id".toByteArray(Charsets.UTF_8))

    private fun leerCursor(texto: String): Pair<Instant, String>? = runCatching {
        val partes = String(Base64.getUrlDecoder().decode(texto), Charsets.UTF_8).split('|', limit = 2)
        Instant.ofEpochMilli(partes[0].toLong()) to partes[1]
    }.getOrNull()

    /** Una respuesta de error, lanzada desde donde se detecta y escrita en [manejar]. */
    private class Rechazo(
        val estado: Int,
        val codigo: String,
        val titulo: String,
        val detalle: String? = null,
        val cabeceras: Map<String, String> = emptyMap(),
        val errores: List<ErrorDeCampo>? = null,
        val clave: ClaveApi? = null,
        /** Lo que va al registro y no al cliente. */
        val motivoInterno: String? = null
    ) : Exception(codigo)

    /** Una ruta de la API, para comprobarla contra `docs/openapi.yaml`. */
    data class Ruta(val metodo: String, val plantilla: String)

    companion object {
        const val MAX_CARACTERES = 1600
        const val MAX_REFERENCIA = 100
        const val LIMITE_PAGINA_POR_DEFECTO = 50
        const val LIMITE_MAXIMO_PAGINA = 200

        const val URL_ERRORES = "https://github.com/jbautistamartin/APItoSMS/blob/main/docs/errores.md"

        private val IDEMPOTENCY_KEY = Regex("^[A-Za-z0-9_\\-:.]{1,100}$")

        val RUTAS = listOf(
            Ruta("GET", "/api/v1/salud"),
            Ruta("POST", "/api/v1/mensajes"),
            Ruta("GET", "/api/v1/mensajes"),
            Ruta("GET", "/api/v1/mensajes/{id}"),
            Ruta("DELETE", "/api/v1/mensajes/{id}"),
            Ruta("POST", "/api/v1/lotes"),
            Ruta("GET", "/api/v1/cola"),
            Ruta("POST", "/api/v1/cola/pausa"),
            Ruta("POST", "/api/v1/cola/reanudar")
        )
    }
}
