// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.di

import com.capicua.apitosms.data.sms.SmsSender
import com.capicua.apitosms.domain.transport.EnviadorDeMensajes
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * El transporte. Es el **único** sitio que nombra la implementación concreta del medio de salida.
 *
 * En SMSRelay era un *multibinding* con un transporte por canal; aquí hay un solo medio, el SMS,
 * y vuelve a ser un `@Binds`, como en SMStoTelegram y SMStoWhats.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TransportModule {

    @Binds
    abstract fun bindEnviador(impl: SmsSender): EnviadorDeMensajes
}
