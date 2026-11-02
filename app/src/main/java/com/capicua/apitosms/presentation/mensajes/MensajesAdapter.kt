// APItoSMS para Android
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

package com.capicua.apitosms.presentation.mensajes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.capicua.apitosms.R
import com.capicua.apitosms.databinding.ItemMensajeBinding
import com.capicua.apitosms.domain.model.EstadoMensaje
import com.capicua.apitosms.domain.model.MensajeSaliente
import com.capicua.apitosms.util.toDisplayString

/** Texto y color de cada estado, para la lista y para el detalle. */
internal val EstadoMensaje.etiqueta: String
    get() = when (this) {
        EstadoMensaje.EN_COLA -> "En cola"
        EstadoMensaje.ENVIANDO -> "Enviando"
        EstadoMensaje.ENVIADO -> "Enviado"
        EstadoMensaje.ENTREGADO -> "Entregado"
        EstadoMensaje.NO_ENTREGADO -> "No entregado"
        EstadoMensaje.FALLIDO -> "Fallido"
        EstadoMensaje.CANCELADO -> "Cancelado"
        EstadoMensaje.CADUCADO -> "Caducado"
    }

internal val EstadoMensaje.color: Int
    get() = when (this) {
        EstadoMensaje.EN_COLA -> R.color.status_pending
        EstadoMensaje.ENVIANDO -> R.color.status_dispatching
        EstadoMensaje.ENVIADO, EstadoMensaje.ENTREGADO -> R.color.status_delivered
        EstadoMensaje.NO_ENTREGADO, EstadoMensaje.FALLIDO -> R.color.status_failed
        EstadoMensaje.CANCELADO, EstadoMensaje.CADUCADO -> R.color.status_neutral
    }

class MensajesAdapter(
    private val alPulsar: (MensajeSaliente) -> Unit
) : ListAdapter<MensajeSaliente, MensajesAdapter.Fila>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Fila(ItemMensajeBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Fila, position: Int) = holder.bind(getItem(position))

    inner class Fila(private val binding: ItemMensajeBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(m: MensajeSaliente) {
            val ctx = binding.root.context
            val color = ContextCompat.getColor(ctx, m.estado.color)
            binding.root.setOnClickListener { alPulsar(m) }
            binding.viewIndicador.setBackgroundColor(color)
            binding.textDestino.text = m.destino
            binding.textEstado.text = m.estado.etiqueta
            binding.textEstado.setTextColor(color)
            // El texto puede estar purgado por la retención: la fila se queda, el contenido no.
            binding.textTexto.text = m.texto ?: ctx.getString(R.string.mensajes_texto_purgado)
            binding.textDetalle.text = listOfNotNull(
                m.admitidoEn.toDisplayString(),
                m.claveNombre,
                m.referencia?.let { "ref. $it" },
                m.ultimoError?.takeIf { m.estado != EstadoMensaje.ENTREGADO && m.estado != EstadoMensaje.ENVIADO }
            ).joinToString(" · ")
        }
    }

    private object Diff : DiffUtil.ItemCallback<MensajeSaliente>() {
        override fun areItemsTheSame(a: MensajeSaliente, b: MensajeSaliente) = a.id == b.id
        override fun areContentsTheSame(a: MensajeSaliente, b: MensajeSaliente) = a == b
    }
}
