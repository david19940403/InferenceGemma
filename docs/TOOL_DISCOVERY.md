# Catálogo dinámico con presupuesto de contexto

Configuración sugerida para el Tab S9 / E4B: contexto **4.096**, backend GPU,
presupuesto de definiciones de herramientas **1.500 tokens estimados**.
Ejemplo completo: `engine-settings.example.json`.

Se guarda en el Android como `no_backup/engine-settings.json`. Puede editarse
desde Motor → Engine terminal o desde el panel remoto, con el modelo liberado.
El campo JSON es `toolBudgetTokens` (256–2048); por defecto 1500.

## Selección y descubrimiento

1. Cada Tool Store devuelve hasta 16 candidatos relevantes; el servidor Go acepta
   `top_k` entre 1 y 16 (el valor omitido sigue siendo 3 por compatibilidad).
2. La federación ordena hasta 32 candidatos. Android selecciona las definiciones
   completas que caben en el presupuesto; no existe un corte fijo de tres tools.
   Se acortan descripciones generales, nunca parámetros, enums ni campos requeridos.
3. El modelo dispone de `search_tools(query)` aunque la búsqueda inicial tenga
   resultados. Es una función interna: consulta el catálogo, no ejecuta SSH ni shell.
4. Una consulta exitosa reemplaza los candidatos por los nuevos resultados,
   priorizados para esa capacidad, en vez de acumular esquemas indefinidamente.
   Una consulta sin coincidencias conserva los anteriores. Los resultados de las
   acciones anteriores y la intención original se conservan.
5. Hasta tres solicitudes adicionales de búsqueda, tres ejecuciones SSH y siete
   evaluaciones por instrucción. Una búsqueda repetida no vuelve a consultar la
   red, pero consume un intento. El último turno solo permite responder.

Se validan las tools recuperadas, los hosts autorizados, los argumentos y las
plantillas antes de cualquier ejecución. `search_tools` y `finish_transaction`
están reservadas y no pueden registrarse como comandos de un catálogo.

## Qué significa el presupuesto

Los 1500 son un **techo estimado**, no un mínimo que deba rellenarse. Incluye
las funciones internas. No se repiten todos los esquemas dentro del texto del
prompt: se envían una sola vez mediante las definiciones nativas.

Este SDK no expone tokenización previa exacta. La estimación conservadora usa
UTF-8, palabras, identificadores y puntuación. El prompt y sus resultados reducen
el espacio para herramientas si crecen; se reserva espacio para generación y
formato del runtime. Un esquema que no cabe se omite entero. No se garantiza
un número exacto de tokens para cualquier entrada; el límite nativo de 4096
sigue siendo la última protección.

El monitor muestra número de tools, tokens estimados del catálogo y tokens
reales de entrada del último prefill. Si falta una capacidad, el modelo puede
consultarla en el siguiente turno sin ejecutar las acciones de las tools que
solo está inspeccionando. No se crean herramientas ni se amplían permisos.

## Verificación

- Pruebas unitarias: presupuesto, más de tres tools, esquemas grandes, búsquedas
  adicionales, caché de consultas repetidas, respuestas vacías y validaciones.
- Go: recuperación de diez herramientas en una consulta y nombres reservados.
- Tab S9 con E4B/GPU: 16 herramientas iniciales, catálogo estimado 1411 tokens,
  **837 tokens reales de entrada total**. El modelo pidió `search_tools("buscar archivo")`,
  obtuvo una tool ausente inicialmente, ejecutó la herramienta simulada y respondió
  con la ruta correcta. Los siguientes prefills fueron 357 y 408 tokens.
  No hubo ejecuciones reales de Windows en esta prueba.

La prueba de dispositivo `ToolDiscoveryDeviceTest` requiere el argumento opt-in
`runAgentDiscovery=true`; no carga modelos durante una ejecución de tests normal.

Las respuestas finales de texto se aceptan como texto, nunca se interpretan como
órdenes. Se colapsan duplicados idénticos del streaming; varias llamadas distintas
en el mismo turno se rechazan sin ejecutar ninguna.
