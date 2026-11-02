// APItoSMS — Biblioteca cliente
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

using System.Text.Json.Serialization;

namespace Capicua.ApiToSms.Cliente;

/// <summary>
/// Estado de un mensaje en el teléfono. El nombre que viaja en el JSON es el de la API
/// (<c>EN_COLA</c>, <c>ENVIADO</c>…).
/// </summary>
/// <remarks>
/// <code>
/// EN_COLA ──► ENVIANDO ──► ENVIADO ──► ENTREGADO
///    │            │           └──────► NO_ENTREGADO
///    │            ├─► EN_COLA   (error transitorio, con espera)
///    │            └─► FALLIDO
///    ├─► CANCELADO
///    └─► CADUCADO
/// </code>
/// </remarks>
[JsonConverter(typeof(JsonStringEnumConverter<EstadoMensaje>))]
public enum EstadoMensaje
{
    /// <summary>Admitido y persistido; espera su turno.</summary>
    [JsonStringEnumMemberName("EN_COLA")] EnCola,

    /// <summary>Entregado a la radio; se espera la confirmación del operador.</summary>
    [JsonStringEnumMemberName("ENVIANDO")] Enviando,

    /// <summary>
    /// El operador confirmó la salida. <b>Puede ser el estado final</b>: hay operadores que no
    /// mandan informes de entrega (Telcel por VoWiFi, por ejemplo), y entonces nunca pasa a
    /// <see cref="Entregado"/>.
    /// </summary>
    [JsonStringEnumMemberName("ENVIADO")] Enviado,

    /// <summary>Llegó el informe de entrega del teléfono de destino.</summary>
    [JsonStringEnumMemberName("ENTREGADO")] Entregado,

    /// <summary>El informe de entrega dice que no se entregó. El SMS sí salió.</summary>
    [JsonStringEnumMemberName("NO_ENTREGADO")] NoEntregado,

    /// <summary>No salió: error permanente o intentos agotados. Ver <see cref="Mensaje.UltimoError"/>.</summary>
    [JsonStringEnumMemberName("FALLIDO")] Fallido,

    /// <summary>Cancelado antes de salir.</summary>
    [JsonStringEnumMemberName("CANCELADO")] Cancelado,

    /// <summary>Pasó su <c>caducaEn</c> sin haber salido.</summary>
    [JsonStringEnumMemberName("CADUCADO")] Caducado,
}

/// <summary>Un mensaje que se quiere enviar.</summary>
/// <param name="Destino">
/// Número de destino. Mejor en E.164 (<c>+523311298770</c>); sin prefijo, el teléfono le pone el
/// del país configurado en sus ajustes.
/// </param>
/// <param name="Texto">Hasta 1600 caracteres. Se parte en tantos SMS como haga falta.</param>
/// <param name="Referencia">
/// Texto libre del cliente, hasta 100 caracteres (un número de pedido, por ejemplo). Se puede
/// filtrar por él en <see cref="ClienteApiToSms.ListarAsync"/>.
/// </param>
/// <param name="CaducaEn">Si para entonces no ha salido, ya no sale: queda <c>CADUCADO</c>.</param>
public sealed record NuevoMensaje(
    string Destino,
    string Texto,
    string? Referencia = null,
    DateTimeOffset? CaducaEn = null);

/// <summary>Un mensaje tal como lo ve el teléfono.</summary>
public sealed record Mensaje
{
    /// <summary>Identificador asignado por el teléfono.</summary>
    public required string Id { get; init; }

    /// <summary>Destino normalizado a E.164.</summary>
    public required string Destino { get; init; }

    /// <summary>El texto, o null si la retención del teléfono ya lo ha purgado.</summary>
    public string? Texto { get; init; }

    /// <summary>La referencia que mandó el cliente.</summary>
    public string? Referencia { get; init; }

    /// <summary>Estado actual.</summary>
    public required EstadoMensaje Estado { get; init; }

    /// <summary>Intentos de envío hechos.</summary>
    public int Intentos { get; init; }

    /// <summary>El último error del operador o del teléfono, si lo hubo.</summary>
    public string? UltimoError { get; init; }

    /// <summary>Número de SMS en que se parte el texto.</summary>
    public int Partes { get; init; }

    /// <summary>Cuándo lo admitió el teléfono.</summary>
    public DateTimeOffset AdmitidoEn { get; init; }

    /// <summary>Tras un error transitorio, cuándo se volverá a intentar.</summary>
    public DateTimeOffset? ProximoIntentoEn { get; init; }

    /// <summary>Cuándo confirmó el operador la salida.</summary>
    public DateTimeOffset? EnviadoEn { get; init; }

    /// <summary>Cuándo llegó el informe de entrega.</summary>
    public DateTimeOffset? EntregadoEn { get; init; }

