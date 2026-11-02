// APItoSMS — Biblioteca cliente
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

namespace ApiToSms.Cliente;

/// <summary>Cómo llegar al teléfono y cómo comportarse cuando falla.</summary>
public sealed class OpcionesApiToSms
{
    /// <summary>La URL que enseña el Panel de la app, por ejemplo <c>https://192.168.1.50:8443</c>.</summary>
    public Uri? Url { get; set; }

    /// <summary>La clave de la API (<c>ats_…</c>). Se ve una sola vez, al crearla en el teléfono.</summary>
    public string? Clave { get; set; }

    /// <summary>
    /// Huella del certificado del teléfono, en cualquiera de las dos formas del Panel (ver
    /// <see cref="HuellaFijada"/>). Sin huella se usa la validación normal del sistema.
    /// </summary>
    public string? Huella { get; set; }

    /// <summary>
    /// Reintentos tras un fallo de red, un 5xx o un 429 por ritmo. Reintentar es seguro en todas
    /// las llamadas: los envíos llevan siempre <c>Idempotency-Key</c>.
    /// </summary>
    public int Reintentos { get; set; } = 3;

    /// <summary>Espera antes del primer reintento; crece de forma lineal.</summary>
    public TimeSpan EsperaEntreReintentos { get; set; } = TimeSpan.FromSeconds(1);

    /// <summary>
    /// Un 429 cuyo <c>Retry-After</c> pida esperar más que esto no se reintenta y llega al llamador
    /// como <see cref="LimiteExcedidoException"/>. Así se distingue el ritmo (segundos) de la cuota
    /// diaria agotada (horas).
    /// </summary>
    public TimeSpan EsperaMaximaPorLimite { get; set; } = TimeSpan.FromSeconds(60);

    /// <summary>Tiempo máximo de cada petición.</summary>
    public TimeSpan TiempoMaximoPeticion { get; set; } = TimeSpan.FromSeconds(30);

    internal void Validar()
    {
        if (Url is null) throw new ArgumentException("Falta la URL del teléfono.", nameof(Url));
        if (Url.Scheme != Uri.UriSchemeHttps)
        {
            throw new ArgumentException("La URL tiene que ser https: el teléfono no habla HTTP en claro.", nameof(Url));
        }
        if (string.IsNullOrWhiteSpace(Clave) || !Clave.StartsWith("ats_", StringComparison.Ordinal))
        {
            throw new ArgumentException("La clave de la API tiene que empezar por 'ats_'.", nameof(Clave));
        }
        if (Huella is not null) HuellaFijada.Leer(Huella);
        if (Reintentos < 0) throw new ArgumentOutOfRangeException(nameof(Reintentos));
    }
}

/// <summary>Cómo espera <see cref="ClienteApiToSms.EsperarResultadoAsync"/>.</summary>
public sealed class OpcionesDeEspera
{
    /// <summary>Plazo total. Al agotarse se lanza <see cref="EsperaAgotadaException"/>.</summary>
    public TimeSpan Plazo { get; set; } = TimeSpan.FromMinutes(10);

    /// <summary>Primera espera entre consultas; crece ×1,5 hasta <see cref="IntervaloMaximo"/>.</summary>
    public TimeSpan IntervaloInicial { get; set; } = TimeSpan.FromSeconds(1);

    /// <summary>Espera máxima entre consultas.</summary>
    public TimeSpan IntervaloMaximo { get; set; } = TimeSpan.FromSeconds(15);

    /// <summary>
    /// Si es true, un mensaje <see cref="EstadoMensaje.Enviado"/> se sigue consultando hasta que
    /// llegue el informe de entrega o pase <see cref="PlazoInforme"/>. Con operadores que no
    /// mandan informes, el resultado será <see cref="EstadoMensaje.Enviado"/> igualmente, solo
    /// que más tarde.
    /// </summary>
    public bool EsperarInformeDeEntrega { get; set; }

    /// <summary>Cuánto esperar el informe de entrega desde que el mensaje salió.</summary>
    public TimeSpan PlazoInforme { get; set; } = TimeSpan.FromMinutes(2);
}
