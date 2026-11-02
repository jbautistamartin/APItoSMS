# Errores de la API de APItoSMS

Todo error llega como `application/problem+json` ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)):

```json
{
  "type": "https://github.com/jbautistamartin/APItoSMS/blob/main/docs/errores.md#validacion",
  "title": "Petición no válida",
  "status": 422,
  "detail": "destino: el destino solo puede llevar dígitos tras el +",
  "codigo": "validacion",
  "errores": [{ "campo": "destino", "motivo": "el destino solo puede llevar dígitos tras el +" }]
}
```

**Un programa debe mirar `codigo`.** Es estable, va en minúsculas y con guiones, y no cambia entre
versiones. `title` y `detail` son para una persona y su redacción puede cambiar.

La biblioteca C# (`Capicua.ApiToSms.Cliente`) convierte cada código en una excepción. Esa conversión
aparece en la columna «Excepción C#».

| Estado | `codigo` | Excepción C# | ¿Reintentar? |
|---|---|---|---|
| 400 | [`json-no-valido`](#json-no-valido) | `ApiToSmsException` | No |
| 400, 413, 431, 501 | [`http-no-valido`](#http-no-valido) | `ApiToSmsException` | No |
| 401 | [`no-autenticado`](#no-autenticado) | `NoAutenticadoException` | No |
| 403 | [`ip-no-permitida`](#ip-no-permitida) | `SinPermisoException` | No |
| 403 | [`sin-permiso`](#sin-permiso) | `SinPermisoException` | No |
| 404 | [`no-encontrado`](#no-encontrado) | `NoEncontradoException` | No |
| 405 | [`metodo-no-permitido`](#metodo-no-permitido) | `ApiToSmsException` | No |
| 409 | [`conflicto-idempotencia`](#conflicto-idempotencia) | `ConflictoException` | No |
| 409 | [`no-cancelable`](#no-cancelable) | `ConflictoException` | No |
| 415 | [`tipo-no-soportado`](#tipo-no-soportado) | `ApiToSmsException` | No |
| 422 | [`validacion`](#validacion) | `ValidacionException` | No, hay que corregir la petición |
| 429 | [`ritmo-excedido`](#ritmo-excedido) | `LimiteExcedidoException` | Sí, tras `Retry-After` |
| 429 | [`cuota-agotada`](#cuota-agotada) | `LimiteExcedidoException` | Mañana |
| 500 | [`error-interno`](#error-interno) | `ApiToSmsException` | Sí, con la misma `Idempotency-Key` |

Hay dos rechazos que no llegan como respuesta HTTP: los aplica el teléfono **antes del saludo
TLS**. En los dos, la conexión se cierra sin contestar:

- **IP bloqueada** tras 10 fallos de autenticación en 5 minutos. El bloqueo dura 15 minutos, y el
  Panel tiene un botón para levantarlo antes.
- **IP fuera de las redes permitidas** en los ajustes. Si la lista está vacía, solo se acepta la
  subred de la wifi.

---

## `json-no-valido`

El cuerpo no es JSON, no es UTF-8 o trae un campo que la API no conoce. **Un campo desconocido es
un error a propósito**: quien escribe `destinatario` en lugar de `destino` tiene que enterarse en
ese momento, no días después.

## `http-no-valido`

La petición HTTP no es válida antes de llegar a la API:

- `501`: trae `Transfer-Encoding` (por ejemplo `chunked`). El cuerpo tiene que ir con
  `Content-Length`. **Cuidado en .NET:** `JsonContent.Create` y `PostAsJsonAsync` mandan *chunked*.
  Serializa antes a texto o a bytes. `Capicua.ApiToSms.Cliente` ya lo hace.
- `413`: el cuerpo pasa de 1 MB.
- `431`: más de 64 cabeceras, o más de 16 KB entre todas.
- `400`: cabecera mal formada, `Content-Length` no válido, o una cabecera que no puede repetirse
  (`Authorization`, `Content-Length`, `Content-Type`, `Host`, `Idempotency-Key`) aparece dos veces.

Una petición sin `Content-Length` ni `Transfer-Encoding` tiene el cuerpo vacío (RFC 9112, 6.3).
Así funciona `curl -X POST …/cola/pausa`.

## `no-autenticado`

Falta la cabecera `Authorization: Bearer ats_…`, o la clave no existe, o está revocada. **Las tres
causas dan la misma respuesta**: distinguirlas le diría a quien prueba claves cuáles han existido.
El motivo real queda en el registro del teléfono (pestaña Registro, tipo Rechazados).

Cada `401` cuenta para el bloqueo por fuerza bruta de la IP.

## `ip-no-permitida`

La clave tiene una lista de redes permitidas y la petición viene de fuera de ellas. Se configura al
crear o editar la clave en el teléfono.

## `sin-permiso`

La clave no tiene el ámbito que pide la ruta:

| Ámbito | Rutas |
|---|---|
| `ENVIAR` | `POST /mensajes`, `POST /lotes`, `DELETE /mensajes/{id}` |
| `CONSULTAR` | `GET /mensajes`, `GET /mensajes/{id}`, `GET /cola` |
| `ADMIN` | `POST /cola/pausa`, `POST /cola/reanudar`; además ve los mensajes de todas las claves |

## `no-encontrado`

La ruta no existe, o el mensaje no existe. Un mensaje de otra clave también da `404` a una clave
sin `ADMIN`: no se distingue «no existe» de «no es tuyo».

## `metodo-no-permitido`

La ruta existe, pero no con ese método. La cabecera `Allow` dice cuáles admite.

## `conflicto-idempotencia`

La `Idempotency-Key` ya se usó, con la misma clave de la API, para una petición **con otro
contenido**. Con el mismo contenido no hay error: el teléfono devuelve el mensaje que ya creó,
con `Idempotent-Replayed: true`, y no manda otro SMS.

En un lote, la clave vale para el lote entero. Cada mensaje se guarda con `clave#índice`.

## `no-cancelable`

Solo se cancela lo que sigue `EN_COLA`. Un mensaje `ENVIANDO` ya está en la radio y no se puede
parar.

## `tipo-no-soportado`

El cuerpo tiene que ir con `Content-Type: application/json`.

## `validacion`

Uno o más campos no son válidos. `errores` dice cuáles. En un lote, cada error trae `indice`, la
posición del mensaje, y **no se admite ninguno** de los mensajes del lote (todo o nada).

| Campo | Reglas |
|---|---|
| `destino` | Obligatorio. E.164 (`+523311298770`) o número nacional, al que se le pone el prefijo de país de los ajustes. Tiene que pasar las listas de prefijos permitidos y bloqueados |
| `texto` | Obligatorio, hasta 1600 caracteres, y no más partes SMS que el máximo de los ajustes (3 por defecto) |
| `referencia` | Opcional, hasta 100 caracteres |
| `caducaEn` | Opcional. ISO 8601 con zona (`2026-11-01T18:30:00Z`), y en el futuro |
| `mensajes` | En un lote: de 1 al máximo de los ajustes (100 por defecto) |
| `Idempotency-Key` | De 1 a 100 caracteres: letras, dígitos y `- _ : .` |
| `estado`, `desde`, `limite`, `cursor` | Filtros de `GET /mensajes` |

## `ritmo-excedido`

La clave ha hecho más peticiones por minuto de las que tiene permitidas. `Retry-After` dice los
segundos que hay que esperar.

## `cuota-agotada`

Admitir la petición superaría la cuota diaria, del teléfono o de la clave. La cuota se cuenta en
**partes SMS**, no en mensajes. `Retry-After` dice los segundos que faltan para la medianoche del
teléfono.

## `error-interno`

Fallo inesperado del teléfono. Se puede reintentar con la misma `Idempotency-Key` sin riesgo de
duplicar el SMS.
