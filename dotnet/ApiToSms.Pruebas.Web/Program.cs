// APItoSMS — Web de pruebas
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

using System.Text.Json;
using ApiToSms.Cliente;
using ApiToSms.Pruebas.Web;

var builder = WebApplication.CreateBuilder(args);

// Solo en este equipo: la web manda SMS de verdad con la clave guardada en conexion.json, y no
// tiene autenticación propia.
builder.WebHost.UseUrls("http://127.0.0.1:5080");
builder.Services.AddSingleton<Conexion>();
builder.Services.AddSingleton<Seguimiento>();
builder.Services.AddHostedService(sp => sp.GetRequiredService<Seguimiento>());
builder.Services.ConfigureHttpJsonOptions(o =>
{
    o.SerializerOptions.PropertyNamingPolicy = JsonNamingPolicy.CamelCase;
    o.SerializerOptions.Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping;
});

var app = builder.Build();
app.UseDefaultFiles();
app.UseStaticFiles();

// ── Conexión ────────────────────────────────────────────────────────────

app.MapGet("/conexion", (Conexion c) => c.Publica());

app.MapPost("/conexion", (DatosConexion datos, Conexion c, CancellationToken ct) => Llamar(async () =>
{
    c.Guardar(datos);
    var salud = await c.Cliente().SaludAsync(ct);
    return new { salud.Version };
}));

app.MapGet("/salud", (Conexion c, CancellationToken ct) => Llamar(() => c.Cliente().SaludAsync(ct)));

// ── Mensajes ────────────────────────────────────────────────────────────

app.MapPost("/enviar", (PeticionEnvio p, Conexion c, Seguimiento s, CancellationToken ct) => Llamar(async () =>
{
    var m = await c.Cliente().EnviarAsync(new NuevoMensaje(p.Destino, p.Texto, Vacio(p.Referencia)), ct: ct);
    s.Seguir([m]);
    return m;
}));

app.MapPost("/lote", (PeticionLote p, Conexion c, Seguimiento s, CancellationToken ct) => Llamar(async () =>
{
    var destinos = p.Destinos.Select(d => d.Trim()).Where(d => d.Length > 0).ToList();
    if (destinos.Count == 0) throw new ArgumentException("Hace falta al menos un destino.");
    if (p.Cantidad is < 1 or > 1000) throw new ArgumentException("La cantidad va de 1 a 1000.");

    // {n} es el número de mensaje dentro del lote, para distinguirlos al recibirlos.
    var prefijo = DateTime.Now.ToString("HHmmss");
    var mensajes = Enumerable.Range(1, p.Cantidad)
        .Select(n => new NuevoMensaje(destinos[(n - 1) % destinos.Count], p.Texto.Replace("{n}", n.ToString()), $"web-{prefijo}-{n}"))
        .ToList();

    // El teléfono limita el tamaño de lote (100 por defecto): se parte en trozos de 50.
    var admitidos = new List<Mensaje>();
    foreach (var trozo in mensajes.Chunk(50))
    {
        var lote = await c.Cliente().EnviarLoteAsync(trozo, ct: ct);
        s.Seguir(lote);
        admitidos.AddRange(lote);
    }
    return admitidos;
}));

app.MapGet("/mensajes", (Conexion c, string? estado, string? referencia, string? cursor, CancellationToken ct) => Llamar(() =>
    c.Cliente().ListarAsync(new FiltroDeMensajes(
        Estado: estado is null ? null : JsonSerializer.Deserialize<EstadoMensaje>($"\"{estado}\""),
        Referencia: Vacio(referencia),
        Limite: 50,
        Cursor: Vacio(cursor)), ct)));

app.MapDelete("/mensajes/{id}", (string id, Conexion c, Seguimiento s, CancellationToken ct) => Llamar(async () =>
{
    var m = await c.Cliente().CancelarAsync(id, ct);
    s.Seguir([m]);
    return m;
}));

app.MapPost("/seguimiento/limpiar", (Seguimiento s) =>
{
    s.Limpiar();
    return Results.NoContent();
});

// ── Cola ────────────────────────────────────────────────────────────────

app.MapGet("/cola", (Conexion c, CancellationToken ct) => Llamar(() => c.Cliente().EstadoColaAsync(ct)));
app.MapPost("/cola/pausa", (Conexion c, CancellationToken ct) => Llamar(() => c.Cliente().PausarAsync(ct)));
app.MapPost("/cola/reanudar", (Conexion c, CancellationToken ct) => Llamar(() => c.Cliente().ReanudarAsync(ct)));

// ── Eventos en vivo ─────────────────────────────────────────────────────

app.MapGet("/eventos", async (HttpContext http, Seguimiento s, CancellationToken ct) =>
{
    http.Response.Headers.ContentType = "text/event-stream";
    http.Response.Headers.CacheControl = "no-cache";
    var opciones = new JsonSerializerOptions(JsonSerializerDefaults.Web)
    {
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };
    await foreach (var evento in s.Suscribir(ct))
    {
        await http.Response.WriteAsync($"event: {evento.Tipo}\ndata: {JsonSerializer.Serialize(evento.Datos, opciones)}\n\n", ct);
        await http.Response.Body.FlushAsync(ct);
    }
});

app.Run();

// Los errores de la API llegan al navegador con su código, que es lo que se quiere ver al probar.
static async Task<IResult> Llamar<T>(Func<Task<T>> llamada)
{
    try
    {
        return Results.Ok(await llamada());
    }
    catch (ApiToSmsException e)
    {
        return Results.Json(new { error = e.Message, codigo = e.Codigo, status = e.Status, errores = e.Errores }, statusCode: 502);
    }
    catch (ConexionException e)
    {
        return Results.Json(new { error = e.Message, codigo = e is CertificadoRechazadoException ? "certificado" : "conexion" }, statusCode: 504);
    }
    catch (Exception e) when (e is ArgumentException or FormatException or InvalidOperationException or JsonException)
    {
        return Results.BadRequest(new { error = e.Message });
    }
}

static string? Vacio(string? s) => string.IsNullOrWhiteSpace(s) ? null : s.Trim();

internal sealed record PeticionEnvio(string Destino, string Texto, string? Referencia);

internal sealed record PeticionLote(IReadOnlyList<string> Destinos, int Cantidad, string Texto);
