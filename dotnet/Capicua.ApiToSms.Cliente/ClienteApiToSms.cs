// APItoSMS — Biblioteca cliente
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Net.Security;
using System.Runtime.CompilerServices;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;

namespace Capicua.ApiToSms.Cliente;

/// <summary>
/// Cliente de la API de un teléfono APItoSMS.
/// </summary>
/// <remarks>
/// <para>
/// <b>El envío es asíncrono.</b> <see cref="EnviarAsync"/> vuelve en cuanto el teléfono ha
/// admitido y guardado el mensaje (<c>202</c>), no cuando sale: el teléfono manda los SMS de uno
/// en uno. El resultado se consulta después, con <see cref="ConsultarAsync"/> o
/// <see cref="EsperarResultadoAsync"/>.
/// </para>
/// <para>
/// <b>Reintentos.</b> Cada envío lleva una <c>Idempotency-Key</c> (la que se pase o una nueva), así
/// que reintentarlo tras un corte no duplica el SMS: el teléfono devuelve el mensaje que ya creó.
/// Para que eso valga también entre reinicios del proceso que llama, guarda la clave junto al
/// trabajo y pásala explícitamente.
/// </para>
/// <para>Es seguro usar una instancia desde varios hilos.</para>
/// </remarks>
public sealed class ClienteApiToSms : IDisposable
{
    private const string Base = "api/v1/";

    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
        // El JSON va al teléfono, no a una página: no hace falta escapar '+' ni las tildes, y
        // así el cuerpo se lee tal cual en el registro.
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    private static readonly string AgenteDeUsuario =
        $"Capicua.ApiToSms.Cliente/{typeof(ClienteApiToSms).Assembly.GetName().Version?.ToString(3)}";

    private readonly HttpClient _http;
    private readonly OpcionesApiToSms _opciones;
    private readonly bool _propio;

    /// <summary>
    /// Crea el cliente sobre un <see cref="HttpClient"/> ya configurado. Su manejador tiene que
    /// hacer el pinning: créalo con <see cref="CrearManejador"/>, o usa
    /// <see cref="ServiceCollectionExtensions.AddApiToSms"/>, que lo hace solo.
    /// </summary>
    public ClienteApiToSms(HttpClient http, OpcionesApiToSms opciones)
    {
        ArgumentNullException.ThrowIfNull(http);
        ArgumentNullException.ThrowIfNull(opciones);
        opciones.Validar();
        _http = http;
        _opciones = opciones;
        _http.BaseAddress ??= ConBarraFinal(opciones.Url!);
    }

    private ClienteApiToSms(HttpClient http, OpcionesApiToSms opciones, bool propio) : this(http, opciones)
    {
        _propio = propio;
    }

    /// <summary>
    /// Crea un cliente con su propio <see cref="HttpClient"/>. Para una aplicación con inyección de
    /// dependencias es mejor <see cref="ServiceCollectionExtensions.AddApiToSms"/>.
    /// </summary>
    public static ClienteApiToSms Crear(OpcionesApiToSms opciones)
    {
        ArgumentNullException.ThrowIfNull(opciones);
        opciones.Validar();
        var http = new HttpClient(CrearManejador(opciones), disposeHandler: true)
        {
            BaseAddress = ConBarraFinal(opciones.Url!),
            Timeout = Timeout.InfiniteTimeSpan, // el tiempo lo controla cada petición
        };
        return new ClienteApiToSms(http, opciones, propio: true);
    }

    /// <summary>
    /// El manejador HTTP con el certificado fijado por <see cref="OpcionesApiToSms.Huella"/>. Sin
    /// huella, valida el certificado como lo haría cualquier cliente.
    /// </summary>
    public static SocketsHttpHandler CrearManejador(OpcionesApiToSms opciones)
    {
        ArgumentNullException.ThrowIfNull(opciones);
        var huella = opciones.Huella is null ? null : HuellaFijada.Leer(opciones.Huella);
        var manejador = new SocketsHttpHandler
        {
            // Un teléfono que cambia de IP por DHCP no debe quedarse con conexiones viejas.
            PooledConnectionLifetime = TimeSpan.FromMinutes(5),
            ConnectTimeout = TimeSpan.FromSeconds(10),
            AutomaticDecompression = DecompressionMethods.None,
            SslOptions = new SslClientAuthenticationOptions
            {
                EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13,
            },
        };
        if (huella is not null)
        {
            manejador.SslOptions.RemoteCertificateValidationCallback = (_, certificado, _, _) =>
                certificado is not null && huella.Coincide(certificado as X509Certificate2 ?? new X509Certificate2(certificado));
        }
        return manejador;
    }

