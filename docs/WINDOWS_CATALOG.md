# Catalogo Windows

Los scripts se instalan en `C:\Users\David\.toolsexecutiosn`. El PC mantiene el
Tool Store y ejecuta las herramientas por SSH; el Tab S9 selecciona herramientas
y argumentos con el modelo, y el S24 envia las instrucciones por VPN.

El catalogo inicial agrega 19 herramientas y conserva `sysinfo_windows` y
`get-sysinfo.ps1`. No hace falta reinstalar Android al ampliar este catalogo.
La tool `tools_help` se suma a ese conjunto: 21 herramientas en total en esta PC.

| Grupo | Tools | Ejemplos de instrucciones |
|---|---|---|
| Ayuda | `tools_help` | Que herramientas tienes; lista tools; ayuda de red; explica files_read_text |
| Almacenamiento | `windows_disk_space` | Cuanto espacio libre queda en el disco C |
| Red | `windows_network_adapters`, `network_ping`, `network_tcp_port`, `network_dns_lookup` | Muestra las IP de la VPN; haz ping a 192.168.9.2; comprueba el puerto 8000 de 192.168.9.6 |
| Procesos | `windows_process_list`, `windows_process_stop` | Muestra los procesos que consumen mas memoria; cierra el proceso con PID 12345 |
| Servicios | `windows_service_list`, `windows_service_control` | Consulta el estado de Spooler; reinicia el servicio Spooler |
| Diagnostico | `windows_event_errors` | Muestra los ultimos errores de System de las ultimas 24 horas |
| Consultar archivos | `files_list`, `files_read_text`, `files_find_name` | Lista C:\Users\David\Documents; lee C:\Users\David\Documents\nota.txt; busca informe en C:\Users\David\Documents |
| Modificar archivos | `files_create_directory`, `files_create_text`, `files_append_text`, `files_copy`, `files_move` | Crea C:\Users\David\Documents\Pruebas; crea una nota; copia o renombra un archivo indicando ambas rutas |
| Escritorio | `windows_open_application` | Abre la calculadora; abre el bloc de notas; abre el explorador; abre el administrador de tareas |

Usa rutas absolutas e indica los destinos expresamente. Los ejemplos con
acciones modifican el equipo cuando se ejecutan; las pruebas automatizadas
solo modifican archivos propios y cierran un proceso creado para la prueba.

## Comportamiento

- Los resultados y errores siguen el contrato JSON y los codigos de salida del agente.
- Crear, copiar y mover rechazan un destino existente. Anadir texto conserva el contenido.
- Las tools de archivos rechazan credenciales, rutas del sistema para escritura,
  enlaces, carpetas de claves y modificaciones de los propios scripts del catalogo.
- Detener procesos exige PID y pertenencia a la cuenta SSH; se protegen procesos
  del sistema, SSH, VPN y el runtime del agente.
- Controlar servicios exige nombre exacto. Se protegen SSH, Tool Store, VPN y
  servicios de seguridad/conectividad. Otros servicios pueden exigir privilegios.
- Abrir aplicaciones usa una tarea temporal en la sesion interactiva de la cuenta
  SSH. Debe haber un escritorio iniciado con esa misma cuenta. Devuelve `accepted`:
  indica que Windows inicio la tarea; no verifica visualmente la ventana.
- Leer texto permite hasta 2000 caracteres. Buscar nombres examina hasta cuatro
  niveles y 500 carpetas. Los listados tienen limites explicitos.
- El agente conserva solo los primeros 400 caracteres de cada resultado para
  la siguiente inferencia. Pide resultados breves y limita listados; para analisis
  extensos habra que ampliar el manejo de resultados del agente.

## Instalar o actualizar

### Ayuda del catalogo

`tools_help` consulta el catalogo activo del Tool Store; incluye tambien tools
agregadas desde el administrador web. Modos: `summary` (categorias y total),
`list` (nombres), `search` (tema) y `detail` (nombre exacto, parametros requeridos,
opciones y ejemplo). `page` comienza en 1 y `limit` admite de 1 a 10; `next`
indica la pagina siguiente. Se pagina para conservar resultados breves.
Las consultas como `que herramientas tienes`, `lista tools pagina 2`,
`ayuda de red` y `ayuda de files_read_text` se resuelven directamente con esta
tool. El agente muestra el inventario devuelto, sin reinterpretar sus cantidades
o nombres con otra inferencia del modelo.

```powershell
C:\Users\David\.toolsexecutiosn\help.ps1 summary
C:\Users\David\.toolsexecutiosn\help.ps1 list '' 1 5
C:\Users\David\.toolsexecutiosn\help.ps1 search archivos
C:\Users\David\.toolsexecutiosn\help.ps1 detail files_read_text
```

El instalador protege el token con DPAPI de Windows en `catalog-access.enc`,
legible por la cuenta que instala y ejecuta las tools. Al cambiar el token o la
URL del catalogo, ejecuta otra vez el instalador. No se imprimen credenciales.

```powershell
.\scripts\install-windows-catalog.ps1
.\scripts\test-windows-catalog.ps1
```

El instalador utiliza el token de `agent-config.local.json`, copia solo los
scripts administrados, guarda una copia de los archivos previos en
`.cache/catalog-backups` y registra/actualiza por nombre sin duplicar tools.
Conserva el resto del catalogo. El manifiesto instalado se guarda como
`C:\Users\David\.toolsexecutiosn\installed-catalog.json`.

Para probar tambien la apertura de la calculadora:

```powershell
.\scripts\test-windows-catalog.ps1 -TestDesktop
```

La fuente versionada esta en `remote-scripts/windows/catalog/`; edita ahi y
vuelve a instalar para conservar los cambios en GitHub.

## Otros equipos

Cada equipo necesita sus propios scripts, usuario SSH, clave autorizada y clave
publica del host verificada en Android. El nombre/IP debe coincidir exactamente
con `hosts` de la configuracion del Tab. Esta instalacion solo registra la PC
actual; no supone que haya acceso a otros equipos.

El instalador acepta `-Destination`, `-TargetHost`, `-ConfigPath`, `-CatalogUrl`
y `-NamePrefix`. Usa un prefijo diferente, por ejemplo `oficina_`, para otro
host porque los nombres de tools son unicos en el catalogo. Ejecuta la copia de
scripts en el equipo destino (`-NoRegister`) y registra desde un equipo que
pueda acceder al Tool Store usando la misma ruta absoluta remota. El parametro
`-Destination` es una ruta de ese Windows; el instalador no copia por SSH.

No hay herramientas de shell libre, borrado, apagado o cambio de credenciales
en este primer catalogo.
