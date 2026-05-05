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
import java.time.ZonedDateTime
import java.time.ZoneId
// Importa tus tablas desde el paquete de base de datos
import mx.cetis24.scesa.database.Alumnos

// Importa las funciones de Exposed para que funcionen el .select, .eq, etc.
import org.jetbrains.exposed.sql.*

// ==========================================
// 1. MODELOS DE DATOS (Van afuera)
// ==========================================

@kotlinx.serialization.Serializable
data class AlumnoRequest(
    val numeroControl: String,
    val nombreCompleto: String,
    val grado: Int,
    val grupo: String,
    val turno: String,
    val nombreTutor: String, // Nuevo campo
    val emailTutor: String    // Nuevo campo
)

@kotlinx.serialization.Serializable
data class EscaneoRequest(val numeroControl: String, val operadorId: Int)

@kotlinx.serialization.Serializable
data class AsistenciaRespuesta(
    val id: Int,
    val numeroControl: String,
    val nombre: String,   // Agregado
    val grupo: String,    // Agregado
    val turno: String,    // Agregado
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
                        it[nombreTutor] = request.nombreTutor // Asignar nuevo campo
                        it[emailTutor] = request.emailTutor   // Asignar nuevo campo
                    }
                }
                call.respond(HttpStatusCode.Created, "Alumno ${request.nombreCompleto} registrado correctamente.")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Error al registrar alumno: ${e.message}")
            }
        }

        post("/api/asistencia/escanear") {
            try {
                val request = call.receive<EscaneoRequest>()

                // --- CORRECCIÓN 1: Configurar Zona Horaria de Coahuila ---
                val zonaCoahuila = java.time.ZoneId.of("America/Monterrey")
                val ahora = java.time.ZonedDateTime.now(zonaCoahuila).toLocalDateTime()
                val hoyInicio = ahora.toLocalDate().atStartOfDay()

                // 1. Verificamos que el alumno exista y obtenemos su info (para el mensaje de éxito)
                val alumnoInfo = dbQuery<Triple<String, String, String>?> {
                    Alumnos.select { Alumnos.numeroControl eq request.numeroControl }
                        .map { row ->
                            // Usar 'row' en lugar de 'it' a veces ayuda al autocompletado y a la inferencia
                            Triple(row[Alumnos.nombreCompleto], row[Alumnos.grupo], row[Alumnos.turno])
                        }
                        .singleOrNull()
                }

                if (alumnoInfo == null) {
                    return@post call.respond(HttpStatusCode.NotFound, "Error: El número de control no existe.")
                }

                val (nombre, grupo, turno) = alumnoInfo

                // 2. Buscamos el ÚLTIMO movimiento de hoy
                val ultimoRegistro = dbQuery {
                    RegistrosAsistencia
                        .select {
                            (RegistrosAsistencia.numeroControl eq request.numeroControl) and
                                    (RegistrosAsistencia.timestampRegistro greaterEq hoyInicio)
                        }
                        .orderBy(RegistrosAsistencia.timestampRegistro to org.jetbrains.exposed.sql.SortOrder.DESC)
                        .limit(1)
                        .map {
                            Pair(it[RegistrosAsistencia.tipoEvento], it[RegistrosAsistencia.timestampRegistro])
                        }
                        .singleOrNull()
                }

                val ultimoTipo = ultimoRegistro?.first
                val ultimoTimestamp = ultimoRegistro?.second

                // --- VALIDACIÓN: Cooldown de 1 minuto ---
                if (ultimoTimestamp != null) {
                    val diferencia = java.time.Duration.between(ultimoTimestamp, ahora).toMinutes()
                    if (diferencia < 1) {
                        return@post call.respond(
                            HttpStatusCode.Conflict,
                            "Escaneo ignorado: Espera 1 minuto entre registros."
                        )
                    }
                }

                val nuevoTipo = when (ultimoTipo) {
                    null -> "ENTRADA"
                    "ENTRADA" -> "SALIDA"
                    else -> return@post call.respond(
                        HttpStatusCode.Forbidden,
                        "El alumno ya cumplió su ciclo de Entrada/Salida por hoy."
                    )
                }

                // 3. Insertar registro
                dbQuery {
                    RegistrosAsistencia.insert {
                        it[numeroControl] = request.numeroControl
                        it[tipoEvento] = nuevoTipo
                        it[metodoRegistro] = "QR"
                        it[timestampRegistro] = ahora // Guardamos la hora de Coahuila
                        it[operadorId] = request.operadorId
                    }
                }

                // Respuesta enriquecida con los datos del alumno
                call.respond(HttpStatusCode.Created, "¡$nuevoTipo exitosa! Alumno: $nombre ($grupo $turno)")

            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Error en el servidor: ${e.message}")
            }
        }

        get("/api/asistencia") {
            try {
                val historial = dbQuery {
                    // Realizamos un INNER JOIN para conectar el escaneo con el perfil del alumno
                    (RegistrosAsistencia innerJoin Alumnos)
                        .slice(
                            RegistrosAsistencia.id,
                            RegistrosAsistencia.numeroControl,
                            Alumnos.nombreCompleto,
                            Alumnos.grupo,
                            Alumnos.turno,
                            RegistrosAsistencia.tipoEvento,
                            RegistrosAsistencia.timestampRegistro,
                            RegistrosAsistencia.operadorId
                        )
                        .selectAll()
                        .orderBy(RegistrosAsistencia.timestampRegistro to SortOrder.DESC)
                        .map {
                            AsistenciaRespuesta(
                                id = it[RegistrosAsistencia.id],
                                numeroControl = it[RegistrosAsistencia.numeroControl],
                                nombre = it[Alumnos.nombreCompleto], // Ahora disponible por el Join
                                grupo = it[Alumnos.grupo],           // Ahora disponible por el Join
                                turno = it[Alumnos.turno],           // Ahora disponible por el Join
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

        get("/api/alumnos") {
            try {
                val listaAlumnos = dbQuery {
                    mx.cetis24.scesa.database.Alumnos.selectAll().map {
                        AlumnoRequest(
                            numeroControl = it[mx.cetis24.scesa.database.Alumnos.numeroControl],
                            nombreCompleto = it[mx.cetis24.scesa.database.Alumnos.nombreCompleto],
                            grado = it[mx.cetis24.scesa.database.Alumnos.grado],
                            grupo = it[mx.cetis24.scesa.database.Alumnos.grupo],
                            turno = it[mx.cetis24.scesa.database.Alumnos.turno],
                            nombreTutor = it[mx.cetis24.scesa.database.Alumnos.nombreTutor] ?: "Sin asignar",
                            emailTutor = it[mx.cetis24.scesa.database.Alumnos.emailTutor] ?: "Sin asignar"
                        )
                    }
                }
                call.respond(listaAlumnos)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error al obtener alumnos: ${e.message}")
            }
        }

    }
}