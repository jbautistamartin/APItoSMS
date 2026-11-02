// APItoSMS — Tests de la biblioteca cliente
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Capicua.ApiToSms.Cliente.Tests;

/// <summary>
/// El pinning contra un TLS de verdad: un Kestrel en proceso con un certificado autofirmado EC
/// P-256, como el que genera el teléfono.
/// </summary>
public sealed class PinningTests : IAsyncLifetime
{
    private const string Clave = "ats_0123456789abcdefghijABCDEFGHIJ0123456789abc";

    private readonly X509Certificate2 _certificado = CrearCertificado();
    private WebApplication? _servidor;
    private Uri _url = null!;

    public async Task InitializeAsync()
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.ConfigureKestrel(k => k.Listen(IPAddress.Loopback, 0, l => l.UseHttps(_certificado)));
        _servidor = builder.Build();
        _servidor.MapGet("/api/v1/salud", () => Results.Json(new { estado = "ok", version = "1.0.0" }));
        await _servidor.StartAsync();
        var direccion = _servidor.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.First();
        _url = new Uri(direccion);
    }

    public async Task DisposeAsync()
    {
        if (_servidor is not null) await _servidor.DisposeAsync();
        _certificado.Dispose();
    }

    [Fact]
    public async Task Con_la_huella_del_certificado_conecta()
    {
        using var cliente = Cliente(HuellaFijada.DeCertificado(_certificado));
        var salud = await cliente.SaludAsync();
        Assert.Equal("ok", salud.Estado);
    }

    [Fact]
    public async Task Con_la_huella_de_la_clave_publica_conecta()
    {
        using var cliente = Cliente(HuellaFijada.DeClavePublica(_certificado));
        Assert.Equal("1.0.0", (await cliente.SaludAsync()).Version);
    }

    [Fact]
    public async Task La_huella_se_admite_en_minusculas_y_sin_dos_puntos()
    {
        using var cliente = Cliente(HuellaFijada.DeCertificado(_certificado).Replace(":", "").ToLowerInvariant());
        Assert.Equal("ok", (await cliente.SaludAsync()).Estado);
    }

    [Fact]
    public async Task Con_otra_huella_se_niega_y_no_reintenta()
    {
        using var otro = CrearCertificado();
        using var cliente = Cliente(HuellaFijada.DeCertificado(otro));
        await Assert.ThrowsAsync<CertificadoRechazadoException>(() => cliente.SaludAsync());
    }

    [Fact]
    public async Task Sin_huella_un_autofirmado_no_pasa_la_validacion_del_sistema()
    {
        using var cliente = Cliente(huella: null);
        await Assert.ThrowsAsync<CertificadoRechazadoException>(() => cliente.SaludAsync());
    }

    [Theory]
    [InlineData("AB:CD")]
    [InlineData("sha256//no-es-base64!")]
    [InlineData("sha256//AAAA")]
    [InlineData("ZZ:CD:F6:C6:57:47:A5:02:40:56:35:56:2D:C5:9A:A5:E3:0C:79:6B:8F:15:7D:66:4C:B7:FF:5B:8B:D9:EC:CA")]
    public void Una_huella_mal_escrita_se_rechaza(string huella) =>
        Assert.Throws<FormatException>(() => HuellaFijada.Leer(huella));

    [Fact]
    public void La_huella_se_escribe_como_en_el_Panel()
    {
        var huella = HuellaFijada.DeCertificado(_certificado);
        Assert.Matches("^([0-9A-F]{2}:){31}[0-9A-F]{2}$", huella);
        Assert.Equal(huella, HuellaFijada.Leer(huella.ToLowerInvariant()).ToString());
    }

    private ClienteApiToSms Cliente(string? huella) => ClienteApiToSms.Crear(new OpcionesApiToSms
    {
        Url = _url,
        Clave = Clave,
        Huella = huella,
        Reintentos = 1,
        EsperaEntreReintentos = TimeSpan.FromMilliseconds(1),
    });

    private static X509Certificate2 CrearCertificado()
    {
        using var clave = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var peticion = new CertificateRequest("CN=127.0.0.1", clave, HashAlgorithmName.SHA256);
        var san = new SubjectAlternativeNameBuilder();
        san.AddIpAddress(IPAddress.Loopback);
        peticion.CertificateExtensions.Add(san.Build());
        using var efimero = peticion.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(1));
        // En Windows, SChannel no sirve un certificado con la clave efímera de CreateSelfSigned:
        // hay que pasarlo por un PKCS#12.
        return X509CertificateLoader.LoadPkcs12(efimero.Export(X509ContentType.Pkcs12), null);
    }
}
