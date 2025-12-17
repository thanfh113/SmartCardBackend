package org.example.project.model // Hoặc package tương ứng bên server của bạn

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val cardUuid: String,
    val employeeId: String,
    val name: String,
    val publicKeyHex: String
)

@Serializable
data class TransactionRequest(
    val cardUuid: String,
    val amount: Double,
    val description: String,
    val currentBalance: Double,
    val signatureHex: String
)

@Serializable
data class AvatarUploadRequest(
    val cardUuid: String,
    val avatarBase64: String
)

@Serializable
data class UpdateInfoRequest(
    val cardUuid: String,
    val employeeId: String,
    val name: String,
    val dob: String,        // Ngày sinh
    val department: String, // Tên phòng ban
    val position: String,    // Chức vụ
    val isDefaultPin: Boolean = true
)

@Serializable
data class NextIdResponse(val id: String)

@Serializable
data class UserResponse(
    val cardUuid: String,
    val employeeId: String,
    val name: String,
    val department: String?,
    val role: String,
    val isActive: Boolean,
    val dob: String? = null,      // Thêm ngày sinh
    val position: String? = null,
    val isDefaultPin: Boolean = true,
    val balance: Double
)

@Serializable
data class ChangeStatusRequest(
    val targetUuid: String,
    val isActive: Boolean
)
@Serializable
data class Product(
    val id: Int,
    val name: String,
    val price: Int,
    val category: String, // Dùng để ánh xạ icon
    val isAvailable: Boolean
)

@Serializable
data class HistoryLogEntry(
    val type: String, // CHECK_IN, CHECK_OUT, TOPUP, PAYMENT, RESTRICTED
    val time: String, // Thời gian ISO (ví dụ: "2025-12-14T12:42:01.1994891")
    val name: String, // Tên nhân viên
    val desc: String?, // Mô tả
    val amount: String,
    val balanceAfter: String
)