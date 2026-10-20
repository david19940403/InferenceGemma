# Revisión de exposición en GitHub · 2026-10-20

Repositorio público: `david19940403/InferenceGemma`. Se consultaron las dos ramas remotas (`main` y `codex/terminal-infraestructura`), su historial alcanzable y el código local preparado para publicar. GitHub no tenía releases ni artefactos de Actions publicados en el momento de la consulta.

## Resultado

Gitleaks 8.30.1, ejecutado localmente con salida redactada, no detectó secretos en el historial ni en los archivos de código. Además, una comparación de 275 blobs históricos con las credenciales locales de prueba no encontró los tokens del catálogo/panel ni el cuerpo de la clave SSH de prueba. No aparecieron archivos de base de datos, claves privadas, almacenes de firma, modelos o configuraciones locales sensibles entre los nombres del historial revisado.

`examples/agent-config.example.json` contiene marcadores `REEMPLAZAR_...`; el bloque PEM es un ejemplo sin una clave real. Los programas generan claves aleatorias en ejecución y las escriben en `.cache/`, que está excluida de Git. Las configuraciones de Android se guardan cifradas bajo `noBackupFilesDir`.

## Datos encontrados y medidas

| Dato | Alcance y medida |
|---|---|
| Número de serie del teléfono en `.idea/deploymentTargetSelector.xml` | Identificador de dispositivo, sin credenciales de acceso. Se deja de versionar la selección y el estado local de dispositivos de Android Studio. La copia local se conserva. |
| IP privada de ComfyUI en código anterior | Dato de topología local, sin token. Sigue en código/historial antiguo; no se considera una credencial. |
| Capturas, claves, tokens, APK, modelos y configuración de pruebas | Se mantienen fuera del repositorio. Se amplía `.gitignore` para claves, `.env`, bases locales, modelos, cifrados y `tmp/`. |

Los datos que ya existían en commits anteriores siguen accesibles en el historial y en `main`. No se reescribió el historial ni se hizo force-push. Si apareciera una credencial real expuesta, eliminar un archivo del último commit sería insuficiente: habría que revocarla y evaluar la limpieza del historial y de copias externas.

## Alcance operativo

El nuevo panel **Red** exige clave o sesión HttpOnly/SameSite Strict y no devuelve claves SSH. HTTP está pensado para una red confiable o VPN. El servidor API Ollama original es independiente y no hereda esa autenticación; mantenlo detenido si solo necesitas el nuevo panel remoto.

Este resultado corresponde a las referencias y archivos inspeccionados; un escáner no demuestra la ausencia absoluta de información sensible. No se inspeccionaron forks externos, copias ajenas ni contenido privado de otras cuentas.

Evidencias locales excluidas de Git: `.cache/security-audit/history.log`, `source.log` y los informes JSON redactados. Los archivos privados de `.cache/` se conservaron localmente y no se enviaron a un servicio externo: el análisis se ejecutó en la PC.