    // ── Envío ───────────────────────────────────────────────────────────

    /// <summary>
    /// Deja un mensaje en la cola del teléfono. Vuelve cuando está admitido, no cuando sale.
    /// </summary>
    /// <param name="mensaje">El mensaje.</param>
    /// <param name="idempotencyKey">
    /// Hasta 100 caracteres: letras, dígitos y <c>- _ : .</c>. Si es null se genera una, que solo
    /// protege los reintentos de esta llamada.
    /// </param>
    /// <param name="ct">Cancelación.</param>
    /// <returns>
    /// El mensaje admitido. <see cref="Mensaje.Repetido"/> es true si la clave ya se había usado
    /// con el mismo contenido.
    /// </returns>
    /// <exception cref="ValidacionException">Destino, texto o caducidad no válidos.</exception>
    /// <exception cref="ConflictoException">La clave de idempotencia ya se usó con otro contenido.</exception>
    /// <exception cref="LimiteExcedidoException">Cuota diaria agotada.</exception>
    public async Task<Mensaje> EnviarAsync(NuevoMensaje mensaje, string? idempotencyKey = null, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(mensaje);
        var clave = idempotencyKey ?? NuevaClaveDeIdempotencia();
        using var respuesta = await PedirAsync(() =>
        {
            var p = new HttpRequestMessage(HttpMethod.Post, Base + "mensajes") { Content = Cuerpo(mensaje) };
            p.Headers.Add("Idempotency-Key", clave);
            return p;
        }, ct).ConfigureAwait(false);
        var m = await LeerAsync<Mensaje>(respuesta, ct).ConfigureAwait(false);
        return m with { Repetido = EsRepetida(respuesta) };
    }

    /// <summary>
    /// Deja varios mensajes en la cola, en ese orden. <b>Todo o nada</b>: si uno no es válido no se
    /// admite ninguno, y <see cref="ApiToSmsException.Errores"/> dice cuál con su índice.
    /// </summary>
    /// <param name="mensajes">De 1 al máximo por lote configurado en el teléfono (100 por defecto).</param>
    /// <param name="idempotencyKey">Como en <see cref="EnviarAsync"/>; vale para el lote entero.</param>
    /// <param name="ct">Cancelación.</param>
    /// <returns>Los mensajes admitidos, en el mismo orden.</returns>
    public async Task<IReadOnlyList<Mensaje>> EnviarLoteAsync(
        IReadOnlyList<NuevoMensaje> mensajes, string? idempotencyKey = null, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(mensajes);
        var clave = idempotencyKey ?? NuevaClaveDeIdempotencia();
        using var respuesta = await PedirAsync(() =>
        {
            var p = new HttpRequestMessage(HttpMethod.Post, Base + "lotes")
            {
                Content = Cuerpo(new PeticionLote(mensajes)),
            };
            p.Headers.Add("Idempotency-Key", clave);
            return p;
        }, ct).ConfigureAwait(false);
        var lote = await LeerAsync<RespuestaLote>(respuesta, ct).ConfigureAwait(false);
        var repetida = EsRepetida(respuesta);
        return lote.Mensajes.Select(m => m with { Repetido = repetida }).ToList();
    }

    // ── Consulta ────────────────────────────────────────────────────────

    /// <summary>El estado actual de un mensaje.</summary>
    /// <exception cref="NoEncontradoException">No existe, o es de otra clave.</exception>
    public async Task<Mensaje> ConsultarAsync(string id, CancellationToken ct = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(id);
        using var respuesta = await PedirAsync(
            () => new HttpRequestMessage(HttpMethod.Get, Base + "mensajes/" + Uri.EscapeDataString(id)), ct).ConfigureAwait(false);
        return await LeerAsync<Mensaje>(respuesta, ct).ConfigureAwait(false);
    }

