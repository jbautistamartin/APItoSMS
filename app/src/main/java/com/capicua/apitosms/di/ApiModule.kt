// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.di

import com.capicua.apitosms.BuildConfig
import com.capicua.apitosms.data.config.ConfigDataStore
import com.capicua.apitosms.domain.api.ApiDeMensajes
import com.capicua.apitosms.domain.cola.AlmacenDeClaves
import com.capicua.apitosms.domain.cola.AlmacenDeMensajes
import com.capicua.apitosms.domain.cola.ControlDeCola
import com.capicua.apitosms.domain.cola.ProcesadorDeCola
import com.capicua.apitosms.domain.cola.RegistroDeAuditoria
import com.capicua.apitosms.domain.seguridad.BloqueoPorFallos
import com.capicua.apitosms.domain.seguridad.LimitadorDeRitmo
import com.capicua.apitosms.domain.transport.EnviadorDeMensajes
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/**
 * La API y la cola son clases del dominio, sin Hilt dentro (así se prueban en la JVM). Aquí se
 * montan con sus piezas de Android.
 *
 * El limitador de ritmo y el bloqueo por fuerza bruta son **singletons**: los comparten todas las
 * conexiones, y el Panel enseña las IPs bloqueadas.
 */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    fun provideLimitador(): LimitadorDeRitmo = LimitadorDeRitmo()

    @Provides
    @Singleton
    fun provideBloqueo(): BloqueoPorFallos = BloqueoPorFallos()

    @Provides
    @Singleton
    fun provideApi(
        mensajes: AlmacenDeMensajes,
        claves: AlmacenDeClaves,
        configDataStore: ConfigDataStore,
        cola: ControlDeCola,
        enviador: EnviadorDeMensajes,
        registro: RegistroDeAuditoria,
        limitador: LimitadorDeRitmo,
        bloqueo: BloqueoPorFallos
    ): ApiDeMensajes = ApiDeMensajes(
        mensajes = mensajes,
        claves = claves,
        ajustes = { configDataStore.config.first().ajustesDeApi(BuildConfig.VERSION_NAME) },
        cola = cola,
        contarPartes = enviador::contarPartes,
        registro = registro,
        limitador = limitador,
        bloqueo = bloqueo
    )

    @Provides
    @Singleton
    fun provideProcesador(
        mensajes: AlmacenDeMensajes,
        enviador: EnviadorDeMensajes,
        configDataStore: ConfigDataStore,
        registro: RegistroDeAuditoria
    ): ProcesadorDeCola = ProcesadorDeCola(
        almacen = mensajes,
        enviador = enviador,
        ajustes = { configDataStore.config.first().ajustesDeCola() },
        registro = registro
    )
}
