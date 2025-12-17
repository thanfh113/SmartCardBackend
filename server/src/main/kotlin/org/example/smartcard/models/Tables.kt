package org.example.smartcard.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import java.math.BigDecimal

object Employees : Table("employees") {
    val id = integer("id").autoIncrement()
    val cardUuid = varchar("card_uuid", 36).uniqueIndex()
    val employeeId = varchar("employee_id", 20).uniqueIndex()
    val name = varchar("name", 100)

    // Khớp với database thực tế của bạn
    val dob = date("date_of_birth").nullable()
    val departmentId = integer("department_id").default(1)
    val positionId = integer("position_id").default(1)
    val role = varchar("role", 20).default("USER")

    val balance = decimal("balance", 15, 2).default(BigDecimal.ZERO)
    val publicKey = blob("rsa_public_key").nullable()
    val isActive = bool("is_active").default(true)
    val photoPath = varchar("photo_path", 255).nullable()
    val pinHash = varchar("pin_hash", 255).nullable()
    val isDefaultPin = bool("is_default_pin").default(true)

    override val primaryKey = PrimaryKey(id)
}

object Transactions : Table("transactions") {
    val id = integer("id").autoIncrement()
    val employeeId = integer("employee_id").references(Employees.id)
    val type = varchar("trans_type", 20)
    val amount = decimal("amount", 15, 2)
    val balanceBefore = decimal("balance_before", 15, 2)
    val balanceAfter = decimal("balance_after", 15, 2)
    val description = text("description").nullable()
    val signature = blob("signature").nullable()
    val transactionTime = datetime("transaction_time")

    override val primaryKey = PrimaryKey(id)
}

// ✅ THÊM BẢNG NÀY ĐỂ GHI LOG RA VÀO
object AttendanceLogs : Table("attendance_logs") {
    val id = integer("id").autoIncrement()
    val employeeId = integer("employee_id").references(Employees.id)
    val workDate = date("work_date")
    val checkInTime = datetime("check_in_time").nullable()
    val checkOutTime = datetime("check_out_time").nullable()
    val status = varchar("status", 50).nullable()
    val notes = text("notes").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    override val primaryKey = PrimaryKey(id)
}

object Departments : Table("departments") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 100)

    override val primaryKey = PrimaryKey(id)
}
object Positions : Table("positions") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 100)

    override val primaryKey = PrimaryKey(id)
}
object Products : Table("products") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 100)
    val price = decimal("price", 15, 2)
    val category = varchar("category", 50)
    val isAvailable = bool("is_available").default(true)

    override val primaryKey = PrimaryKey(id)
}