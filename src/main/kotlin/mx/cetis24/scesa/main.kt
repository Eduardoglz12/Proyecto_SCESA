package mx.cetis24.scesa

import io.ktor.server.application.*
import io.ktor.server.netty.EngineMain
import mx.cetis24.scesa.database.DatabaseFactory

fun main(args: Array<String>) {
    // Esto arranca el servidor Netty usando la configuración de 'application.yaml'
    EngineMain.main(args)
}

// Esta es la función principal de tu aplicación.
// Aquí es donde "conectamos" todas las piezas del rompecabezas.
fun Application.module() {
    // 1. Iniciamos la conexión a PostgreSQL antes que nada
    DatabaseFactory.init()

    // 2. ACTIVAR PLUGINS
    configureResources()

    // 3. Cargamos los plugins que generó Ktor (están en los otros archivos .kt)
    configureSerialization()
    configureRouting()
    configureHTTP() // Aquí es donde vive la configuración de CORS
}