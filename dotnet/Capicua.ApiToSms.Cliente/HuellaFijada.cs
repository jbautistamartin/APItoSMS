// APItoSMS — Biblioteca cliente
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Capicua.ApiToSms.Cliente;

/// <summary>
/// La huella con la que se fija el certificado del teléfono. El Panel de la app enseña las dos
/// formas que se admiten:
/// <list type="bullet">
/// <item><b>Huella del certificado</b>: SHA-256 del certificado entero, en hexadecimal
/// (<c>1C:6C:F6:…</c>; los dos puntos, los espacios y las mayúsculas dan igual). Cambia si se
/// regenera o se renueva el certificado.</item>
/// <item><b>Huella de la clave pública</b>: <c>sha256//</c> y el SHA-256 de la clave pública en
/// base64, el formato de <c>curl --pinnedpubkey</c>. Sobrevive a renovar un certificado importado
/// con la misma clave.</item>
/// </list>
/// </summary>
/// <remarks>
/// Con una huella fijada <b>no se mira la cadena ni el nombre</b>: el certificado del teléfono es
/// autofirmado y su IP puede cambiar, y lo único que importa es que sea exactamente ese. Sin
/// huella, la validación es la normal del sistema, que solo vale con un certificado firmado por una
/// CA en la que confíe esta máquina.
/// </remarks>
public sealed class HuellaFijada
{
    private const string PrefijoClave = "sha256//";

    private readonly byte[] _digest;
    private readonly bool _deClavePublica;

    private HuellaFijada(byte[] digest, bool deClavePublica)
    {
        _digest = digest;
        _deClavePublica = deClavePublica;
    }

    /// <summary>Lee la huella en cualquiera de las dos formas.</summary>
    /// <exception cref="FormatException">Si no es ninguna de las dos.</exception>
    public static HuellaFijada Leer(string texto)
    {
        ArgumentNullException.ThrowIfNull(texto);
        var t = texto.Trim();

        if (t.StartsWith(PrefijoClave, StringComparison.OrdinalIgnoreCase))
        {
            byte[] bytes;
            try { bytes = Convert.FromBase64String(t[PrefijoClave.Length..]); }
            catch (FormatException) { throw new FormatException("La huella de la clave pública no es base64 válido."); }
            if (bytes.Length != 32) throw new FormatException("La huella de la clave pública tiene que ser un SHA-256 (32 bytes).");
            return new HuellaFijada(bytes, deClavePublica: true);
        }

        var hex = new string(t.Where(c => c is not (':' or ' ' or '-')).ToArray());
        if (hex.Length != 64 || !hex.All(Uri.IsHexDigit))
        {
            throw new FormatException(
                "La huella tiene que ser el SHA-256 del certificado en hexadecimal (64 cifras, con o sin ':') o 'sha256//' y la de la clave pública en base64.");
        }
        return new HuellaFijada(Convert.FromHexString(hex), deClavePublica: false);
    }

    /// <summary>true si el certificado es el fijado.</summary>
    public bool Coincide(X509Certificate2 certificado)
    {
        var digest = _deClavePublica
            ? SHA256.HashData(certificado.PublicKey.ExportSubjectPublicKeyInfo())
            : SHA256.HashData(certificado.RawData);
        return CryptographicOperations.FixedTimeEquals(digest, _digest);
    }

    /// <summary>Huella del certificado, en el formato del Panel (<c>AB:CD:…</c>).</summary>
    public static string DeCertificado(X509Certificate2 certificado) =>
        ConDosPuntos(SHA256.HashData(certificado.RawData));

    /// <summary>Huella de la clave pública, en el formato de <c>curl --pinnedpubkey</c>.</summary>
    public static string DeClavePublica(X509Certificate2 certificado) =>
        PrefijoClave + Convert.ToBase64String(SHA256.HashData(certificado.PublicKey.ExportSubjectPublicKeyInfo()));

    /// <inheritdoc />
    public override string ToString() => _deClavePublica
        ? PrefijoClave + Convert.ToBase64String(_digest)
        : ConDosPuntos(_digest);

    private static string ConDosPuntos(byte[] bytes) =>
        string.Join(':', Convert.ToHexString(bytes).Chunk(2).Select(p => new string(p)));
}
