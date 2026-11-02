// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.di

import android.content.Context
import androidx.room.Room
import com.capicua.apitosms.data.local.db.ApiToSmsDatabase
import com.capicua.apitosms.data.local.db.Migraciones
import com.capicua.apitosms.data.local.db.dao.ClaveDao
import com.capicua.apitosms.data.local.db.dao.LogDao
import com.capicua.apitosms.data.local.db.dao.MensajeDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ApiToSmsDatabase =
        Room.databaseBuilder(context, ApiToSmsDatabase::class.java, ApiToSmsDatabase.NOMBRE_BD)
            // Sin fallbackToDestructiveMigration: si falta una migración queremos que falle
            // de forma ruidosa, no que borre la cola.
            .addMigrations(*Migraciones.TODAS)
            .enableMultiInstanceInvalidation()
            // WAL: la API escribe mientras la cola lee, y con WAL una lectura no espera a una escritura.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides
    fun provideMensajeDao(db: ApiToSmsDatabase): MensajeDao = db.mensajeDao()

    @Provides
    fun provideClaveDao(db: ApiToSmsDatabase): ClaveDao = db.claveDao()

    @Provides
    fun provideLogDao(db: ApiToSmsDatabase): LogDao = db.logDao()
}
