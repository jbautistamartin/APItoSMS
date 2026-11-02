// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.di

import com.capicua.apitosms.data.repository.ClaveRepository
import com.capicua.apitosms.data.repository.LogRepository
import com.capicua.apitosms.data.repository.MensajeRepository
import com.capicua.apitosms.domain.cola.AlmacenDeClaves
import com.capicua.apitosms.domain.cola.AlmacenDeMensajes
import com.capicua.apitosms.domain.cola.ControlDeCola
import com.capicua.apitosms.domain.cola.RegistroDeAuditoria
import com.capicua.apitosms.worker.ColaDeEnvios
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Los puertos del dominio, atados a sus implementaciones sobre Room y WorkManager. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindAlmacenDeMensajes(impl: MensajeRepository): AlmacenDeMensajes

    @Binds
    abstract fun bindAlmacenDeClaves(impl: ClaveRepository): AlmacenDeClaves

    @Binds
    abstract fun bindRegistro(impl: LogRepository): RegistroDeAuditoria

    @Binds
    abstract fun bindControlDeCola(impl: ColaDeEnvios): ControlDeCola
}
