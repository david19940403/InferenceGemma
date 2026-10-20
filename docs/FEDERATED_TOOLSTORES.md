# Catálogos por PC y catálogo central

El motor Android puede consultar hasta ocho Tool Stores: el principal y siete adicionales. Cada PC conserva su base SQLite y administra sus herramientas. También puedes mantener un catálogo central con herramientas destinadas a varios hosts. No es necesario replicar ni sincronizar las bases.

En **Terminal → Configurar Tool Store**, o en **Tool Store en la red** del panel web del Android servidor, pulsa **Agregar PC / catálogo**. Indica nombre, URL HTTP(S), estado activo y autenticación. El principal utiliza el token importado con la configuración SSH; cada adicional permite reutilizarlo o introducir uno propio. Dejar vacío el campo de un token ya guardado lo conserva. Puedes desactivar el principal y trabajar únicamente con otros catálogos.

Usa la dirección accesible desde Android, por ejemplo `http://10.12.14.1:8000` o `http://192.168.9.1:8000`. Cada entrada representa un servicio independiente, no interfaces alternativas del mismo servicio. Cambia la URL cuando el mismo PC cambie de dirección; no registres sus distintas interfaces como si fueran PCs diferentes. `127.0.0.1` representa el Android, salvo que hayas configurado expresamente un túnel ADB.

**Probar conexión** consulta los activos y muestra `ok`, `offline`, `unauthorized`, `invalid` o `error` para cada uno. La prueba no guarda cambios; pulsa **Guardar** para aplicarlos. Los estados corresponden a la última consulta/prueba, no a una vigilancia continua.

## Búsqueda y ejecución

- Las consultas se realizan en paralelo, con conexión de 2,5 s, lectura de 4 s y límite por consulta de 7,5 s. Una PC apagada no impide recuperar herramientas de las demás. Las operaciones de red usan hilos de IO y pueden tardar hasta su timeout de socket al cancelarse.
- Un catálogo que responde correctamente sin coincidencias cuenta como disponible. Si ninguno responde correctamente, se informa el fallo de conexión, sin intentar disfrazarlo mediante reformulaciones del modelo.
- Un nodo fallido no se vuelve a consultar en las reformulaciones de esa intención. La siguiente intención lo intenta de nuevo.
- Cada catálogo aporta hasta tres resultados. Se combinan y ordenan por coincidencia de palabras con nombre, descripción y host; se conservan hasta tres para respetar el contexto del modelo. Los empates mantienen el orden de configuración. No se comparan puntuaciones internas de bases diferentes.
- Las copias con el mismo nombre, transporte y parámetros se consideran una herramienta. Si comparten nombre pero difieren en destino, comando o parámetros, reciben nombres derivados estables que identifican el catálogo; el comando y sus argumentos se conservan.
- El catálogo indica **dónde se define** una herramienta. `target_host` indica **dónde se ejecuta**. Puede definirse centralmente y ejecutarse en otra PC.
- Añadir un catálogo no añade permisos SSH. Todos sus destinos deben existir en `hosts` de la configuración cifrada, con sus claves de host verificadas. Si devuelve destinos no autorizados, se descarta esa respuesta; los otros catálogos pueden continuar.
- Los tokens propios se guardan con AES-GCM y AndroidKeyStore en un archivo excluido de copias de seguridad. No se devuelven al navegador. Para HTTP, usa una LAN confiable o VPN; también se admiten endpoints HTTPS. No hay descubrimiento automático ni envío de tokens a equipos desconocidos.

## Contrato del Android servidor

`GET /remote/toolstore` devuelve la configuración pública. `PUT /remote/toolstore` acepta la misma estructura y, opcionalmente, `tokens` como actualización de escritura. Ambos requieren autenticación del acceso remoto, independiente de la autenticación de cada catálogo.

```json
{
  "url": "http://10.12.14.1:8000",
  "rewriteSearch": true,
  "primaryEnabled": true,
  "stores": [
    {
      "id": "oficina",
      "label": "PC oficina",
      "url": "http://192.168.9.2:8000",
      "enabled": true,
      "useImportedToken": false
    }
  ],
  "tokens": {"oficina": "<token>"}
}
```

En respuestas, `tokens` siempre está vacío y `hasToken` solo indica si existe un token propio guardado. Omite la entrada en `tokens` para conservarlo. Quitar un catálogo elimina su token de la siguiente configuración guardada. El identificador debe ser único, empezar con una letra minúscula y contener hasta 32 letras minúsculas, números, guiones o guiones bajos; `principal` está reservado. Las URL activas no pueden repetirse.

`POST /remote/toolstore/test` acepta un borrador con el mismo formato y devuelve `url`, `message` y `sources`, incluso cuando ningún nodo está disponible. Cada fuente contiene `id`, `label`, `url`, `status` y `toolCount`. La configuración inválida se rechaza con HTTP 400. `GET /remote/status` incluye `catalogs` con los últimos resultados. Al guardar configuración se limpian esos estados para evitar mostrar pruebas de destinos anteriores.

La configuración previa de una sola URL sigue funcionando. Se migra al almacenamiento cifrado al guardar; importar el JSON SSH actualiza la URL principal y conserva los catálogos adicionales. El protocolo de búsqueda de cada Tool Store continúa siendo `POST /tools/search` con `query` y `top_k`.

## Verificación

Las 23 pruebas JVM del proyecto incluyen consultas concurrentes, fallos parciales, todos los nodos desconectados, cancelación, credenciales por fuente, deduplicación, conflictos de nombres y límite global. La prueba en Samsung utilizó dos procesos Tool Store con bases SQLite y tokens independientes en esta misma PC, más un puerto inaccesible; también verificó autenticación incorrecta y respuestas públicas sin tokens. Con el principal desactivado, una intención real recuperó el diagnóstico VPN del catálogo alternativo y ejecutó el script por SSH. El panel web se comprobó con Chrome, incluidos borradores durante el refresco y limpieza de campos de credenciales al cerrar sesión. No representa una prueba entre dos PCs físicas distintas.
