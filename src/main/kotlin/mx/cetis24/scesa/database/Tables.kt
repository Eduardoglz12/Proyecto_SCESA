package mx.cetis24.scesa.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

// Padrón del CETIS 24
object Alumnos : Table("alumnos") {
    val numeroControl = varchar("numero_control", 20)
    val nombreCompleto = varchar("nombre_completo", 150)
    val grado = integer("grado")
    val grupo = varchar("grupo", 10)
    val turno = varchar("turno", 20)
    val nombreTutor = varchar("nombre_tutor", 150).nullable()
    val emailTutor = varchar("email_tutor", 150).nullable()
    val activo = bool("activo").default(true)

    override val primaryKey = PrimaryKey(numeroControl)
}

// Historial de escaneos
object RegistrosAsistencia : Table("registros_asistencia") {
    val id = integer("id").autoIncrement()
    val numeroControl = varchar("numero_control", 20) references Alumnos.numeroControl
    val tipoEvento = varchar("tipo_evento", 10) // ENTRADA o SALIDA
    val metodoRegistro = varchar("metodo_registro", 15) // QR o MANUAL
    val timestampRegistro = datetime("timestamp_registro")
    val operadorId = integer("operador_id")

    override val primaryKey = PrimaryKey(id)
}