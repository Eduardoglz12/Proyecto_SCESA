package mx.cetis24.scesa

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.*
import mx.cetis24.scesa.database.DatabaseFactory.dbQuery
import mx.cetis24.scesa.database.RegistrosAsistencia
import mx.cetis24.scesa.database.Alumnos
import org.jetbrains.exposed.sql.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import io.ktor.http.content.*
import java.io.InputStreamReader

// 1. MODELOS DE DATOS

@kotlinx.serialization.Serializable
data class AlumnoRequest(
    val numeroControl: String,
    val nombreCompleto: String,
    val grado: Int,
    val grupo: String,
    val turno: String,
    val nombreTutor: String,
    val emailTutor: String
)

@kotlinx.serialization.Serializable
data class EscaneoRequest(val numeroControl: String, val operadorId: Int)

@kotlinx.serialization.Serializable
data class AsistenciaRespuesta(
    val id: Int,
    val numeroControl: String,
    val nombre: String,
    val grupo: String,
    val turno: String,
    val evento: String,
    val fecha: String,
    val operador: Int
)

@kotlinx.serialization.Serializable
data class StatsDashboard(
    val totalAlumnos: Long,
    val entradasHoy: Long,
    val presentes: Long,
    val alertas: Int
)

// 2. LÓGICA DE CORREO (RESEND)

val httpClient = HttpClient(CIO) {
    install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        })
    }
}

suspend fun enviarCorreoSalida(email: String, nombre: String, horaEntrada: String, horaSalida: String) {
    val apiKey = System.getenv("RESEND_API_KEY") ?: return
    try {
        httpClient.post("https://api.resend.com/emails") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("from", "SCESA - CETIS 24 <onboarding@resend.dev>")
                put("to", JsonArray(listOf(JsonPrimitive(email))))
                put("subject", "Resumen de Asistencia - $nombre")
                put("html", """
                    <div style="font-family: sans-serif; border: 1px solid #eee; padding: 20px; border-radius: 10px;">
                        <h2 style="color: #1A3A5C;">Resumen de Jornada Escolar</h2>
                        <p>Estimado Tutor,</p>
                        <p>Le informamos los horarios registrados hoy para el alumno <b>$nombre</b>:</p>
                        <ul style="list-style: none; padding: 0;">
                            <li style="margin-bottom: 10px;">
                                <span style="color: #22C55E; font-weight: bold;">● Hora de Entrada:</span> $horaEntrada
                            </li>
                            <li style="margin-bottom: 10px;">
                                <span style="color: #EF4444; font-weight: bold;">● Hora de Salida:</span> $horaSalida
                            </li>
                        </ul>
                        <hr style="border: 0; border-top: 1px solid #eee; margin: 20px 0;">
                        <p style="font-size: 12px; color: #666;">Este es un mensaje automático del sistema SCESA - CETIS 24.</p>
                    </div>
                """.trimIndent())
            })
        }
    } catch (e: Exception) {
        println("Error enviando correo: ${e.message}")
    }
}

suspend fun ejecutarCierreDeJornada() {
    val zonaCoahuila = ZoneId.of("America/Monterrey")
    val hoy = ZonedDateTime.now(zonaCoahuila).toLocalDate()
    val hoyInicio = hoy.atStartOfDay()

    try {
        println("🔄 Iniciando revisión automática de Cierre de Jornada...")

        // 1. Obtener los Números de Control que registraron ENTRADA hoy
        val alumnosConEntrada = dbQuery {
            RegistrosAsistencia.select {
                (RegistrosAsistencia.tipoEvento eq "ENTRADA") and (RegistrosAsistencia.timestampRegistro greaterEq hoyInicio)
            }.map { it[RegistrosAsistencia.numeroControl] }.distinct()
        }

        // 2. Obtener los Números de Control que registraron SALIDA hoy
        val alumnosConSalida = dbQuery {
            RegistrosAsistencia.select {
                (RegistrosAsistencia.tipoEvento eq "SALIDA") and (RegistrosAsistencia.timestampRegistro greaterEq hoyInicio)
            }.map { it[RegistrosAsistencia.numeroControl] }.distinct()
        }

        // 3. Entradas menos Salidas = Los que faltaron
        val faltaronDeSalir = alumnosConEntrada.filterNot { it in alumnosConSalida }

        if (faltaronDeSalir.isEmpty()) {
            println("Todos los alumnos registraron su salida correctamente. No hay alertas.")
            return
        }

        println("Se detectaron ${faltaronDeSalir.size} alumnos sin salida. Enviando correos...")

        // 4. Buscar los correos y enviar la alerta
        dbQuery {
            Alumnos.select { Alumnos.numeroControl inList faltaronDeSalir }
                .forEach { row ->
                    val email = row[Alumnos.emailTutor] ?: ""
                    val nombre = row[Alumnos.nombreCompleto]

                    if (email.isNotBlank()) {
                        // Lanzamos una corrutina por cada correo para que se envíen rápido
                        kotlinx.coroutines.GlobalScope.launch {
                            enviarAlertaOmisionSalida(email, nombre)
                        }
                    }
                }
        }
    } catch (e: Exception) {
        println("Error en el Cierre de Jornada automático: ${e.message}")
    }
}

