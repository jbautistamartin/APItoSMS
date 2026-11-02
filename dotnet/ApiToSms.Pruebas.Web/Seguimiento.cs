// APItoSMS — Web de pruebas
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

using System.Collections.Concurrent;
using System.Runtime.CompilerServices;
using System.Threading.Channels;
using ApiToSms.Cliente;

namespace ApiToSms.Pruebas.Web;

/// <summary>Un evento para el navegador: <c>mensaje</c>, <c>cola</c> o <c>error</c>.</summary>
public sealed record Evento(string Tipo, object Datos);

/// <summary>
/// Sondea el teléfono por los mensajes enviados desde esta web y empuja cada cambio al navegador
/// por SSE. El navegador no habla con el teléfono: no podría fijar su certificado autofirmado.
/// </summary>
public sealed class Seguimiento(Conexion conexion, ILogger<Seguimiento> log) : BackgroundService
{
    private static readonly TimeSpan Intervalo = TimeSpan.FromSeconds(2);

    private readonly ConcurrentDictionary<string, Mensaje> _mensajes = new();
    private readonly ConcurrentDictionary<Channel<Evento>, byte> _suscriptores = new();

    public void Seguir(IEnumerable<Mensaje> mensajes)
    {
        foreach (var m in mensajes)
        {
            _mensajes[m.Id] = m;
            Emitir(new Evento("mensaje", m));
        }
    }

    public void Limpiar() => _mensajes.Clear();

    /// <summary>Los eventos desde ahora, empezando por el estado de todo lo que se sigue.</summary>
    public async IAsyncEnumerable<Evento> Suscribir([EnumeratorCancellation] CancellationToken ct)
    {
        var canal = Channel.CreateBounded<Evento>(new BoundedChannelOptions(1000) { FullMode = BoundedChannelFullMode.DropOldest });
        _suscriptores[canal] = 0;
        try
        {
            foreach (var m in _mensajes.Values.OrderBy(m => m.AdmitidoEn).ThenBy(m => m.Id, StringComparer.Ordinal))
            {
                yield return new Evento("mensaje", m);
            }
            await foreach (var e in canal.Reader.ReadAllAsync(ct)) yield return e;
        }
        finally
        {
            _suscriptores.TryRemove(canal, out _);
        }
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        using var reloj = new PeriodicTimer(Intervalo);
        while (await reloj.WaitForNextTickAsync(ct))
        {
            if (_suscriptores.IsEmpty) continue;
            try
            {
                var cliente = conexion.Cliente();
                Emitir(new Evento("cola", await cliente.EstadoColaAsync(ct)));

                // Un ENVIADO aún puede pasar a ENTREGADO: se sigue mirando un rato.
                var pendientes = _mensajes.Values
                    .Where(m => !m.Terminado || (m.Estado == EstadoMensaje.Enviado && DateTimeOffset.UtcNow - (m.EnviadoEn ?? m.AdmitidoEn) < TimeSpan.FromMinutes(5)))
                    .Take(50)
                    .ToList();
                foreach (var previo in pendientes)
                {
                    var actual = await cliente.ConsultarAsync(previo.Id, ct);
                    if (actual != previo with { Repetido = actual.Repetido })
                    {
                        _mensajes[actual.Id] = actual;
                        Emitir(new Evento("mensaje", actual));
                    }
                }
            }
            catch (InvalidOperationException)
            {
                // Sin conexión configurada todavía.
            }
            catch (Exception e) when (e is ApiToSmsException or ConexionException)
            {
                log.LogWarning("Sondeo: {Error}", e.Message);
                Emitir(new Evento("error", new { error = e.Message }));
            }
        }
    }

    private void Emitir(Evento e)
    {
        foreach (var canal in _suscriptores.Keys) canal.Writer.TryWrite(e);
    }
}
