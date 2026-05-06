package mx.cetis24.scesa

import io.ktor.server.application.*
import io.ktor.server.netty.EngineMain
import mx.cetis24.scesa.database.DatabaseFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

fun main(args: Array<String>) {
    // Esto arranca el servidor Netty usando la configuración de 'application.yaml'
    EngineMain.main(args)
}

// Esta es la función principal de la aplicación.
fun Application.module() {
    // 1. Iniciamos la conexión a PostgreSQL
    DatabaseFactory.init()

    // 2. ACTIVAR PLUGINS
    configureResources()

    // 3. Cargamos los plugins que generó Ktor (están en los otros archivos .kt)
    configureSerialization()
    configureRouting()
    configureHTTP() // Configuración de CORS
    configurarAutomatizacion() //Activar automatización cierre de entradas
}

fun Application.configurarAutomatizacion() {
    launch {
        val zonaCoahuila = ZoneId.of("America/Monterrey")

        while (true) { // Bucle infinito que mantiene el proceso
            val ahora = ZonedDateTime.now(zonaCoahuila)

            // Queremos que se ejecute a las 7:00 PM (19:00 hrs)
            var proximaEjecucion = ahora.withHour(19).withMinute(0).withSecond(0).withNano(0)

            // Si ya pasaron las 7:00 PM de hoy, programamos para las 7:00 PM de mañana
            if (ahora.isAfter(proximaEjecucion)) {
                proximaEjecucion = proximaEjecucion.plusDays(1)
            }

            // Calculamos cuántos milisegundos faltan para la hora acordada
            val milisegundosDeEspera = Duration.between(ahora, proximaEjecucion).toMillis()

            println("Cron Job programado. El servidor dormirá por ${milisegundosDeEspera / 1000 / 60} minutos hasta las 17:00 hrs.")

            // Pausa el hilo en segundo plano (el cron)
            delay(milisegundosDeEspera)

            // Ejecuta el cierre
            ejecutarCierreDeJornada()
        }
    }
}