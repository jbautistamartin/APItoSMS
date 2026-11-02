// APItoSMS — Web de pruebas
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

using System.Text.Json;
using Capicua.ApiToSms.Cliente;

namespace Capicua.ApiToSms.Pruebas.Web;

/// <summary>Lo que se escribe en el formulario de conexión. La clave vacía conserva la guardada.</summary>
public sealed record DatosConexion(string? Url, string? Clave, string? Huella);

/// <summary>
/// La conexión con el teléfono, guardada en <c>conexion.json</c> junto al proyecto (o al ejecutable, publicada). Ese fichero
/// lleva la clave de la API en claro y <b>no se versiona</b> (está en el <c>.gitignore</c>).
/// </summary>
public sealed class Conexion : IDisposable
{
    private static readonly JsonSerializerOptions Formato = new(JsonSerializerDefaults.Web) { WriteIndented = true };

    private readonly string _fichero;
    private readonly Lock _cerrojo = new();
    private DatosConexion _datos = new(null, null, null);
    private ClienteApiToSms? _cliente;

    public Conexion(IWebHostEnvironment entorno)
    {
        _fichero = Path.Combine(entorno.ContentRootPath, "conexion.json");
        if (File.Exists(_fichero))
        {
            _datos = JsonSerializer.Deserialize<DatosConexion>(File.ReadAllText(_fichero), Formato) ?? _datos;
        }
    }

    /// <summary>Lo que se le enseña al navegador: todo menos la clave.</summary>
    public object Publica()
    {
        lock (_cerrojo)
        {
            return new
            {
                _datos.Url,
                _datos.Huella,
                TieneClave = !string.IsNullOrEmpty(_datos.Clave),
                ClaveVisible = _datos.Clave is { Length: > 8 } c ? c[..8] + "…" : null,
            };
        }
    }

    public void Guardar(DatosConexion nuevos)
    {
        lock (_cerrojo)
        {
            var datos = new DatosConexion(
                nuevos.Url?.Trim(),
                string.IsNullOrWhiteSpace(nuevos.Clave) ? _datos.Clave : nuevos.Clave.Trim(),
                string.IsNullOrWhiteSpace(nuevos.Huella) ? null : nuevos.Huella.Trim());
            var cliente = ClienteApiToSms.Crear(Opciones(datos)); // valida antes de guardar
            _cliente?.Dispose();
            _cliente = cliente;
            _datos = datos;
            File.WriteAllText(_fichero, JsonSerializer.Serialize(datos, Formato));
        }
    }

    /// <summary>El cliente con la conexión actual.</summary>
    /// <exception cref="InvalidOperationException">Si aún no hay conexión configurada.</exception>
    public ClienteApiToSms Cliente()
    {
        lock (_cerrojo)
        {
            if (_cliente is not null) return _cliente;
            if (_datos.Url is null || _datos.Clave is null)
            {
                throw new InvalidOperationException("Falta configurar la conexión con el teléfono.");
            }
            return _cliente = ClienteApiToSms.Crear(Opciones(_datos));
        }
    }

    private static OpcionesApiToSms Opciones(DatosConexion d) => new()
    {
        Url = Uri.TryCreate(d.Url, UriKind.Absolute, out var url) ? url : throw new ArgumentException("La URL no es válida."),
        Clave = d.Clave,
        Huella = d.Huella,
    };

    public void Dispose() => _cliente?.Dispose();
}
