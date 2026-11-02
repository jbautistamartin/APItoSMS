// APItoSMS — Tests de la biblioteca cliente
// Copyright © 2026 Capicua · José Luis Bautista Martín
// Licencia: GNU Lesser General Public License v2.1
// https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

using System.Net;
using System.Text.Json;

namespace ApiToSms.Cliente.Tests;

public class ClienteTests
{
    private const string Clave = "ats_0123456789abcdefghijABCDEFGHIJ0123456789abc";

    private static (ClienteApiToSms, ManejadorFalso) Cliente(int reintentos = 2)
    {
        var falso = new ManejadorFalso();
        var opciones = new OpcionesApiToSms
        {
            Url = new Uri("https://192.168.1.50:8443"),
            Clave = Clave,
            Reintentos = reintentos,
            EsperaEntreReintentos = TimeSpan.FromMilliseconds(1),
        };
        return (new ClienteApiToSms(new HttpClient(falso), opciones), falso);
    }

    // ── Envío ───────────────────────────────────────────────────────────

    [Fact]
    public async Task Enviar_manda_el_json_de_la_api_con_clave_e_idempotencia()
    {
        var (cliente, falso) = Cliente();
        falso.Json(HttpStatusCode.Accepted, ManejadorFalso.Mensaje(extra: ",\"posicionEnCola\":3"));

        var m = await cliente.EnviarAsync(new NuevoMensaje("+523311298770", "hola", "pedido-7"), "clave-1");

        var p = Assert.Single(falso.Recibidas);
        Assert.Equal(HttpMethod.Post, p.Metodo);
        Assert.Equal("/api/v1/mensajes", p.Ruta);
        Assert.Equal("Bearer " + Clave, p.Cabeceras["Authorization"]);
        Assert.Equal("clave-1", p.Cabeceras["Idempotency-Key"]);
        // Sin caducaEn: el teléfono rechaza los campos desconocidos y no hace falta mandar nulls.
        Assert.Equal("""{"destino":"+523311298770","texto":"hola","referencia":"pedido-7"}""", p.Cuerpo);
        Assert.Equal(EstadoMensaje.EnCola, m.Estado);
        Assert.Equal(3, m.PosicionEnCola);
        Assert.False(m.Repetido);
        Assert.False(m.Terminado);
    }

    [Fact]
    public async Task Los_cuerpos_viajan_con_Content_Length()
    {
        // El teléfono contesta 501 a un cuerpo chunked. Pasó con JsonContent en la primera prueba
        // contra el teléfono: el lote no se admitió.
        var (cliente, falso) = Cliente();
        falso.Json(HttpStatusCode.Accepted, ManejadorFalso.Mensaje())
             .Json(HttpStatusCode.Accepted, $$"""{"mensajes":[{{ManejadorFalso.Mensaje()}}]}""");

        await cliente.EnviarAsync(new NuevoMensaje("+523311298770", "más tildes: ñandú"));
        await cliente.EnviarLoteAsync([new("+523311298770", "hola")]);

        Assert.All(falso.Recibidas, r => Assert.Equal(System.Text.Encoding.UTF8.GetByteCount(r.Cuerpo!), r.Longitud));
    }

    [Fact]
    public async Task Enviar_sin_idempotencia_genera_una_y_la_repite_en_el_reintento()
    {
        var (cliente, falso) = Cliente();
        falso.FalloDeRed().Json(HttpStatusCode.Accepted, ManejadorFalso.Mensaje());

        await cliente.EnviarAsync(new NuevoMensaje("+523311298770", "hola"));

        Assert.Equal(2, falso.Recibidas.Count);
        var primera = falso.Recibidas[0].Cabeceras["Idempotency-Key"];
        Assert.Matches("^[0-9a-f]{32}$", primera);
        Assert.Equal(primera, falso.Recibidas[1].Cabeceras["Idempotency-Key"]);
        Assert.Equal(falso.Recibidas[0].Cuerpo, falso.Recibidas[1].Cuerpo);
    }

    [Fact]
    public async Task Una_respuesta_repetida_se_marca()
    {
        var (cliente, falso) = Cliente();
        falso.Json(HttpStatusCode.Accepted, ManejadorFalso.Mensaje(), ("Idempotent-Replayed", "true"));

        var m = await cliente.EnviarAsync(new NuevoMensaje("+523311298770", "hola"), "k");

        Assert.True(m.Repetido);
    }