    /// <summary>
    /// Una página de mensajes, del más reciente al más antiguo. Una clave sin ámbito ADMIN solo ve
    /// los suyos.
    /// </summary>
    public async Task<PaginaDeMensajes> ListarAsync(FiltroDeMensajes? filtro = null, CancellationToken ct = default)
    {
        var ruta = Base + "mensajes" + Consulta(filtro ?? new FiltroDeMensajes());
        using var respuesta = await PedirAsync(() => new HttpRequestMessage(HttpMethod.Get, ruta), ct).ConfigureAwait(false);
        return await LeerAsync<PaginaDeMensajes>(respuesta, ct).ConfigureAwait(false);
    }

    /// <summary>Todos los mensajes que cumplen el filtro, pidiendo las páginas según hacen falta.</summary>
    public async IAsyncEnumerable<Mensaje> ListarTodosAsync(
        FiltroDeMensajes? filtro = null, [EnumeratorCancellation] CancellationToken ct = default)
    {
        var actual = filtro ?? new FiltroDeMensajes();
        while (true)
        {
            var pagina = await ListarAsync(actual, ct).ConfigureAwait(false);
            foreach (var m in pagina.Mensajes) yield return m;
            if (pagina.Siguiente is null) yield break;
            actual = actual with { Cursor = pagina.Siguiente };
        }
    }

    /// <summary>
    /// Consulta el mensaje hasta que termina y lo devuelve. "Termina" es salir de
    /// <c>EN_COLA</c>/<c>ENVIANDO</c>; con <see cref="OpcionesDeEspera.EsperarInformeDeEntrega"/>,
    /// además, esperar un rato al informe de entrega.
    /// </summary>
    /// <exception cref="EsperaAgotadaException">Pasó el plazo sin que terminara.</exception>
    public async Task<Mensaje> EsperarResultadoAsync(string id, OpcionesDeEspera? opciones = null, CancellationToken ct = default)
    {
        var o = opciones ?? new OpcionesDeEspera();
        var limite = DateTimeOffset.UtcNow + o.Plazo;
        var espera = o.IntervaloInicial;

        while (true)
        {
            var m = await ConsultarAsync(id, ct).ConfigureAwait(false);
            var ahora = DateTimeOffset.UtcNow;
            var aguardandoInforme = o.EsperarInformeDeEntrega
                && m.Estado == EstadoMensaje.Enviado
                && (m.EnviadoEn is null || ahora - m.EnviadoEn.Value < o.PlazoInforme);
            if (m.Terminado && !aguardandoInforme) return m;
            if (ahora >= limite)
            {
                // Agotar el plazo esperando un informe que no llega no es un fallo: el SMS salió.
                if (m.Terminado) return m;
                throw new EsperaAgotadaException(m);
            }

            var resto = limite - ahora;
            await Task.Delay(espera < resto ? espera : resto, ct).ConfigureAwait(false);
            espera = TimeSpan.FromTicks(Math.Min((long)(espera.Ticks * 1.5), o.IntervaloMaximo.Ticks));
        }
    }

    /// <summary>
    /// Cancela un mensaje que sigue en la cola. Cancelar uno ya cancelado no es un error.
    /// </summary>
    /// <exception cref="ConflictoException">Ya salió de la cola (<c>no-cancelable</c>).</exception>
    public async Task<Mensaje> CancelarAsync(string id, CancellationToken ct = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(id);
        try
        {
            using var respuesta = await PedirAsync(
                () => new HttpRequestMessage(HttpMethod.Delete, Base + "mensajes/" + Uri.EscapeDataString(id)), ct).ConfigureAwait(false);
            return await LeerAsync<Mensaje>(respuesta, ct).ConfigureAwait(false);
        }
        catch (ConflictoException e) when (e.Codigo == "no-cancelable")
        {
            // Si un reintento llega después de que el primer DELETE sí entrara, el teléfono dice
            // que ya no está EN_COLA. Lo está porque lo cancelamos nosotros.
            var actual = await ConsultarAsync(id, ct).ConfigureAwait(false);
            if (actual.Estado == EstadoMensaje.Cancelado) return actual;
            throw;
        }
    }

    // ── Cola ────────────────────────────────────────────────────────────

    /// <summary>El estado de la cola y de las cuotas.</summary>
    public async Task<EstadoCola> EstadoColaAsync(CancellationToken ct = default)
    {
        using var respuesta = await PedirAsync(() => new HttpRequestMessage(HttpMethod.Get, Base + "cola"), ct).ConfigureAwait(false);
        return await LeerAsync<EstadoCola>(respuesta, ct).ConfigureAwait(false);
    }