    /// <summary>Caducidad pedida por el cliente.</summary>
    public DateTimeOffset? CaducaEn { get; init; }

    /// <summary>Solo en <see cref="EstadoMensaje.EnCola"/>: 1 es el siguiente en salir.</summary>
    public int? PosicionEnCola { get; init; }

    /// <summary>
    /// true si la petición ya se había hecho con la misma <c>Idempotency-Key</c> y el teléfono
    /// ha devuelto el mensaje que creó entonces, sin crear otro.
    /// </summary>
    [JsonIgnore]
    public bool Repetido { get; init; }

    /// <summary>
    /// true si la cola ya no va a hacer nada más con él. <see cref="EstadoMensaje.Enviado"/>
    /// cuenta como terminado aunque aún pueda pasar a entregado.
    /// </summary>
    [JsonIgnore]
    public bool Terminado => Estado is not (EstadoMensaje.EnCola or EstadoMensaje.Enviando);

    /// <summary>true si el SMS salió del teléfono, se sepa o no si llegó.</summary>
    [JsonIgnore]
    public bool Salio => Estado is EstadoMensaje.Enviado or EstadoMensaje.Entregado or EstadoMensaje.NoEntregado;
}

/// <summary>Una página de <see cref="ClienteApiToSms.ListarAsync"/>.</summary>
/// <param name="Mensajes">Los mensajes, del más reciente al más antiguo.</param>
/// <param name="Siguiente">Cursor para la página siguiente, o null si no hay más.</param>
public sealed record PaginaDeMensajes(IReadOnlyList<Mensaje> Mensajes, string? Siguiente);

/// <summary>Filtro de <see cref="ClienteApiToSms.ListarAsync"/>. Todo es opcional.</summary>
/// <param name="Estado">Solo los mensajes en este estado.</param>
/// <param name="Referencia">Solo los de esta referencia exacta.</param>
/// <param name="Desde">Solo los admitidos desde este instante.</param>
/// <param name="Limite">Tamaño de página, de 1 a 200; 50 si no se dice.</param>
/// <param name="Cursor">El <see cref="PaginaDeMensajes.Siguiente"/> de la página anterior.</param>
public sealed record FiltroDeMensajes(
    EstadoMensaje? Estado = null,
    string? Referencia = null,
    DateTimeOffset? Desde = null,
    int? Limite = null,
    string? Cursor = null);

/// <summary>Una cuota diaria, contada en partes SMS.</summary>
/// <param name="Limite">0: sin límite.</param>
/// <param name="Usadas">Partes admitidas hoy (hora del teléfono).</param>
public sealed record Cuota(int Limite, int Usadas);

/// <summary>El estado de la cola del teléfono.</summary>
public sealed record EstadoCola
{
    /// <summary>true si la cola está pausada: admite mensajes pero no envía.</summary>
    public bool Pausada { get; init; }

    /// <summary>Mensajes esperando turno.</summary>
    public int EnCola { get; init; }

    /// <summary>Mensajes en vuelo (0 o 1: la cola es en serie).</summary>
    public int Enviando { get; init; }

    /// <summary>Pausa configurada entre un SMS y el siguiente.</summary>
    public int PausaEntreMensajesSegundos { get; init; }

    /// <summary>
    /// Mensajes de hoy por estado (nombre de la API: <c>ENVIADO</c>…). De la clave que pregunta,
    /// o de todas si tiene el ámbito ADMIN.
    /// </summary>
    public IReadOnlyDictionary<string, int> Hoy { get; init; } = new Dictionary<string, int>();

    /// <summary>Cuota diaria del teléfono.</summary>
    public required Cuota CuotaGlobal { get; init; }

    /// <summary>Cuota diaria de la clave que pregunta.</summary>
    public Cuota? CuotaClave { get; init; }
}

/// <summary>Respuesta de <c>GET /api/v1/salud</c>.</summary>
/// <param name="Estado">"ok".</param>
/// <param name="Version">Versión de la app del teléfono.</param>
public sealed record Salud(string Estado, string Version);

/// <summary>Un error en un campo de la petición.</summary>
/// <param name="Indice">Posición en el lote; null en una petición de un solo mensaje.</param>
/// <param name="Campo">Nombre del campo (<c>destino</c>, <c>texto</c>…).</param>
/// <param name="Motivo">Qué le pasa, en español.</param>
public sealed record ErrorDeCampo(int? Indice, string Campo, string Motivo);

/// <summary>Un error de la API en formato RFC 9457.</summary>
internal sealed record Problema(
    string? Type,
    string? Title,
    int Status,
    string? Detail,
    string? Codigo,
    IReadOnlyList<ErrorDeCampo>? Errores);

internal sealed record PeticionLote(IReadOnlyList<NuevoMensaje> Mensajes);

internal sealed record RespuestaLote(IReadOnlyList<Mensaje> Mensajes);
