package mx.cetis24.scesa

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*
import mx.cetis24.scesa.database.DatabaseFactory.dbQuery
import mx.cetis24.scesa.database.RegistrosAsistencia
import org.jetbrains.exposed.sql.insert
import java.time.LocalDateTime
import org.jetbrains.exposed.sql.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.Duration

// ==========================================
// 1. MODELOS DE DATOS (Van afuera)
// ==========================================

@kotlinx.serialization.Serializable
data class AlumnoRequest(
    val numeroControl: String,
    val nombreCompleto: String,
    val grado: Int,
    val grupo: String,
    val turno: String
)

@kotlinx.serialization.Serializable
data class EscaneoRequest(val numeroControl: String, val operadorId: Int)

@kotlinx.serialization.Serializable
data class AsistenciaRespuesta(
    val id: Int,
    val numeroControl: String,
    val evento: String,
    val fecha: String,
    val operador: Int
)
// ==========================================
// 2. CONFIGURACIÓN DE RUTAS
// ==========================================
fun Application.configureRouting() {
    routing {

        // --- RUTA 1: Prueba de servidor ---
        get("/") {
            call.respondText("Servidor SCESA Operativo 🚀")
        }

        // --- RUTA 2: Registrar Alumnos en el padrón ---
        post("/api/alumnos") {
            try {
                val request = call.receive<AlumnoRequest>()

                dbQuery {
                    mx.cetis24.scesa.database.Alumnos.insert {
                        it[numeroControl] = request.numeroControl
                        it[nombreCompleto] = request.nombreCompleto
                        it[grado] = request.grado
                        it[grupo] = request.grupo
                        it[turno] = request.turno
                    }
                }
                call.respond(HttpStatusCode.Created, "Alumno ${request.nombreCompleto} registrado correctamente.")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Error al registrar alumno: ${e.message}")
            }
        }

        // --- RUTA 3: Escanear y registrar asistencia ---
        post("/api/asistencia/escanear") {
            try {
                val request = call.receive<EscaneoRequest>()
                val ahora = LocalDateTime.now()
                val hoyInicio = LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT)

                // 1. Buscamos el ÚLTIMO movimiento de hoy
                val ultimoRegistro = dbQuery {
                    RegistrosAsistencia
                        .select {
                            (RegistrosAsistencia.numeroControl eq request.numeroControl) and
                                    (RegistrosAsistencia.timestampRegistro greaterEq hoyInicio)
                        }
                        .orderBy(RegistrosAsistencia.timestampRegistro to SortOrder.DESC)
                        .limit(1)
                        .map {
                            Pair(it[RegistrosAsistencia.tipoEvento], it[RegistrosAsistencia.timestampRegistro])
                        }
                        .singleOrNull()
                }

                val ultimoTipo = ultimoRegistro?.first
                val ultimoTimestamp = ultimoRegistro?.second

                // --- VALIDACIÓN 1: Evitar escaneo accidental (Cooldown de 2 minutos) ---
                if (ultimoTimestamp != null) {
                    val diferencia = Duration.between(ultimoTimestamp, ahora).toMinutes()
                    if (diferencia < 1) {
                        return@post call.respond(
                            HttpStatusCode.Conflict,
                            "Escaneo ignorado: Por seguridad, espera 1 minuto entre registros."
                        )
                    }
                }

                // --- VALIDACIÓN 2 y 3: Lógica de flujo y límite diario ---
                val nuevoTipo = when (ultimoTipo) {
                    null -> "ENTRADA" // Caso A: Primer registro del día -> Entra
                    "ENTRADA" -> "SALIDA" // Caso B: Ya entró -> Toca salir
                    "SALIDA" -> {
                        // Caso C: Ya entró Y ya salió.
                        // Bloqueamos una segunda entrada según tu requerimiento.
                        return@post call.respond(
                            HttpStatusCode.Forbidden,
                            "El alumno ya cumplió su ciclo de Entrada/Salida por hoy."
                        )
                    }
                    else -> "ENTRADA"
                }

                // 2. Insertar si pasó todas las validaciones
                dbQuery {
                    RegistrosAsistencia.insert {
                        it[numeroControl] = request.numeroControl
                        it[tipoEvento] = nuevoTipo
                        it[metodoRegistro] = "QR"
                        it[timestampRegistro] = ahora
                        it[operadorId] = request.operadorId
                    }
                }

                call.respond(HttpStatusCode.Created, "Registro de $nuevoTipo exitoso para ${request.numeroControl}.")

            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Error en validación: ${e.message}")
            }
        }

        get("/api/asistencia") {
            try {
                val historial = dbQuery {
                    RegistrosAsistencia.selectAll()
                        .orderBy(RegistrosAsistencia.timestampRegistro to SortOrder.DESC)
                        .map {
                            // Aquí usamos el nuevo "molde" AsistenciaRespuesta
                            AsistenciaRespuesta(
                                id = it[RegistrosAsistencia.id],
                                numeroControl = it[RegistrosAsistencia.numeroControl],
                                evento = it[RegistrosAsistencia.tipoEvento],
                                fecha = it[RegistrosAsistencia.timestampRegistro].toString(),
                                operador = it[RegistrosAsistencia.operadorId]
                            )
                        }
                }
                call.respond(historial)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error al obtener historial: ${e.message}")
            }
        }

    }
}