    /// <summary>
    /// Pausa la cola: el teléfono sigue admitiendo mensajes pero no envía. Necesita el ámbito ADMIN.
    /// </summary>
    public Task<EstadoCola> PausarAsync(CancellationToken ct = default) => CambiarPausaAsync("cola/pausa", ct);

    /// <summary>Reanuda la cola. Necesita el ámbito ADMIN.</summary>
    public Task<EstadoCola> ReanudarAsync(CancellationToken ct = default) => CambiarPausaAsync("cola/reanudar", ct);

    /// <summary>Comprueba que el teléfono contesta. No necesita clave.</summary>
    public async Task<Salud> SaludAsync(CancellationToken ct = default)
    {
        using var respuesta = await PedirAsync(() => new HttpRequestMessage(HttpMethod.Get, Base + "salud"), ct).ConfigureAwait(false);
        return await LeerAsync<Salud>(respuesta, ct).ConfigureAwait(false);
    }

    private async Task<EstadoCola> CambiarPausaAsync(string ruta, CancellationToken ct)
    {
        using var respuesta = await PedirAsync(() => new HttpRequestMessage(HttpMethod.Post, Base + ruta), ct).ConfigureAwait(false);
        return await LeerAsync<EstadoCola>(respuesta, ct).ConfigureAwait(false);
    }

    // ── Transporte ──────────────────────────────────────────────────────

