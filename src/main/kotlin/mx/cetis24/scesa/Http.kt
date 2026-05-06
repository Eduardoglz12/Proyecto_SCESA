package mx.cetis24.scesa

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureHTTP() {
    install(CORS) {
        // Permite que el Dashboard (Vite) se conecte al Backend
        anyHost()

        // Permite los métodos necesarios para SCESA
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)

        // Permite las cabeceras comunes
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)

        //Temporal mientras se implementa el Login
        allowCredentials = true
    }
}