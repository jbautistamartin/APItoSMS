# Capicua.ApiToSms.Cliente

Biblioteca cliente de [APItoSMS](https://github.com/jbautistamartin/APItoSMS): envía SMS a través
de un teléfono Android de la red local, con el certificado fijado por su huella.

```csharp
using Capicua.ApiToSms.Cliente;

using var cliente = ClienteApiToSms.Crear(new OpcionesApiToSms
{
    Url = new Uri("https://192.168.1.50:8443"),
    Clave = Environment.GetEnvironmentVariable("APITOSMS_CLAVE"),
    Huella = "99:48:7A:…:88:06",          // la del Panel, cualquiera de las dos
});

var mensaje = await cliente.EnviarAsync(new NuevoMensaje("+523311298770", "Prueba"));
var final = await cliente.EsperarResultadoAsync(mensaje.Id);
```

Con inyección de dependencias: `builder.Services.AddApiToSms(o => { … })`.

El paquete no está en nuget.org: se descarga del release de GitHub. Ensamblado con nombre seguro,
`PublicKeyToken=a313609336f4f9ff`.
