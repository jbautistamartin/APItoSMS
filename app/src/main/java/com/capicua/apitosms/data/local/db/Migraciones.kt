// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.local.db

import androidx.room.migration.Migration

/**
 * Migraciones del esquema, escritas a mano y registradas en `DatabaseModule`.
 *
 * Vacío porque el esquema nace en la versión 1 (ver [ApiToSmsDatabase]). Deja de poder estarlo en
 * cuanto la aplicación se instale con mensajes dentro: a partir de ahí, **cada cambio de esquema
 * sube la versión y añade aquí su `Migration`**, y cambiar la v1 en sitio hace que Room aborte al
 * abrir la base de quien ya la tenía.
 */
object Migraciones {

    /** Todas las migraciones conocidas, en orden. Vacío mientras el esquema siga en la v1. */
    val TODAS: Array<Migration> = emptyArray()
}
