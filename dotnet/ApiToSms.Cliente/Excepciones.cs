// APItoSMS — Biblioteca cliente
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

namespace ApiToSms.Cliente;

/// <summary>
/// El teléfono contestó con un error. <see cref="Codigo"/> es lo que debe mirar un programa:
/// estable, en minúsculas y con guiones (<c>validacion</c>, <c>cuota-agotada</c>…). El mensaje es
/// para una persona y puede cambiar de redacción.
/// </summary>
public class ApiToSmsException : Exception
{
    /// <summary>Código HTTP.</summary>
    public int Status { get; }

    /// <summary>Código estable del error. La lista está en <c>docs/errores.md</c>.</summary>
    public string Codigo { get; }

    /// <summary>Errores por campo, en un 422.</summary>
    public IReadOnlyList<ErrorDeCampo> Errores { get; }

    /// <summary>Crea la excepción.</summary>
    public ApiToSmsException(int status, string codigo, string mensaje, IReadOnlyList<ErrorDeCampo>? errores = null)
        : base(mensaje)
    {
        Status = status;
        Codigo = codigo;
        Errores = errores ?? [];
    }
}

/// <summary>401: la clave falta, no existe o está revocada. El teléfono no dice cuál de las tres.</summary>
public sealed class NoAutenticadoException(string mensaje)
    : ApiToSmsException(401, "no-autenticado", mensaje);

/// <summary>
/// 403: la clave no tiene el ámbito necesario (<c>sin-permiso</c>), o no admite peticiones desde
/// esta IP (<c>ip-no-permitida</c>).
/// </summary>
public sealed class SinPermisoException(string codigo, string mensaje)
    : ApiToSmsException(403, codigo, mensaje);

/// <summary>404: el mensaje no existe, o es de otra clave.</summary>
public sealed class NoEncontradoException(string mensaje)
    : ApiToSmsException(404, "no-encontrado", mensaje);

/// <summary>422: la petición no es válida. <see cref="ApiToSmsException.Errores"/> dice qué campo y por qué.</summary>
public sealed class ValidacionException(string mensaje, IReadOnlyList<ErrorDeCampo> errores)
    : ApiToSmsException(422, "validacion", mensaje, errores);

/// <summary>
/// 409: la <c>Idempotency-Key</c> ya se usó con otro contenido (<c>conflicto-idempotencia</c>), o
/// el mensaje ya no se puede cancelar porque salió de la cola (<c>no-cancelable</c>).
/// </summary>
public sealed class ConflictoException(string codigo, string mensaje)
    : ApiToSmsException(409, codigo, mensaje);

/// <summary>
/// 429: la clave pide demasiado deprisa (<c>ritmo-excedido</c>) o se agotó la cuota diaria
/// (<c>cuota-agotada</c>). El cliente reintenta solo el primero, si la espera es razonable.
/// </summary>
public sealed class LimiteExcedidoException(string codigo, string mensaje, TimeSpan? reintentarEn)
    : ApiToSmsException(429, codigo, mensaje)
{
    /// <summary>Lo que dijo el teléfono en <c>Retry-After</c>.</summary>
    public TimeSpan? ReintentarEn { get; } = reintentarEn;
}

/// <summary>
/// No se pudo hablar con el teléfono: red, tiempo agotado o TLS. Se lanza después de agotar los
/// reintentos.
/// </summary>
public class ConexionException(string mensaje, Exception? causa)
    : Exception(mensaje, causa);

/// <summary>
/// El certificado del teléfono no es el esperado: la huella no coincide con la configurada. O se
/// regeneró el certificado en el teléfono, o alguien se está haciendo pasar por él. No se reintenta.
/// </summary>
public sealed class CertificadoRechazadoException(string mensaje, Exception? causa)
    : ConexionException(mensaje, causa);

/// <summary>
/// <see cref="ClienteApiToSms.EsperarResultadoAsync"/> agotó su plazo sin que el mensaje terminara.
/// </summary>
public sealed class EsperaAgotadaException(Mensaje ultimo)
    : TimeoutException($"El mensaje {ultimo.Id} sigue {ultimo.Estado} al acabar el plazo de espera")
{
    /// <summary>El último estado leído.</summary>
    public Mensaje Ultimo { get; } = ultimo;
}
