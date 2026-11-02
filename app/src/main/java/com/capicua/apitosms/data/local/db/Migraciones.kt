// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migraciones del esquema, escritas a mano y registradas en `DatabaseModule`.
 *
 * Desde que la aplicación se instaló con mensajes dentro, **cada cambio de esquema sube la versión
 * y añade aquí su `Migration`**: cambiar una versión en sitio hace que Room aborte al abrir la base
 * de quien ya la tenía.
 */
object Migraciones {

    /** v1 → v2: la IP desde la que se pidió cada mensaje. Los anteriores quedan con null. */
    val DE_1_A_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE mensajes ADD COLUMN ip_origen TEXT")
        }
    }

    /** Todas las migraciones conocidas, en orden. */
    val TODAS: Array<Migration> = arrayOf(DE_1_A_2)
}
