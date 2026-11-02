// APItoSMS — Tests de la biblioteca cliente
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

using System.Net;
using System.Text;

namespace ApiToSms.Cliente.Tests;

/// <summary>
/// Un teléfono de mentira: contesta, en orden, las respuestas que se le encolan, y guarda cada
/// petición que recibe (con el cuerpo ya leído, porque el cliente la desecha al terminar).
/// </summary>
internal sealed class ManejadorFalso : HttpMessageHandler
{
    private readonly Queue<Func<HttpResponseMessage>> _respuestas = new();

    public List<Recibida> Recibidas { get; } = [];

    /// <param name="Longitud">El <c>Content-Length</c> que declara el contenido antes de leerlo; null si iría chunked.</param>
    public sealed record Recibida(HttpMethod Metodo, string Ruta, string? Cuerpo, long? Longitud, Dictionary<string, string> Cabeceras);

    public ManejadorFalso Json(HttpStatusCode estado, string json, params (string, string)[] cabeceras)
    {
        _respuestas.Enqueue(() =>
        {
            var r = new HttpResponseMessage(estado) { Content = new StringContent(json, Encoding.UTF8, "application/json") };
            foreach (var (k, v) in cabeceras) r.Headers.TryAddWithoutValidation(k, v);
            return r;
        });
        return this;
    }

    public ManejadorFalso Problema(int estado, string codigo, string detalle = "detalle", string? errores = null, params (string, string)[] cabeceras)
    {
        var json = $$"""{"type":"x#{{codigo}}","title":"t","status":{{estado}},"detail":"{{detalle}}","codigo":"{{codigo}}"{{(errores is null ? "" : ",\"errores\":" + errores)}}}""";
        _respuestas.Enqueue(() =>
        {
            var r = new HttpResponseMessage((HttpStatusCode)estado)
            {
                Content = new StringContent(json, Encoding.UTF8, "application/problem+json"),
            };
            foreach (var (k, v) in cabeceras) r.Headers.TryAddWithoutValidation(k, v);
            return r;
        });
        return this;
    }

    public ManejadorFalso FalloDeRed()
    {
        _respuestas.Enqueue(() => throw new HttpRequestException("conexión rechazada"));
        return this;
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage peticion, CancellationToken ct)
    {
        var longitud = peticion.Content?.Headers.ContentLength;
        var cuerpo = peticion.Content is null ? null : await peticion.Content.ReadAsStringAsync(ct);
        Recibidas.Add(new Recibida(
            peticion.Method,
            peticion.RequestUri!.PathAndQuery,
            cuerpo,
            longitud,
            peticion.Headers.ToDictionary(h => h.Key, h => string.Join(",", h.Value))));
        if (_respuestas.Count == 0) throw new InvalidOperationException("El test no esperaba más peticiones.");
        return _respuestas.Dequeue()();
    }

    public static string Mensaje(string id = "m1", string estado = "EN_COLA", string extra = "") =>
        $$"""{"id":"{{id}}","destino":"+523311298770","texto":"hola","estado":"{{estado}}","intentos":0,"partes":1,"admitidoEn":"2026-11-01T18:30:00Z"{{extra}}}""";
}
