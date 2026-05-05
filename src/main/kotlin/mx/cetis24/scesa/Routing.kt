package mx.cetis24.scesa

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import kotlinx.serialization.json.*
import mx.cetis24.scesa.database.DatabaseFactory.dbQuery
import mx.cetis24.scesa.database.RegistrosAsistencia
import mx.cetis24.scesa.database.Alumnos
import org.jetbrains.exposed.sql.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

// ==========================================
// 1. MODELOS DE DATOS
// ==========================================

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

// ==========================================
// 2. LÓGICA DE CORREO (RESEND)
// ==========================================

val httpClient = HttpClient(CIO)

suspend fun enviarCorreoSalida(email: String, nombre: String, hora: String) {
    val apiKey = System.getenv("RESEND_API_KEY") ?: return
    try {
        httpClient.post("https://api.resend.com/emails") {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("from", "SIREA - CETIS 24 <onboarding@resend.dev>")
                put("to", JsonArray(listOf(JsonPrimitive(email))))
                put("subject", "Notificación de Salida - $nombre")
                put("html", """
                    <div style="font-family: sans-serif; border: 1px solid #eee; padding: 20px;">
                        <h2 style="color: #1A3A5C;">Aviso de Salida Escolar</h2>
                        <p>Estimado Tutor,</p>
                        <p>Le informamos que el alumno <b>$nombre</b> ha registrado su <b>SALIDA</b> del plantel a las <b>$hora</b>.</p>
                        <p style="font-size: 12px; color: #666;">Este es un mensaje automático del sistema SIREA.</p>
                    </div>
                """.trimIndent())
            })
        }
    } catch (e: Exception) {
        println("Error enviando correo: ${e.message}")
    }
}

// ==========================================
// 3. CONFIGURACIÓN DE RUTAS
// ==========================================
fun Application.configureRouting() {
    routing {

        get("/") { call.respondText("Servidor SCESA Operativo 🚀") }

        // --- GESTIÓN DE ALUMNOS (GET y POST) ---

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

        // --- ASISTENCIA Y ESCANEO ---

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

                // --- 4. ENVÍO DE CORREO SI ES SALIDA ---
                if (nuevoTipo == "SALIDA" && info["email"]!!.isNotBlank()) {
                    launch {
                        val horaFormateada = ahora.format(DateTimeFormatter.ofPattern("hh:mm a"))
                        enviarCorreoSalida(info["email"]!!, info["nombre"]!!, horaFormateada)
                    }
                }

                call.respond(HttpStatusCode.Created, "$nuevoTipo registrado para ${info["nombre"]}")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Error: ${e.message}")
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
    }
}