// Una variante de la función de correo con un mensaje de "Alerta"
suspend fun enviarAlertaOmisionSalida(email: String, nombre: String) {
    val apiKey = System.getenv("RESEND_API_KEY") ?: return
    try {
        httpClient.post("https://api.resend.com/emails") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("from", "SCESA - CETIS 24 <onboarding@resend.dev>")
                put("to", JsonArray(listOf(JsonPrimitive(email))))
                put("subject", "⚠️ AVISO: Omisión de registro de salida - $nombre")
                put("html", """
                    <div style="font-family: sans-serif; border: 1px solid #EF4444; padding: 20px; border-radius: 10px;">
                        <h2 style="color: #EF4444;">Aviso de Sistema</h2>
                        <p>Estimado Tutor,</p>
                        <p>El sistema <b>SCESA</b> detectó que el alumno <b>$nombre</b> registró su entrada el día de hoy, pero <b>NO registró su salida</b> al finalizar el turno escolar.</p>
                        <p>Esto puede ocurrir si el alumno olvidó escanear su credencial al retirarse del plantel.</p>
                        <p style="font-size: 12px; color: #666;">Este es un mensaje automático del sistema.</p>
                    </div>
                """.trimIndent())
            })
        }
    } catch (e: Exception) {
        println("Error enviando alerta a $email: ${e.message}")
    }
}

