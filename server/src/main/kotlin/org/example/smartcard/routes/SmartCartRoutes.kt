package org.example.smartcard.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.model.AvatarUploadRequest
import org.example.project.model.ChangeStatusRequest
import org.example.project.model.HistoryLogEntry
import org.example.project.model.Product
import org.example.project.model.RegisterRequest
import org.example.project.model.SetDefaultPinRequest
import org.example.project.model.TransactionRequest
import org.example.project.model.UpdateInfoRequest
import org.example.project.model.UserResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.example.smartcard.models.*
import org.example.smartcard.utils.CryptoUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.javatime.date
import java.math.BigDecimal
import java.time.LocalDateTime
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64

fun Route.smartCardRoutes() {
    route("/api/card") {

        // 1. Register (Giữ nguyên)
        post("/register") {
            val req = call.receive<RegisterRequest>()
            try {
                val isSuccess = transaction {
                    if (Employees.select { Employees.cardUuid eq req.cardUuid }.count() > 0) return@transaction false
                    val pubKeyBytes = CryptoUtils.hexToBytes(req.publicKeyHex)
                    Employees.insert {
                        it[cardUuid] = req.cardUuid
                        it[employeeId] = req.employeeId
                        it[name] = req.name
                        it[publicKey] = ExposedBlob(pubKeyBytes)
                        it[balance] = BigDecimal.ZERO
                        it[isActive] = true
                        it[role] = "USER"
                        it[isDefaultPin] = true
                    }
                    true
                }
                if (isSuccess) call.respond(HttpStatusCode.Created, "Registered")
                else call.respond(HttpStatusCode.Conflict, "Card exists")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error")
            }
        }

        // 2. Transaction (Tiền nong -> Bảng Transactions)
        post("/transaction") {
            val req = call.receive<TransactionRequest>()
            val txResult = transaction {
                val empRow = if (req.cardUuid.isNotEmpty()) {
                    Employees.select { Employees.cardUuid eq req.cardUuid }.singleOrNull()
                        ?: Employees.select { Employees.employeeId eq req.cardUuid }.singleOrNull()
                } else {
                    Employees.select { Employees.employeeId eq req.cardUuid }.singleOrNull()
                }

                if (empRow != null) {
                    val empId = empRow[Employees.id]
                    val currentDbBal = empRow[Employees.balance]
                    val amountBD = req.amount.toBigDecimal()
                    val newBal = currentDbBal + amountBD

                    Transactions.insert {
                        it[employeeId] = empId
                        it[type] = if (req.amount > 0) "TOPUP" else "PAYMENT"
                        it[amount] = amountBD.abs()
                        it[balanceBefore] = currentDbBal
                        it[balanceAfter] = newBal
                        it[description] = req.description
                        it[signature] = ExposedBlob(CryptoUtils.hexToBytes(req.signatureHex.ifBlank { "00" }))
                        it[transactionTime] = LocalDateTime.now()
                    }

                    Employees.update({ Employees.id eq empId }) { it[balance] = newBal }
                    Pair(true, newBal)
                } else {
                    Pair(false, null)
                }
            }

            if (txResult.first) {
                call.respond(HttpStatusCode.OK, mapOf("newBalance" to txResult.second!!.toDouble()))
            } else {
                call.respond(HttpStatusCode.NotFound, "User not found")
            }
        }

        // ✅ 3. LOG RA VÀO -> Bảng AttendanceLogs
        post("/access-log") {
            try {
                val params = call.receive<Map<String, String>>()
                val empIdStr = params["employeeId"] ?: ""
                val logType = params["type"] ?: "CHECK_IN"
                val desc = params["description"] ?: ""

                // Biến lưu thông báo lỗi cụ thể để gửi lại Client
                var rejectionMessage: String? = null

                val isSaved = transaction {
                    val emp = Employees.select { Employees.employeeId eq empIdStr }.singleOrNull()
                    if (emp == null) {
                        rejectionMessage = "User not found."
                        return@transaction false
                    }

                    val internalId = emp[Employees.id]
                    val now = LocalDateTime.now()
                    val today = LocalDate.now()

                    // --- LOGIC GHI LOG ---
                    if (logType == "CHECK_IN") {
                        val existingOpenLog = AttendanceLogs
                            .select {
                                (AttendanceLogs.employeeId eq internalId) and
                                        (AttendanceLogs.checkOutTime.isNull())
                            }
                            .limit(1)
                            .singleOrNull()

                        if (existingOpenLog == null) {
                            // Cho phép INSERT Check-In mới
                            AttendanceLogs.insert {
                                it[employeeId] = internalId
                                it[workDate] = today
                                it[checkInTime] = now
                                it[status] = "Working"
                                it[notes] = desc
                            }
                            println("✅ SERVER: Check-In mới được tạo.")
                        } else {
                            // CÓ phiên mở -> Từ chối và đặt thông báo lỗi
                            rejectionMessage = "Open session exists. Must check out first."
                            return@transaction false // Ghi THẤT BẠI
                        }

                    } else if (logType == "CHECK_OUT") {
                        // Logic Check-out: CẬP NHẬT
                        val lastLogId = AttendanceLogs.slice(AttendanceLogs.id)
                            .select {
                                (AttendanceLogs.employeeId eq internalId) and
                                        (AttendanceLogs.checkOutTime.isNull())
                            }
                            .orderBy(AttendanceLogs.checkInTime to SortOrder.DESC)
                            .limit(1)
                            .singleOrNull()
                            ?.get(AttendanceLogs.id)

                        if (lastLogId != null) {
                            // Cập nhật dòng Check-in tìm được
                            AttendanceLogs.update({ AttendanceLogs.id eq lastLogId }) {
                                it[checkOutTime] = now
                                it[status] = "Finished"
                                it[notes] = "$desc (Out)"
                            }
                            println("✅ SERVER: Đã Check-Out thành công.")
                        } else {
                            // Không tìm thấy Check-in để update -> Từ chối
                            rejectionMessage = "No open session found to check out."
                            return@transaction false // Ghi THẤT BẠI
                        }
                    } else { // logType == "RESTRICTED"
                        // Log đặc biệt: LUÔN INSERT
                        AttendanceLogs.insert {
                            it[employeeId] = internalId
                            it[workDate] = today
                            it[checkInTime] = now
                            it[status] = "Restricted Access"
                            it[notes] = desc
                        }
                    }
                    true // Ghi thành công
                }

                if (isSaved) {
                    call.respond(HttpStatusCode.OK, "Saved")
                } else {
                    // 🔥 TRẢ VỀ MÃ LỖI 409 nếu Server từ chối vì quy tắc nghiệp vụ
                    call.respond(HttpStatusCode.Conflict, rejectionMessage ?: "Conflict or Unknown failure.")
                }

            } catch (e: Exception) {
                println("🔥 SERVER ERROR: ${e.message}")
                e.printStackTrace()
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Unknown Error")
            }
        }

        get("/balance/{id}") {
            val id = call.parameters["id"] ?: ""
            val bal = transaction {
                Employees.slice(Employees.balance)
                    .select { Employees.employeeId eq id }
                    .singleOrNull()?.get(Employees.balance)
            }
            if (bal != null) call.respond(mapOf("balance" to bal.toDouble()))
            else call.respond(HttpStatusCode.NotFound)
        }

        get("/history") {
            val employeeId = call.request.queryParameters["employeeId"]

            // Khối transaction trả về danh sách logs đã sắp xếp (List<HistoryLogEntry>)
            val allLogsSorted = transaction {

                // 1. Attendance Logs
                val accessBaseQuery = AttendanceLogs
                    .join(Employees, JoinType.INNER, onColumn = AttendanceLogs.employeeId, otherColumn = Employees.id)
                    .slice(
                        Employees.employeeId, Employees.name, AttendanceLogs.checkInTime,
                        AttendanceLogs.checkOutTime, AttendanceLogs.notes, AttendanceLogs.status
                    )
                    .selectAll()

                // 2. Transaction Logs
                val txBaseQuery = Transactions
                    .join(Employees, JoinType.INNER, onColumn = Transactions.employeeId, otherColumn = Employees.id)
                    .slice(
                        Employees.employeeId, Employees.name, Transactions.transactionTime,
                        Transactions.type, Transactions.amount, Transactions.balanceAfter,
                        Transactions.description
                    )
                    .selectAll()

                // THỰC HIỆN LỌC TRÊN DB NẾU employeeId ĐƯỢC CUNG CẤP
                var filteredAccessQuery = accessBaseQuery
                var filteredTxQuery = txBaseQuery

                if (employeeId != null && employeeId.isNotBlank()) {
                    filteredAccessQuery = accessBaseQuery.adjustWhere { Employees.employeeId eq employeeId }
                    filteredTxQuery = txBaseQuery.adjustWhere { Employees.employeeId eq employeeId }
                }

                // 🔥 THAY ĐỔI: Sử dụng List<HistoryLogEntry> để đảm bảo kiểu dữ liệu thống nhất
                val allLogs = mutableListOf<HistoryLogEntry>()
                val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

                // Xử lý Access Logs
                filteredAccessQuery.orderBy(AttendanceLogs.checkInTime to SortOrder.DESC).forEach { row ->
                    val checkInTime = row[AttendanceLogs.checkInTime]
                    val checkOutTime = row[AttendanceLogs.checkOutTime]
                    val employeeName = row[Employees.name]

                    val checkInTimeString = checkInTime?.format(formatter) ?: ""
                    val notes = row[AttendanceLogs.notes].toString()

                    // Ghi Log Ra/Vào bằng DTO
                    if (row[AttendanceLogs.status] == "Restricted Access") {
                        allLogs.add(
                            HistoryLogEntry(
                                type = "RESTRICTED",
                                time = checkInTimeString,
                                name = employeeName,
                                desc = notes,
                                amount = "0.0", // Dùng String cho tính đồng nhất
                                balanceAfter = "0.0" // Dùng String cho tính đồng nhất
                            )
                        )
                    } else {
                        // Check-in
                        allLogs.add(
                            HistoryLogEntry(
                                type = "CHECK_IN",
                                time = checkInTimeString,
                                name = employeeName,
                                desc = notes,
                                amount = "0.0",
                                balanceAfter = "0.0"
                            )
                        )

                        // Check-out (nếu có)
                        if (checkOutTime != null) {
                            allLogs.add(
                                HistoryLogEntry(
                                    type = "CHECK_OUT",
                                    time = checkOutTime.format(formatter),
                                    name = employeeName,
                                    desc = notes,
                                    amount = "0.0",
                                    balanceAfter = "0.0"
                                )
                            )
                        }
                    }
                }

                // Xử lý Transaction Logs
                filteredTxQuery.orderBy(Transactions.transactionTime to SortOrder.DESC).forEach { row ->
                    val txTimeString = row[Transactions.transactionTime]?.format(formatter) ?: ""

                    // Ghi Log Giao dịch bằng DTO
                    allLogs.add(
                        HistoryLogEntry(
                            type = row[Transactions.type].toString(), // Ép Enum về String
                            time = txTimeString,
                            name = row[Employees.name],
                            // Ép Double về String
                            amount = row[Transactions.amount].toDouble().toString(),
                            desc = row[Transactions.description],
                            balanceAfter = row[Transactions.balanceAfter].toDouble().toString() // Ép Double về String
                        )
                    )
                }

                // Sắp xếp LẠI logs
                allLogs.sortedByDescending {
                    val timeStr = it.time
                    // Sử dụng LocalDateTime.MIN nếu chuỗi thời gian rỗng để đảm bảo phân tích cú pháp không bị lỗi
                    if (timeStr.isEmpty()) LocalDateTime.MIN else LocalDateTime.parse(timeStr, formatter)
                }
            }

            // TRẢ VỀ LIST DTO ĐÃ SẮP XẾP. Ktor có thể serialize List<DTO> này một cách an toàn.
            call.respond(allLogsSorted)
        }

        // ✅ API Update (Có xử lý ngày sinh)
        post("/update") {
            val req = call.receive<UpdateInfoRequest>()
            val updated = transaction {
                Employees.update({ Employees.cardUuid eq req.cardUuid }) {
                    it[name] = req.name
                    // Parse ngày sinh dd/MM/yyyy
                    try {
                        it[dob] = LocalDate.parse(req.dob, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    } catch (e: Exception) { /* Ignore format error */ }
                }
            }
            if (updated > 0) call.respond(HttpStatusCode.OK, "Updated")
            else call.respond(HttpStatusCode.NotFound, "Card not found")
        }

        // ... (GIỮ NGUYÊN CÁC API KHÁC: GET INFO, UPDATE, LOGIN...)

        get("/{uuid}") {
            val uuid = call.parameters["uuid"] ?: ""
            val info = transaction {
                (
                    Employees
                        .join(Departments, JoinType.LEFT) { Employees.departmentId eq Departments.id }
                        .join(Positions, JoinType.LEFT) { Employees.positionId eq Positions.id }
                )
                .select { (Employees.cardUuid eq uuid) or (Employees.employeeId eq uuid) }
                .map {
                    UserResponse(
                        cardUuid = it[Employees.cardUuid],
                        employeeId = it[Employees.employeeId],
                        name = it[Employees.name],
                        // ✅ Dùng getOrNull() an toàn với LEFT JOIN
                        department = it.getOrNull(Departments.name),
                        role = it[Employees.role],
                        isActive = it[Employees.isActive],
                        dob = it[Employees.dob]?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                        position = it.getOrNull(Positions.name),
                        isDefaultPin = it[Employees.isDefaultPin],
                        balance = it[Employees.balance].toDouble()
                    )
                }
                .singleOrNull()
            }
            if (info != null) call.respond(info)
            else call.respond(HttpStatusCode.NotFound)
        }
        get("/products") {
            val products = transaction {
                Products.select { Products.isAvailable eq true }.map {
                    Product(
                        id = it[Products.id], // ✅ Lấy giá trị ID
                        name = it[Products.name],
                        price = it[Products.price].toInt(),
                        category = it[Products.category],
                        isAvailable = it[Products.isAvailable]
                    )
                }
            }
            call.respond(products)
        }

        post("/pin-changed") {
            val req = call.receive<Map<String, String>>()
            val targetUuid = req["cardUuid"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing cardUuid")

            val updated = transaction {
                // Đánh dấu isDefaultPin = false
                Employees.update({ Employees.cardUuid eq targetUuid }) {
                    it[isDefaultPin] = false
                }
            }
            if (updated > 0) call.respond(HttpStatusCode.OK, "PIN status updated")
            else call.respond(HttpStatusCode.NotFound, "User not found")
        }

        get("/next-id") {
            val prefix = call.request.queryParameters["prefix"]?.uppercase() ?: "NV"
            val nextId = transaction {
                val count = Employees.select { Employees.employeeId like "$prefix%" }.count()
                "$prefix${String.format("%03d", count + 1)}"
            }
            call.respond(mapOf("id" to nextId))
        }

        get("/all-users") {
            val users = transaction {
                // 🔥 SỬ DỤNG LEFT JOIN: Đảm bảo lấy được User ngay cả khi thiếu Dept/Pos ID
                Employees
                        // LEFT JOIN Departments
                    .join(Departments, JoinType.LEFT) { Employees.departmentId eq Departments.id }
                // LEFT JOIN Positions
                    .join(Positions, JoinType.LEFT) { Employees.positionId eq Positions.id }
                .selectAll().map {
                UserResponse(
                    cardUuid = it[Employees.cardUuid],
                    employeeId = it[Employees.employeeId],
                    name = it[Employees.name],
                    department = it.getOrNull(Departments.name),
                    role = it[Employees.role],
                    isActive = it[Employees.isActive],
                    dob = it[Employees.dob]?.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                    position = it.getOrNull(Positions.name),
                    isDefaultPin = it[Employees.isDefaultPin],
                    balance = it[Employees.balance].toDouble()
                )
            }
            }
            call.respond(users)
        }

        post("/change-status") {
            val req = call.receive<ChangeStatusRequest>()
            transaction {
                Employees.update({ Employees.cardUuid eq req.targetUuid }) { it[isActive] = req.isActive }
            }
            call.respond(HttpStatusCode.OK, "Status updated")
        }

        post("/admin/login") {
            val params = call.receive<Map<String, String>>()
            val adminId = params["id"]?.takeIf { it.isNotBlank() } ?: "ADMIN01"
            val inputPin = params["pin"] ?: ""
            if (inputPin.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "Missing PIN")
                return@post
            }
            val inputHash = CryptoUtils.sha256(inputPin)
            val adminRow = transaction {
                Employees.slice(Employees.pinHash)
                    .select { (Employees.employeeId eq adminId) and (Employees.role eq "ADMIN") }
                    .singleOrNull()
            }
            if (adminRow != null) {
                val dbPin = adminRow[Employees.pinHash] ?: ""
                if (dbPin.equals(inputHash, ignoreCase = true) || dbPin == inputPin) {
                    if (dbPin == inputPin) {
                        transaction { Employees.update({ Employees.employeeId eq adminId }) { it[pinHash] = inputHash } }
                    }
                    call.respond(HttpStatusCode.OK, "Login Success")
                } else {
                    call.respond(HttpStatusCode.Unauthorized, "Wrong PIN")
                }
            } else {
                call.respond(HttpStatusCode.NotFound, "Admin not found")
            }
        }

        post("/update") {
            val req = call.receive<UpdateInfoRequest>()
            val updated = transaction {
                Employees.update({ Employees.cardUuid eq req.cardUuid }) {
                    it[name] = req.name
                    // Parse ngày sinh dd/MM/yyyy
                    try {
                        it[dob] = LocalDate.parse(req.dob, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    } catch (e: Exception) { /* Ignore format error */ }
                }
            }
            if (updated > 0) call.respond(HttpStatusCode.OK, "Updated")
            else call.respond(HttpStatusCode.NotFound, "Card not found")
        }

        post("/admin/updateProfile"){
            val req = call.receive<UpdateInfoRequest>()

            // Lấy ID để tìm kiếm. Ưu tiên employeeId, nếu không có thì dùng cardUuid.
            val adminIdToSearch = req.employeeId.takeIf { it.isNotBlank() } ?: req.cardUuid.takeIf { it.isNotBlank() }

            if (adminIdToSearch.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, "Missing employeeId or cardUuid")
                return@post
            }

            val updated = transaction {
                // --- 1. Lấy departmentId và positionId ---

                // Lấy departmentId từ tên phòng ban
                val deptId = Departments.slice(Departments.id)
                    .select { Departments.name eq req.department }
                    .singleOrNull()?.get(Departments.id)

                // Lấy positionId từ tên chức vụ
                val posId = Positions.slice(Positions.id)
                    .select { Positions.name eq req.position }
                    .singleOrNull()?.get(Positions.id)

                // --- 2. Cập nhật thông tin nhân viên ---

                // Tìm kiếm bằng employeeId HOẶC cardUuid
                Employees.update({ (Employees.employeeId eq adminIdToSearch) or (Employees.cardUuid eq adminIdToSearch) }) {
                    it[name] = req.name

                    // Cập nhật departmentId nếu tìm thấy
                    if (deptId != null) {
                        it[departmentId] = deptId
                    }

                    // Cập nhật positionId nếu tìm thấy
                    if (posId != null) {
                        it[positionId] = posId
                    }

                    // Cập nhật is_default_pin
                    it[isDefaultPin] = req.isDefaultPin

                    // Parse ngày sinh dd/MM/yyyy và cập nhật nếu thành công
                    try {
                        // Tên cột trong Exposed là `dob`
                        val parsedDate = LocalDate.parse(req.dob, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                        it[dob] = parsedDate
                    } catch (e: Exception) {
                        // Nếu không thể parse, giữ nguyên giá trị cũ hoặc bỏ qua
                        // Log lỗi nếu cần thiết
                    }

                    // Cập nhật trường `updated_at` (nếu có trong Exposed Table của bạn)
                    // LƯU Ý: Employees Table bạn cung cấp KHÔNG CÓ updated_at, nếu DB của bạn có, hãy thêm nó vào Employees Table Exposed Model.
                    // Ví dụ: it[updatedAt] = CurrentDateTime()
                }
            }

            if (updated > 0) call.respond(HttpStatusCode.OK, "Profile Updated")
            else call.respond(HttpStatusCode.NotFound, "Admin/Employee not found with ID: $adminIdToSearch")
        }

        post("/admin/delete-user") {
            val params = call.receive<Map<String, String>>()
            val adminId = "ADMIN01"
            val pin = params["pin"] ?: ""
            val targetUuid = params["targetUuid"] ?: ""

            if (pin.isEmpty() || targetUuid.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, "Missing Info")
                return@post
            }

            val adminHash = CryptoUtils.sha256(pin)
            val adminRow = transaction {
                Employees.slice(Employees.pinHash)
                    .select { (Employees.employeeId eq adminId) and (Employees.role eq "ADMIN") }
                    .singleOrNull()
            }
            val dbPin = adminRow?.get(Employees.pinHash) ?: ""

            if (dbPin.equals(adminHash, ignoreCase = true) || dbPin == pin) {
                transaction {
                    // Xóa các bảng liên quan trước để tránh lỗi khóa ngoại
                    val targetId = Employees.slice(Employees.id)
                        .select { Employees.cardUuid eq targetUuid }
                        .singleOrNull()?.get(Employees.id)

                    if (targetId != null) {
                        AttendanceLogs.deleteWhere { AttendanceLogs.employeeId eq targetId }
                        Transactions.deleteWhere { Transactions.employeeId eq targetId }
                        Employees.deleteWhere { Employees.id eq targetId }
                    }
                }
                call.respond(HttpStatusCode.OK, "Deleted")
            } else {
                call.respond(HttpStatusCode.Unauthorized, "Wrong PIN")
            }
        }

        post("/upload-avatar") {
            val req = call.receive<AvatarUploadRequest>()
            try {
                val file = File("uploads", "${req.cardUuid}.jpg")
                file.parentFile.mkdirs()
                file.writeBytes(Base64.getDecoder().decode(req.avatarBase64))
                transaction {
                    Employees.update({ Employees.cardUuid eq req.cardUuid }) { it[photoPath] = "uploads/${req.cardUuid}.jpg" }
                }
                call.respond(HttpStatusCode.OK, "Saved")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, "Error")
            }
        }
        get("/departments") {
            val departments = transaction {
                // Lấy ID và Name từ bảng Departments
                Departments.selectAll().associate {
                    it[Departments.id].toString() to it[Departments.name]
                }
            }
            call.respond(departments)
        }

        // 🔥 API MỚI: Lấy danh sách Positions (ID -> Name)
        get("/positions") {
            val positions = transaction {
                // Lấy ID và Name từ bảng Positions
                Positions.selectAll().associate {
                    it[Positions.id].toString() to it[Positions.name]
                }
            }
            call.respond(positions)
        }
        // Trong smartCardRoutes.kt
        post("/admin/change-pin") { // Đã đổi thành dấu gạch nối cho khớp Client
            try {
                val params = call.receive<Map<String, String>>()
                val adminId = params["id"] ?: "ADMIN01"
                val newPin = params["newPin"] ?: ""

                if (newPin.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "Mã PIN không được để trống")
                    return@post
                }

                // Tạo Hash từ PIN mới
                val newHash = CryptoUtils.sha256(newPin)

                val result = transaction {
                    // Lấy thông tin Admin hiện tại trong DB
                    val admin = Employees.select { (Employees.employeeId eq adminId) and (Employees.role eq "ADMIN") }
                        .singleOrNull()

                    if (admin == null) return@transaction "NOT_FOUND"

                    // 🛡️ KIỂM TRA TRÙNG: So sánh Hash mới với Hash cũ trong DB
                    if (admin[Employees.pinHash] == newHash) {
                        return@transaction "IDENTICAL"
                    }

                    // Nếu không trùng -> Cập nhật Hash mới
                    Employees.update({ Employees.employeeId eq adminId }) {
                        it[pinHash] = newHash
                    }
                    "SUCCESS"
                }

                when (result) {
                    "SUCCESS" -> call.respond(HttpStatusCode.OK, "Đổi PIN thành công")
                    "IDENTICAL" -> call.respond(HttpStatusCode.Conflict, "Mã PIN mới không được trùng với mã PIN hiện tại")
                    "NOT_FOUND" -> call.respond(HttpStatusCode.NotFound, "Không tìm thấy tài khoản Admin")
                    else -> call.respond(HttpStatusCode.InternalServerError, "Lỗi hệ thống")
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error")
            }
        }
        post("/admin/set-default-pin") {
            try {
                val req = call.receive<SetDefaultPinRequest>()

                if (req.cardUuid.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "Missing cardUuid")
                    return@post
                }

                val updated = transaction {
                    Employees.update({ Employees.cardUuid eq req.cardUuid }) {
                        it[isDefaultPin] = req.isDefaultPin
                    }
                }

                if (updated > 0) {
                    call.respond(HttpStatusCode.OK, "PIN status updated to Default: ${req.isDefaultPin}")
                } else {
                    call.respond(HttpStatusCode.NotFound, "User not found with cardUuid: ${req.cardUuid}")
                }
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    e.message ?: "Unknown Error"
                )
            }
        }
    }
}