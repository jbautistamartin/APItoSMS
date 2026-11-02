// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.seguridad

import java.time.Duration
import java.time.Instant

/** Fuente del instante actual. Existe para que los tests controlen el tiempo. */
fun interface Reloj {
    fun ahora(): Instant

    companion object {
        val SISTEMA = Reloj { Instant.now() }
    }
}

/**
 * Límite de peticiones por minuto, por clave: un cubo de fichas que se rellena de forma continua.
 *
 * Es memoria y nada más, y es a propósito: se reinicia con el proceso, y está bien que así sea. No
 * es una cuota —eso es la cuota diaria, que cuenta en la base de datos—, es un freno para un
 * cliente que entra en bucle y martillea la API. Un reinicio del teléfono no es un momento en que
 * eso importe.
 */
class LimitadorDeRitmo(private val reloj: Reloj = Reloj.SISTEMA) {

    private class Cubo(var fichas: Double, var ultimaRecarga: Instant)

    private val cubos = HashMap<String, Cubo>()

    sealed interface Decision {
        data object Permitida : Decision
        data class Denegada(val reintentarEnSegundos: Long) : Decision
    }

    /** Consume una ficha del cubo de [clave], que admite [porMinuto] peticiones por minuto. */
    @Synchronized
    fun consumir(clave: String, porMinuto: Int): Decision {
        if (porMinuto <= 0) return Decision.Permitida
        val ahora = reloj.ahora()
        val cubo = cubos.getOrPut(clave) { Cubo(porMinuto.toDouble(), ahora) }

        val transcurridoMs = Duration.between(cubo.ultimaRecarga, ahora).toMillis().coerceAtLeast(0)
        cubo.fichas = (cubo.fichas + transcurridoMs * porMinuto / 60_000.0).coerceAtMost(porMinuto.toDouble())
        cubo.ultimaRecarga = ahora

        if (cubo.fichas >= 1.0) {
            cubo.fichas -= 1.0
            return Decision.Permitida
        }
        val faltaMs = ((1.0 - cubo.fichas) * 60_000.0 / porMinuto).toLong()
        return Decision.Denegada(reintentarEnSegundos = (faltaMs / 1000 + 1).coerceAtLeast(1))
    }

    @Synchronized
    fun olvidar(clave: String) {
        cubos.remove(clave)
    }
}

/**
 * Bloqueo por fuerza bruta: [maxFallos] autenticaciones fallidas en [ventana] desde una IP la
 * bloquean durante [duracionBloqueo].
 *
 * Un bloqueo se aplica **antes del saludo TLS**: el servidor cierra la conexión nada más
 * aceptarla, sin gastar CPU del teléfono en criptografía para quien está probando claves.
 */
class BloqueoPorFallos(
    private val reloj: Reloj = Reloj.SISTEMA,
    private val maxFallos: Int = 10,
    private val ventana: Duration = Duration.ofMinutes(5),
    private val duracionBloqueo: Duration = Duration.ofMinutes(15)
) {
    private val fallos = HashMap<String, ArrayDeque<Instant>>()
    private val bloqueadas = HashMap<String, Instant>()

    /** Apunta un fallo. Devuelve true si este fallo acaba de bloquear la IP. */
    @Synchronized
    fun registrarFallo(ip: String): Boolean {
        val ahora = reloj.ahora()
        val lista = fallos.getOrPut(ip) { ArrayDeque() }
        lista.addLast(ahora)
        while (lista.isNotEmpty() && lista.first().isBefore(ahora.minus(ventana))) lista.removeFirst()
        if (lista.size >= maxFallos) {
            bloqueadas[ip] = ahora.plus(duracionBloqueo)
            lista.clear()
            return true
        }
        return false
    }

    /** Un acierto borra los fallos acumulados de la IP. */
    @Synchronized
    fun registrarAcierto(ip: String) {
        fallos.remove(ip)
    }

    @Synchronized
    fun estaBloqueada(ip: String): Boolean {
        val hasta = bloqueadas[ip] ?: return false
        if (reloj.ahora().isBefore(hasta)) return true
        bloqueadas.remove(ip)
        return false
    }

    /** IPs bloqueadas ahora mismo, con su fin, para enseñarlas en el Panel. */
    @Synchronized
    fun bloqueadasAhora(): Map<String, Instant> {
        val ahora = reloj.ahora()
        bloqueadas.entries.removeIf { !ahora.isBefore(it.value) }
        return bloqueadas.toMap()
    }

    @Synchronized
    fun desbloquearTodas() {
        bloqueadas.clear()
        fallos.clear()
    }
}
