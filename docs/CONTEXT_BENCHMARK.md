# Contexto real en Tab S9 / Gemma 4 E4B

## Alcance

Mediciones sobre el dispositivo, no estimaciones por caracteres: Samsung Tab S9
SM-X810 (12 GB), modelo `gemma-4-E4B-it.litertlm` de 3.659.530.240 bytes,
LiteRT-LM Android `0.11.0-rc1`, backend GPU. La configuración de producción
encontrada usaba 1.984 tokens. No se modifica durante estas pruebas.

El modelo original anuncia 128K de contexto:
https://huggingface.co/google/gemma-4-E4B-it
Eso no garantiza ese tamaño con este archivo exportado, runtime y dispositivo.
La [distribución LiteRT de referencia](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm)
anuncia **hasta 32K**, no 128K. No se verificó el hash de la copia del
dispositivo contra esa distribución; las mediciones describen el archivo
real instalado, no garantizan las capacidades de otras exportaciones.

## Método

- Motor nuevo por tamaño; conversación nueva por caso, sin SSH ni ejecución de tools externas.
- Calibración doble de tokens reales mediante `getBenchmarkInfo()`.
- Entrada a mitad de presupuesto, cerca del límite dejando 256 tokens libres,
  entrada deliberadamente excesiva y recuperación posterior al error.
- Recuperación de tres códigos situados al principio, centro y final de una
  entrada larga: dos casos repetitivos y uno con palabras variadas.
- En 4.096: generación más larga y protocolo nativo de llamada/respuesta de tool,
  incluyendo un control alternativo con rol `user` frente al rol nativo `tool`.
- Registro de tokens prefill/decode, primer token, tiempo total, PSS y RAM disponible.
- Corte preventivo por menos de 768 MiB libres después de cargar; timeout por petición.

La prueba de recuperación es sintética, no una evaluación general de calidad.
Aceptar tokens no equivale a recordar su contenido. Las respuestas muy cortas
no ofrecen una medición estable de velocidad sostenida. PSS incluye páginas
compartidas: no equivale a RAM privada y no debe sumarse a otros procesos.

## Reproducir

Requiere APK principal debug instalada del mismo proyecto, ADB autorizado y un
modelo accesible para la app. Detiene temporalmente la app/servidor: no ejecutar
con trabajos activos. No borra datos, credenciales ni configuraciones. Reiniciar
el proceso sí puede invalidar las sesiones web y requerir autenticación del cliente.

```powershell
.\scripts\test-device-context.ps1 `
  -Serial '<serial de adb devices>' `
  -ModelPath '/storage/emulated/0/Android/data/hn.fredi.inferencelocal/files/gemma-4-E4B-it.litertlm' `
  -ContextTokens 4096,6144,8192 `
  -AllowInterrupt
```

Resultados locales: `.cache/context-probe/results.jsonl` y salida de cada
instrumentación. El JSONL del dispositivo es acumulativo; usar `run_id` para
separar ejecuciones. El runner abre la app al terminar, pero el operador debe
reactivar el servidor remoto y cargar el modelo. No los activa sin autenticación.

`OK (1 test)` significa que terminó la instrumentación, no que todos los
experimentos pasaron: revisar `request_error`, `engine_error`, `recall_pass`
y `tool_followup.pass`. El error de `overflow` es intencionado; el control de
rol alternativo es informativo y no presupone que el SDK deba rechazarlo.

## Primera exploración

| Contexto configurado | Prefill probado (recuperación) | Primer token | PSS | RAM disponible | Códigos correctos |
|---:|---:|---:|---:|---:|---|
| 2.048 | 1.813 | 3,27 s | 4.279 MiB | 2.953 MiB | Sí |
| 4.096 | 3.861 | 6,26 s | 4.969 MiB | 2.732 MiB | Sí |
| 8.192 | 7.957 | 15,51 s | 6.286 MiB | 1.567 MiB | No |
| 16.384 | Solo calibración corta | — | 8.717 MiB | 475 MiB | No evaluado |

16.384 cargó, pero se detuvo manualmente al detectar margen insuficiente de RAM.
La salida `Process crashed` de esa instrumentación corresponde al `force-stop`
preventivo, no demuestra un crash espontáneo del motor. No se probaron tamaños
mayores. El límite físico máximo no queda establecido por estas mediciones.

Los tres tamaños completos rechazaron el exceso de tokens sin cerrar el motor
y respondieron a una petición corta posterior.

## Repetición y calidad

| Presupuesto | Pruebas de recuperación larga correctas | Prefill real | Observación |
|---:|---:|---:|---|
| 2.048 | 2 / 2 | 1.811–1.813 | Repetición y palabras variadas correctas |
| 4.096 | 2 / 3 | 3.859–3.861 | Repetición correcta; texto variado perdió letras del código final |
| 6.144 | 0 / 3 | 5.907–5.909 | Códigos incompletos o mezclados |
| 8.192 | 0 / 3 | 7.955–7.957 | Códigos incompletos o mezclados |

No atribuir automáticamente esos fallos a un máximo duro del runtime:
son errores de calidad observados con esta combinación de modelo, backend y
prompts. Hace falta comparar exportaciones, cuantización y backends para
aislar su causa. El benchmark no prueba que el modelo original tenga un límite
de 4K ni permite garantizar fidelidad perfecta por debajo de 4K.

La prueba de generación en 4.096 produjo **292 tokens en 29,84 s**,
con primer token en 0,51 s y decode de **9,96 tokens/s**.
Las mediciones de primer token de las entradas largas no deben confundirse
con la latencia de una petición corta.

El ciclo nativo `locate_file → respuesta → finish_transaction` conservó la
ruta exacta y emitió una sola llamada por turno. Pasó tanto con respuesta de
rol `tool` como con el control de rol `user`. Por tanto esta prueba aislada
**no reprodujo** el error de formato reportado en la app y no demuestra que
el rol de mensaje sea su causa. No se ejecutaron acciones reales en Windows.

Recomendación provisional: presupuesto de 4.096 con reserva para salida y
definiciones de tools, sin llenarlo innecesariamente; 8.192 solo experimental.
El control de 179 tokens recuperó los tres códigos correctamente tanto con
presupuesto de 2.048 como de 4.096. Mantener entradas compactas alrededor de
2K como punto inicial, no interpretar 4K como garantía de calidad.
Guardar y resolver rutas/resultados exactos en el programa, no depender de
que el modelo los copie siempre bien. La configuración anterior se restaura;
esta prueba no instala una APK principal ni amplía límites de producción.