// 3. CONFIGURACIÓN DE RUTAS
fun Application.configureRouting() {
    routing {

        get("/") { call.respondText("Servidor SCESA Operativo") }

        // GESTIÓN DE ALUMNOS (GET y POST)

        get("/api/alumnos") {
            try {
                val lista = dbQuery {
                    Alumnos.selectAll().map {
                        AlumnoRequest(
                            numeroControl = it[Alumnos.numeroControl],
                            nombreCompleto = it[Alumnos.nombreCompleto],
                            grado = it[Alumnos.grado],
                            grupo = it[Alumnos.grupo],
                            turno = it[Alumnos.turno],
                            nombreTutor = it[Alumnos.nombreTutor] ?: "",
                            emailTutor = it[Alumnos.emailTutor] ?: ""
                        )
                    }
                }
                call.respond(lista)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error: ${e.message}")
            }
        }

        post("/api/alumnos") {
            try {
                val request = call.receive<AlumnoRequest>()
                dbQuery {
                    val existe = Alumnos.select { Alumnos.numeroControl eq request.numeroControl }.any()
                    if (existe) throw IllegalArgumentException("El alumno ya existe.")

                    Alumnos.insert {
                        it[numeroControl] = request.numeroControl // ¡Importante!
                        it[nombreCompleto] = request.nombreCompleto
                        it[grado] = request.grado
                        it[grupo] = request.grupo
                        it[turno] = request.turno
                        it[nombreTutor] = request.nombreTutor
                        it[emailTutor] = request.emailTutor
                    }
                }
                call.respond(HttpStatusCode.Created, "Alumno registrado.")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Error: ${e.message}")
            }
        }

        // ASISTENCIA Y ESCANEO

        post("/api/asistencia/escanear") {
            try {
                val request = call.receive<EscaneoRequest>()
                val zonaCoahuila = ZoneId.of("America/Monterrey")
                val ahora = ZonedDateTime.now(zonaCoahuila).toLocalDateTime()
                val hoyInicio = ahora.toLocalDate().atStartOfDay()

                // 1. Obtenemos info completa (incluyendo email del tutor para Resend)
                val info = dbQuery {
                    Alumnos.select { Alumnos.numeroControl eq request.numeroControl }
                        .map { row ->
                            mapOf(
                                "nombre" to row[Alumnos.nombreCompleto],
                                "grupo" to row[Alumnos.grupo],
                                "turno" to row[Alumnos.turno],
                                "email" to (row[Alumnos.emailTutor] ?: "")
                            )
                        }.singleOrNull()
                } ?: return@post call.respond(HttpStatusCode.NotFound, "Número de control inválido")

                // 2. Lógica de tipo de evento y cooldown
                val ultimo = dbQuery {
                    RegistrosAsistencia.select {
                        (RegistrosAsistencia.numeroControl eq request.numeroControl) and
                                (RegistrosAsistencia.timestampRegistro greaterEq hoyInicio)
                    }.orderBy(RegistrosAsistencia.timestampRegistro to SortOrder.DESC).limit(1)
                        .map { it[RegistrosAsistencia.tipoEvento] to it[RegistrosAsistencia.timestampRegistro] }
                        .singleOrNull()
                }

                if (ultimo != null && Duration.between(ultimo.second, ahora).toMinutes() < 1) {
                    return@post call.respond(HttpStatusCode.Conflict, "Espera 1 minuto.")
                }

                val nuevoTipo = when (ultimo?.first) {
                    null -> "ENTRADA"
                    "ENTRADA" -> "SALIDA"
                    else -> return@post call.respond(HttpStatusCode.Forbidden, "Ciclo diario completo.")
                }

                // 3. Registrar
                dbQuery {
                    RegistrosAsistencia.insert {
                        it[numeroControl] = request.numeroControl
                        it[tipoEvento] = nuevoTipo
                        it[metodoRegistro] = "QR"
                        it[timestampRegistro] = ahora
                        it[operadorId] = request.operadorId
                    }
                }

                // 4. ENVÍO DE CORREO SI ES SALIDA
                if (nuevoTipo == "SALIDA" && info["email"]!!.isNotBlank()) {
                    // 1. Buscamos la hora de entrada de hoy para este alumno
                    val timestampEntrada = dbQuery {
                        RegistrosAsistencia.select {
                            (RegistrosAsistencia.numeroControl eq request.numeroControl) and
                                    (RegistrosAsistencia.tipoEvento eq "ENTRADA") and
                                    (RegistrosAsistencia.timestampRegistro greaterEq hoyInicio)
                        }
                            .orderBy(RegistrosAsistencia.timestampRegistro to SortOrder.DESC)
                            .limit(1)
                            .map { it[RegistrosAsistencia.timestampRegistro] }
                            .singleOrNull()
                    }

                    // 2. Disparamos el correo con ambos datos
                    launch {
                        val formato = DateTimeFormatter.ofPattern("hh:mm a")
                        val horaSalidaTxt = ahora.format(formato)

                        // Si por alguna razón no hay registro de entrada, ponemos "No registrada"
                        val horaEntradaTxt = timestampEntrada?.format(formato) ?: "No registrada"

                        enviarCorreoSalida(
                            info["email"]!!,
                            info["nombre"]!!,
                            horaEntradaTxt,
                            horaSalidaTxt
                        )
                    }
                }
                call.respond(HttpStatusCode.Created, "$nuevoTipo registrado para ${info["nombre"]}")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Error: ${e.message}")
            }
        }

        post("/api/alumnos/importar") {
            try {
                val multipart = call.receiveMultipart()
                var importados = 0
                var omitidos = 0

                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        // Leer el archivo enviado
                        val fileBytes = part.streamProvider().readBytes()
                        val fileContent = String(fileBytes, Charsets.UTF_8)

                        // Separar por líneas e ignorar líneas en blanco
                        val lines = fileContent.lines().filter { it.isNotBlank() }

                        // Detectar si la primera línea es el encabezado y saltarla
                        val dataLines = if (lines.firstOrNull()?.contains("numeroControl", ignoreCase = true) == true) {
                            lines.drop(1)
                        } else {
                            lines
                        }

                        dbQuery {
                            for (line in dataLines) {
                                // Separar por comas (CSV)
                                val cols = line.split(",")

                                // Validar que la fila tenga las 7 columnas requeridas
                                if (cols.size >= 7) {
                                    val nc = cols[0].trim()

                                    // Verificar si el alumno ya existe para no duplicar
                                    val existe = Alumnos.select { Alumnos.numeroControl eq nc }.any()

                                    if (!existe) {
                                        Alumnos.insert {
                                            it[numeroControl] = nc
                                            it[nombreCompleto] = cols[1].trim()
                                            it[grado] = cols[2].trim().toIntOrNull() ?: 1
                                            it[grupo] = cols[3].trim().uppercase()
                                            it[turno] = cols[4].trim()
                                            it[nombreTutor] = cols[5].trim()
                                            it[emailTutor] = cols[6].trim()
                                        }
                                        importados++
                                    } else {
                                        omitidos++
                                    }
                                }
                            }
                        }
                    }
                    part.dispose()
                }

                call.respond(HttpStatusCode.OK, "Se importaron $importados alumnos. Se omitieron $omitidos (ya existían o error de formato).")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Error al procesar el archivo: ${e.message}")
            }
        }

        get("/api/asistencia") {
            try {
                val historial = dbQuery {
                    (RegistrosAsistencia innerJoin Alumnos)
                        .slice(RegistrosAsistencia.id, RegistrosAsistencia.numeroControl, Alumnos.nombreCompleto,
                            Alumnos.grupo, Alumnos.turno, RegistrosAsistencia.tipoEvento,
                            RegistrosAsistencia.timestampRegistro, RegistrosAsistencia.operadorId)
                        .selectAll().orderBy(RegistrosAsistencia.timestampRegistro to SortOrder.DESC)
                        .map {
                            AsistenciaRespuesta(
                                id = it[RegistrosAsistencia.id],
                                numeroControl = it[RegistrosAsistencia.numeroControl],
                                nombre = it[Alumnos.nombreCompleto],
                                grupo = it[Alumnos.grupo],
                                turno = it[Alumnos.turno],
                                evento = it[RegistrosAsistencia.tipoEvento],
                                fecha = it[RegistrosAsistencia.timestampRegistro].toString(),
                                operador = it[RegistrosAsistencia.operadorId]
                            )
                        }
                }
                call.respond(historial)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error: ${e.message}")
            }
        }

        get("/api/asistencia/stats") {
            try {
                val stats = dbQuery {
                    val zona = ZoneId.of("America/Monterrey")
                    val hoy = ZonedDateTime.now(zona).toLocalDate().atStartOfDay()

                    val total = Alumnos.selectAll().count()
                    val entradas = RegistrosAsistencia.select {
                        (RegistrosAsistencia.tipoEvento eq "ENTRADA") and (RegistrosAsistencia.timestampRegistro greaterEq hoy)
                    }.count()
                    val salidas = RegistrosAsistencia.select {
                        (RegistrosAsistencia.tipoEvento eq "SALIDA") and (RegistrosAsistencia.timestampRegistro greaterEq hoy)
                    }.count()

                    StatsDashboard(total, entradas, entradas - salidas, 0)
                }
                call.respond(stats)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error")
            }
        }

        // Editar Alumno
        put("/api/alumnos/{nc}") {
            val nc = call.parameters["nc"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val request = call.receive<AlumnoRequest>()
            try {
                dbQuery {
                    Alumnos.update({ Alumnos.numeroControl eq nc }) {
                        it[nombreCompleto] = request.nombreCompleto
                        it[grado] = request.grado
                        it[grupo] = request.grupo
                        it[turno] = request.turno
                        it[nombreTutor] = request.nombreTutor
                        it[emailTutor] = request.emailTutor
                    }
                }
                call.respond(HttpStatusCode.OK, "Datos actualizados correctamente")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error al editar: ${e.message}")
            }
        }

        //Eliminar Alumno
        delete("/api/alumnos/{nc}") {
            val nc = call.parameters["nc"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            try {
                dbQuery {
                    // Si el alumno ya tiene asistencias podrías necesitarse borrar sus registros primero
                    // o usar un borrado lógico. Aquí lo borramos físicamente:
                    Alumnos.deleteWhere { Alumnos.numeroControl eq nc }
                }
                call.respond(HttpStatusCode.OK, "Alumno eliminado del sistema")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error al eliminar: ${e.message}")
            }
        }

    }
}