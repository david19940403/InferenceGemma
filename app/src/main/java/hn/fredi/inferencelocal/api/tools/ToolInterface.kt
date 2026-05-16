package hn.fredi.inferencelocal.api.tools

/**
 * Interfaz base para herramientas con soporte para llamadas estructuradas (JSON).
 */
interface Tool {
    val name: String
    val description: String
    val parameters: String // Ejemplo: "{ \"query\": \"string\" }"
    
    /**
     * Mensaje que se muestra al usuario mientras se ejecuta la herramienta.
     */
    val loadingMessage: String get() = "* (Ejecutando $name...)*\n\n"
    
    /**
     * Ejecuta la herramienta con argumentos extraídos del modelo.
     */
    suspend fun call(args: Map<String, Any>, context: Map<String, Any> = emptyMap()): String
    
    /**
     * Fallback para detección manual si el modelo no soporta JSON nativo.
     */
    fun shouldActivate(content: String): Boolean = false
}
