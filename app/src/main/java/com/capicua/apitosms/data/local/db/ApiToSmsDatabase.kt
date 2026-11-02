// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.capicua.apitosms.data.local.db.dao.ClaveDao
import com.capicua.apitosms.data.local.db.dao.LogDao
import com.capicua.apitosms.data.local.db.dao.MensajeDao
import com.capicua.apitosms.data.local.db.entity.ClaveEntity
import com.capicua.apitosms.data.local.db.entity.LogEntity
import com.capicua.apitosms.data.local.db.entity.MensajeEntity

/**
 * Base de datos local de APItoSMS.
 *
 * Nace en la versión 1, como cada aplicación de la casa: `applicationId` nuevo, directorio de datos
 * nuevo, ningún teléfono con esta base. En cuanto se instale en uno con mensajes dentro, el esquema
 * deja de ser editable en sitio y cada cambio sube la versión con su `Migration` en [Migraciones].
 *
 * ## Tablas
 * | Tabla         | Papel                                                                   |
 * |---------------|-------------------------------------------------------------------------|
 * | `mensajes`    | La outbox: lo que pidió la API, persistido antes de contestar `202`.    |
 * | `claves`      | Las claves de la API, con el hash del secreto y nunca el secreto.       |
 * | `log_entries` | Registro de auditoría. Sin el texto de los SMS.                         |
 *
 * Nunca `fallbackToDestructiveMigration()`: borraría la cola, que es justo lo que la outbox existe
 * para no perder.
 */
@Database(
    entities = [MensajeEntity::class, ClaveEntity::class, LogEntity::class],
    version = 1,
    exportSchema = true
)
abstract class ApiToSmsDatabase : RoomDatabase() {

    abstract fun mensajeDao(): MensajeDao
    abstract fun claveDao(): ClaveDao
    abstract fun logDao(): LogDao

    companion object {
        const val NOMBRE_BD = "apitosms.db"
    }
}