    /// <summary>
    /// Manda la petición con los reintentos que tocan y devuelve una respuesta correcta, o lanza la
    /// excepción del error. <paramref name="crear"/> se llama en cada intento: un
    /// <see cref="HttpRequestMessage"/> no se puede mandar dos veces.
    /// </summary>
    private async Task<HttpResponseMessage> PedirAsync(Func<HttpRequestMessage> crear, CancellationToken ct)
    {
        for (var intento = 1; ; intento++)
        {
            var quedanIntentos = intento <= _opciones.Reintentos;
            HttpResponseMessage respuesta;
            using (var porTiempo = CancellationTokenSource.CreateLinkedTokenSource(ct))
            {
                porTiempo.CancelAfter(_opciones.TiempoMaximoPeticion);
                using var peticion = crear();
                peticion.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _opciones.Clave);
                peticion.Headers.UserAgent.ParseAdd(AgenteDeUsuario);
                peticion.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
                try
                {
                    respuesta = await _http.SendAsync(peticion, HttpCompletionOption.ResponseContentRead, porTiempo.Token)
                        .ConfigureAwait(false);
                }
                catch (HttpRequestException e) when (EsDeCertificado(e))
                {
                    throw new CertificadoRechazadoException(
                        "El teléfono no presenta el certificado esperado: la huella no coincide, o falló el saludo TLS. " +
                        "Si se regeneró el certificado en el teléfono, copia la huella nueva del Panel.", e);
                }
                catch (Exception e) when (e is HttpRequestException ||
                                          (e is OperationCanceledException && !ct.IsCancellationRequested))
                {
                    if (!quedanIntentos)
                    {
                        throw new ConexionException(
                            $"No se pudo hablar con el teléfono en {_http.BaseAddress} tras {intento} intento(s): {e.Message}", e);
                    }
                    await Task.Delay(_opciones.EsperaEntreReintentos * intento, ct).ConfigureAwait(false);
                    continue;
                }
            }

            if (respuesta.IsSuccessStatusCode) return respuesta;

            var espera = EsperaParaReintentar(respuesta, intento);
            if (espera is not null && quedanIntentos)
            {
                respuesta.Dispose();
                await Task.Delay(espera.Value, ct).ConfigureAwait(false);
                continue;
            }

            using (respuesta)
            {
                throw await ErrorAsync(respuesta, ct).ConfigureAwait(false);
            }
        }
    }

    /// <summary>Cuánto esperar antes de repetir esta respuesta, o null si no se repite.</summary>
    private TimeSpan? EsperaParaReintentar(HttpResponseMessage r, int intento)
    {
        switch ((int)r.StatusCode)
        {
            case 500 or 502 or 503 or 504:
                return _opciones.EsperaEntreReintentos * intento;
            case 429:
                var pide = RetryAfter(r) ?? _opciones.EsperaEntreReintentos * intento;
                return pide <= _opciones.EsperaMaximaPorLimite ? pide : null;
            default:
                return null;
        }
    }

    private static TimeSpan? RetryAfter(HttpResponseMessage r)
    {
        var ra = r.Headers.RetryAfter;
        if (ra?.Delta is { } delta) return delta;
        if (ra?.Date is { } fecha) return fecha - DateTimeOffset.UtcNow is var d && d > TimeSpan.Zero ? d : TimeSpan.Zero;
        return null;
    }

    private static async Task<Exception> ErrorAsync(HttpResponseMessage r, CancellationToken ct)
    {
        Problema? p = null;
        try
        {
            p = await r.Content.ReadFromJsonAsync<Problema>(Json, ct).ConfigureAwait(false);
        }
        catch (JsonException) { }
        catch (NotSupportedException) { }

        var status = (int)r.StatusCode;
        var codigo = p?.Codigo ?? $"http-{status}";
        var texto = p?.Detail ?? p?.Title ?? r.ReasonPhrase ?? $"HTTP {status}";
        return status switch
        {
            401 => new NoAutenticadoException(texto),
            403 => new SinPermisoException(codigo, texto),
            404 => new NoEncontradoException(texto),
            409 => new ConflictoException(codigo, texto),
            422 => new ValidacionException(texto, p?.Errores ?? []),
            429 => new LimiteExcedidoException(codigo, texto, RetryAfter(r)),
            _ => new ApiToSmsException(status, codigo, texto, p?.Errores),
        };
    }

    private static async Task<T> LeerAsync<T>(HttpResponseMessage r, CancellationToken ct) =>
        await r.Content.ReadFromJsonAsync<T>(Json, ct).ConfigureAwait(false)
        ?? throw new ApiToSmsException((int)r.StatusCode, "respuesta-vacia", "El teléfono devolvió una respuesta vacía.");

    /// <summary>
    /// El cuerpo JSON ya serializado, para que viaje con <c>Content-Length</c>. El servidor del
    /// teléfono no admite <c>Transfer-Encoding: chunked</c> (contesta 501), que es lo que manda
    /// <see cref="JsonContent"/> al serializar sobre la marcha.
    /// </summary>
    private static ByteArrayContent Cuerpo<T>(T valor)
    {
        var contenido = new ByteArrayContent(JsonSerializer.SerializeToUtf8Bytes(valor, Json));
        contenido.Headers.ContentType = new MediaTypeHeaderValue("application/json") { CharSet = "utf-8" };
        return contenido;
    }

    private static bool EsRepetida(HttpResponseMessage r) =>
        r.Headers.TryGetValues("Idempotent-Replayed", out var v) && v.Contains("true", StringComparer.OrdinalIgnoreCase);

    private static bool EsDeCertificado(HttpRequestException e)
    {
        for (Exception? x = e; x is not null; x = x.InnerException)
        {
            if (x is AuthenticationException) return true;
        }
        return false;
    }

    internal static string Consulta(FiltroDeMensajes f)
    {
        var partes = new List<string>();
        if (f.Estado is { } estado) partes.Add("estado=" + JsonSerializer.Serialize(estado, Json).Trim('"'));
        if (f.Referencia is { } referencia) partes.Add("referencia=" + Uri.EscapeDataString(referencia));
        if (f.Desde is { } desde) partes.Add("desde=" + Uri.EscapeDataString(desde.UtcDateTime.ToString("yyyy-MM-ddTHH:mm:ss.fffZ")));
        if (f.Limite is { } limite) partes.Add("limite=" + limite);
        if (f.Cursor is { } cursor) partes.Add("cursor=" + Uri.EscapeDataString(cursor));
        return partes.Count == 0 ? "" : "?" + string.Join('&', partes);
    }

    /// <summary>Una clave de idempotencia nueva, válida para el teléfono.</summary>
    public static string NuevaClaveDeIdempotencia() => Guid.NewGuid().ToString("N");

    private static Uri ConBarraFinal(Uri url) =>
        url.AbsoluteUri.EndsWith('/') ? url : new Uri(url.AbsoluteUri + "/");

    /// <inheritdoc />
    public void Dispose()
    {
        if (_propio) _http.Dispose();
    }
}