    [Fact]
    public async Task La_caducidad_viaja_como_iso_8601_con_zona()
    {
        var (cliente, falso) = Cliente();
        falso.Json(HttpStatusCode.Accepted, ManejadorFalso.Mensaje());

        await cliente.EnviarAsync(new NuevoMensaje("+523311298770", "hola", CaducaEn: new DateTimeOffset(2026, 11, 1, 18, 30, 0, TimeSpan.Zero)));

        using var json = JsonDocument.Parse(falso.Recibidas[0].Cuerpo!);
        var caduca = json.RootElement.GetProperty("caducaEn").GetString();
        Assert.Equal(new DateTimeOffset(2026, 11, 1, 18, 30, 0, TimeSpan.Zero), DateTimeOffset.Parse(caduca!));
        Assert.Contains("+00:00", caduca);
    }

    [Fact]
    public async Task Lote_manda_los_mensajes_en_orden_y_devuelve_los_admitidos()
    {
        var (cliente, falso) = Cliente();
        falso.Json(HttpStatusCode.Accepted,
            $$"""{"mensajes":[{{ManejadorFalso.Mensaje("a")}},{{ManejadorFalso.Mensaje("b")}}]}""");

        var lote = await cliente.EnviarLoteAsync([new("+52331", "uno"), new("+52332", "dos")], "lote-1");

        Assert.Equal(["a", "b"], lote.Select(m => m.Id));
        Assert.Equal("/api/v1/lotes", falso.Recibidas[0].Ruta);
        Assert.Equal("""{"mensajes":[{"destino":"+52331","texto":"uno"},{"destino":"+52332","texto":"dos"}]}""", falso.Recibidas[0].Cuerpo);
    }

    // ── Errores ─────────────────────────────────────────────────────────

    [Fact]
    public async Task Un_422_trae_los_errores_por_campo()
    {
        var (cliente, falso) = Cliente();
        falso.Problema(422, "validacion", "texto: es obligatorio",
            """[{"indice":1,"campo":"texto","motivo":"es obligatorio"}]""");

        var e = await Assert.ThrowsAsync<ValidacionException>(() =>
            cliente.EnviarLoteAsync([new("+52331", "uno"), new("+52332", "")]));

        var error = Assert.Single(e.Errores);
        Assert.Equal(1, error.Indice);
        Assert.Equal("texto", error.Campo);
        Assert.Single(falso.Recibidas); // un 422 no se reintenta
    }

    [Theory]
    [InlineData(401, "no-autenticado", typeof(NoAutenticadoException))]
    [InlineData(403, "ip-no-permitida", typeof(SinPermisoException))]
    [InlineData(404, "no-encontrado", typeof(NoEncontradoException))]
    [InlineData(409, "conflicto-idempotencia", typeof(ConflictoException))]
    [InlineData(415, "tipo-no-soportado", typeof(ApiToSmsException))]
    public async Task Cada_error_es_su_excepcion_y_no_se_reintenta(int estado, string codigo, Type tipo)
    {
        var (cliente, falso) = Cliente();
        falso.Problema(estado, codigo);

        var e = await Assert.ThrowsAnyAsync<ApiToSmsException>(() => cliente.ConsultarAsync("x"));

        Assert.IsType(tipo, e);
        Assert.Equal(codigo, e.Codigo);
        Assert.Equal(estado, e.Status);
        Assert.Single(falso.Recibidas);
    }

    [Fact]
    public async Task Un_429_por_ritmo_espera_lo_que_pide_y_reintenta()
    {
        var (cliente, falso) = Cliente();
        falso.Problema(429, "ritmo-excedido", cabeceras: ("Retry-After", "0"))
             .Json(HttpStatusCode.OK, ManejadorFalso.Mensaje());

        var m = await cliente.ConsultarAsync("m1");

        Assert.Equal("m1", m.Id);
        Assert.Equal(2, falso.Recibidas.Count);
    }

    [Fact]
    public async Task Un_429_por_cuota_hasta_manana_no_se_reintenta()
    {
        var (cliente, falso) = Cliente();
        falso.Problema(429, "cuota-agotada", cabeceras: ("Retry-After", "30000"));

        var e = await Assert.ThrowsAsync<LimiteExcedidoException>(() =>
            cliente.EnviarAsync(new NuevoMensaje("+52331", "hola")));

        Assert.Equal("cuota-agotada", e.Codigo);
        Assert.Equal(TimeSpan.FromSeconds(30000), e.ReintentarEn);
        Assert.Single(falso.Recibidas);
    }

