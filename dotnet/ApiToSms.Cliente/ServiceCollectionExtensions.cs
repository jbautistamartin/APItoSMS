// APItoSMS — Biblioteca cliente
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;

namespace ApiToSms.Cliente;

/// <summary>Registro de <see cref="ClienteApiToSms"/> en un contenedor de dependencias.</summary>
public static class ServiceCollectionExtensions
{
    /// <summary>
    /// Registra <see cref="ClienteApiToSms"/> como cliente tipado de <c>IHttpClientFactory</c>, con
    /// el manejador que fija el certificado.
    /// </summary>
    /// <example>
    /// <code>
    /// builder.Services.AddApiToSms(o =>
    /// {
    ///     o.Url = new Uri("https://192.168.1.50:8443");
    ///     o.Clave = builder.Configuration["ApiToSms:Clave"];
    ///     o.Huella = builder.Configuration["ApiToSms:Huella"];
    /// });
    /// </code>
    /// </example>
    public static IHttpClientBuilder AddApiToSms(this IServiceCollection services, Action<OpcionesApiToSms> configurar)
    {
        ArgumentNullException.ThrowIfNull(services);
        ArgumentNullException.ThrowIfNull(configurar);
        services.AddOptions<OpcionesApiToSms>().Configure(configurar);
        services.AddTransient(sp => sp.GetRequiredService<IOptions<OpcionesApiToSms>>().Value);
        return services
            .AddHttpClient<ClienteApiToSms>((sp, http) =>
            {
                var o = sp.GetRequiredService<IOptions<OpcionesApiToSms>>().Value;
                if (o.Url is not null) http.BaseAddress = o.Url.AbsoluteUri.EndsWith('/') ? o.Url : new Uri(o.Url.AbsoluteUri + "/");
                http.Timeout = Timeout.InfiniteTimeSpan; // el tiempo lo controla cada petición
            })
            .ConfigurePrimaryHttpMessageHandler(sp =>
                ClienteApiToSms.CrearManejador(sp.GetRequiredService<IOptions<OpcionesApiToSms>>().Value));
    }
}
