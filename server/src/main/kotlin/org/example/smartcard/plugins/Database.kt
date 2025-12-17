package org.example.smartcard.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database

fun Application.configureDatabases() {
    val config = HikariConfig().apply {
        driverClassName = "com.mysql.cj.jdbc.Driver"
        jdbcUrl = "jdbc:mysql://localhost:3306/smartcard_db?serverTimezone=UTC&useLegacyDatetimeCode=false&serverTimezone=UTC&zeroDateTimeBehavior=CONVERT_TO_NULL"
        username = "root"
        password = "123456" // <--- ĐIỀN PASSWORD MYSQL CỦA BẠN VÀO ĐÂY
        maximumPoolSize = 3
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    }
    val dataSource = HikariDataSource(config)
    Database.connect(dataSource)
}