    [Fact]
    public async Task Un_5xx_se_reintenta_y_al_agotar_los_intentos_llega_el_error()
    {
        var (cliente, falso) = Cliente(reintentos: 2);
        falso.Problema(503, "a").Problema(503, "b").Problema(503, "c");

        var e = await Assert.ThrowsAsync<ApiToSmsException>(() => cliente.EstadoColaAsync());

        Assert.Equal(503, e.Status);
        Assert.Equal("c", e.Codigo);
        Assert.Equal(3, falso.Recibidas.Count);
    }

    [Fact]
    public async Task Los_fallos_de_red_acaban_en_ConexionException()
    {
        var (cliente, falso) = Cliente(reintentos: 1);
        falso.FalloDeRed().FalloDeRed();

        var e = await Assert.ThrowsAsync<ConexionException>(() => cliente.SaludAsync());

        Assert.IsType<HttpRequestException>(e.InnerException);
        Assert.Equal(2, falso.Recibidas.Count);
    }

    // ── Consultas ───────────────────────────────────────────────────────

    [Fact]
    public async Task Listar_pone_el_filtro_en_la_url()
    {
        var (cliente, falso) = Cliente();
        falso.Json(HttpStatusCode.OK, """{"mensajes":[]}""");

        await cliente.ListarAsync(new FiltroDeMensajes(
            EstadoMensaje.NoEntregado, "pedido 7", new DateTimeOffset(2026, 11, 1, 12, 0, 0, TimeSpan.FromHours(-6)), 20, "abc-_"));

        Assert.Equal(
            "/api/v1/mensajes?estado=NO_ENTREGADO&referencia=pedido%207&desde=2026-11-01T18%3A00%3A00.000Z&limite=20&cursor=abc-_",
            falso.Recibidas[0].Ruta);
    }

    [Fact]
    public async Task ListarTodos_sigue_el_cursor_hasta_el_final()
    {
        var (cliente, falso) = Cliente();
        falso.Json(HttpStatusCode.OK, $$"""{"mensajes":[{{ManejadorFalso.Mensaje("a")}}],"siguiente":"c1"}""")
             .Json(HttpStatusCode.OK, $$"""{"mensajes":[{{ManejadorFalso.Mensaje("b")}}]}""");

        var ids = new List<string>();
        await foreach (var m in cliente.ListarTodosAsync()) ids.Add(m.Id);

        Assert.Equal(["a", "b"], ids);
        Assert.EndsWith("?cursor=c1", falso.Recibidas[1].Ruta);
    }

    [Fact]
    public async Task Esperar_consulta_hasta_que_termina()
    {
        var (cliente, falso) = Cliente();
        falso.Json(HttpStatusCode.OK, ManejadorFalso.Mensaje(estado: "EN_COLA"))
             .Json(HttpStatusCode.OK, ManejadorFalso.Mensaje(estado: "ENVIANDO"))
             .Json(HttpStatusCode.OK, ManejadorFalso.Mensaje(estado: "FALLIDO", extra: ",\"ultimoError\":\"sin servicio\""));

        var m = await cliente.EsperarResultadoAsync("m1", Rapida());

        Assert.Equal(EstadoMensaje.Fallido, m.Estado);
        Assert.Equal("sin servicio", m.UltimoError);
        Assert.Equal(3, falso.Recibidas.Count);
    }

    [Fact]
    public async Task Esperar_da_por_bueno_ENVIADO_sin_informe_por_defecto()
    {
        var (cliente, falso) = Cliente();
        falso.Json(HttpStatusCode.OK, ManejadorFalso.Mensaje(estado: "ENVIADO"));

        var m = await cliente.EsperarResultadoAsync("m1", Rapida());

        Assert.Equal(EstadoMensaje.Enviado, m.Estado);
        Assert.True(m.Salio);
    }

    [Fact]
    public async Task Esperar_el_informe_sigue_tras_ENVIADO_hasta_ENTREGADO()
    {
        var (cliente, falso) = Cliente();
        var reciente = DateTimeOffset.UtcNow.ToString("O");
        falso.Json(HttpStatusCode.OK, ManejadorFalso.Mensaje(estado: "ENVIADO", extra: $",\"enviadoEn\":\"{reciente}\""))
             .Json(HttpStatusCode.OK, ManejadorFalso.Mensaje(estado: "ENTREGADO"));

        var o = Rapida();
        o.EsperarInformeDeEntrega = true;
        var m = await cliente.EsperarResultadoAsync("m1", o);

        Assert.Equal(EstadoMensaje.Entregado, m.Estado);
    }

