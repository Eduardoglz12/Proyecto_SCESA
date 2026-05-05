package mx.cetis24.scesa.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init() {
        try {
            val config = HikariConfig().apply {
                // Driver de PostgreSQL
                driverClassName = "org.postgresql.Driver"

                // CONFIGURACIÓN DE CONEXIÓN
                // Cuando estemos en Railway, usaremos la variable de entorno.
                // Localmente, puedes usar una cadena de prueba.
                jdbcUrl = System.getenv("JDBC_URL") ?: "jdbc:postgresql://localhost:5432/scesa_db"
                username = System.getenv("DB_USER") ?: "postgres"
                password = System.getenv("DB_PASSWORD") ?: "admin123"

                // Optimización para el flujo del CETIS 24 (40-50 escaneos por minuto)
                maximumPoolSize = 10
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                validate()
            }

            val dataSource = HikariDataSource(config)
            Database.connect(dataSource)

            transaction {
                SchemaUtils.create(Alumnos, RegistrosAsistencia)
            }

            println("✅ Conexión a PostgreSQL inicializada correctamente.")
        } catch (e: Exception) {
            println("⚠️ ADVERTENCIA: No se pudo conectar a la base de datos: ${e.message}")
            println("El servidor seguirá corriendo, pero las funciones de asistencia fallarán.")
        }
    }

    /**
     * Esta es una función de utilidad para ejecutar consultas.
     * Ejecuta el código dentro de una transacción en un hilo separado (IO),
     * lo que evita que el servidor se "congele" mientras espera a la base de datos.
     */
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}