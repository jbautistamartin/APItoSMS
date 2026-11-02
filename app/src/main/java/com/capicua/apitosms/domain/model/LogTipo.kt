// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.domain.model

/** Categoría de un evento del registro de auditoría. [etiqueta] es el texto de su filtro. */
enum class LogTipo(val etiqueta: String) {
    /** Una petición de envío admitida: el mensaje está en la cola. */
    ADMITIDO("Admitidos"),

    /** El operador confirmó la salida del SMS. */
    ENVIADO("Enviados"),

    /** Informe de entrega, positivo o negativo. */
    ENTREGA("Entregas"),

    /** Un mensaje cancelado o caducado. */
    CANCELADO("Cancelados"),

    /**
     * Una petición rechazada por la API: sin autenticar, sin permiso, inválida, o por encima de un
     * límite. Es lo primero que hay que mirar si alguien está probando claves.
     */
    RECHAZADO("Rechazados"),

    /** Un intento de envío que falló, transitorio o permanente. */
    ERROR("Errores"),

    /**
     * Cambios que afectan a la seguridad: claves creadas, regeneradas o revocadas, certificado
     * generado o importado, IP bloqueada por fuerza bruta, cola pausada o reanudada.
     */
    SEGURIDAD("Seguridad"),

    /** Servidor encendido o apagado, cambio de red, limpieza periódica, rescate de mensajes. */
    SISTEMA("Sistema")
}