    [Fact]
    public async Task Esperar_el_informe_devuelve_ENVIADO_si_el_operador_no_lo_manda()
    {
        var (cliente, falso) = Cliente();
        // Salió hace tres minutos: el plazo del informe (2 min) ya pasó. Es el caso de Telcel.
        var antiguo = DateTimeOffset.UtcNow.AddMinutes(-3).ToString("O");
        falso.Json(HttpStatusCode.OK, ManejadorFalso.Mensaje(estado: "ENVIADO", extra: $",\"enviadoEn\":\"{antiguo}\""));

        var o = Rapida();
        o.EsperarInformeDeEntrega = true;
        var m = await cliente.EsperarResultadoAsync("m1", o);

        Assert.Equal(EstadoMensaje.Enviado, m.Estado);
        Assert.Single(falso.Recibidas);
    }

    [Fact]
    public async Task Esperar_agota_el_plazo_con_el_ultimo_estado()
    {
        var (cliente, falso) = Cliente();
        for (var i = 0; i < 50; i++) falso.Json(HttpStatusCode.OK, ManejadorFalso.Mensaje(estado: "EN_COLA"));

        var o = Rapida();
        o.Plazo = TimeSpan.FromMilliseconds(50);
        var e = await Assert.ThrowsAsync<EsperaAgotadaException>(() => cliente.EsperarResultadoAsync("m1", o));

        Assert.Equal(EstadoMensaje.EnCola, e.Ultimo.Estado);
    }

    [Fact]
    public async Task Cancelar_lo_ya_cancelado_no_es_un_error()
    {
        var (cliente, falso) = Cliente();
        falso.Problema(409, "no-cancelable")
             .Json(HttpStatusCode.OK, ManejadorFalso.Mensaje(estado: "CANCELADO"));

        var m = await cliente.CancelarAsync("m1");

        Assert.Equal(EstadoMensaje.Cancelado, m.Estado);
        Assert.Equal(HttpMethod.Delete, falso.Recibidas[0].Metodo);
    }

    [Fact]
    public async Task Cancelar_lo_que_ya_salio_es_un_conflicto()
    {
        var (cliente, falso) = Cliente();
        falso.Problema(409, "no-cancelable")
             .Json(HttpStatusCode.OK, ManejadorFalso.Mensaje(estado: "ENVIADO"));

        var e = await Assert.ThrowsAsync<ConflictoException>(() => cliente.CancelarAsync("m1"));

        Assert.Equal("no-cancelable", e.Codigo);
    }

    [Fact]
    public async Task Pausar_y_reanudar_van_a_sus_rutas_sin_cuerpo()
    {
        var (cliente, falso) = Cliente();
        const string cola = """{"pausada":true,"enCola":2,"enviando":0,"pausaEntreMensajesSegundos":5,"hoy":{"ENVIADO":4},"cuotaGlobal":{"limite":0,"usadas":4}}""";
        falso.Json(HttpStatusCode.OK, cola).Json(HttpStatusCode.OK, cola.Replace("true", "false"));

        var pausada = await cliente.PausarAsync();
        var reanudada = await cliente.ReanudarAsync();

        Assert.True(pausada.Pausada);
        Assert.Equal(4, pausada.Hoy["ENVIADO"]);
        Assert.False(reanudada.Pausada);
        Assert.Equal(["/api/v1/cola/pausa", "/api/v1/cola/reanudar"], falso.Recibidas.Select(r => r.Ruta));
        Assert.All(falso.Recibidas, r => Assert.Null(r.Cuerpo));
    }

    // ── Opciones ────────────────────────────────────────────────────────

    [Theory]
    [InlineData("http://192.168.1.50:8443", Clave)]
    [InlineData("https://192.168.1.50:8443", "sin-prefijo")]
    public void Las_opciones_malas_se_rechazan_al_crear(string url, string clave)
    {
        var o = new OpcionesApiToSms { Url = new Uri(url), Clave = clave };
        Assert.Throws<ArgumentException>(() => ClienteApiToSms.Crear(o));
    }

    private static OpcionesDeEspera Rapida() => new()
    {
        IntervaloInicial = TimeSpan.FromMilliseconds(1),
        IntervaloMaximo = TimeSpan.FromMilliseconds(2),
    };
}
