package com.teledrive.app.telegram

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class RecaptchaRequest(
    val verificationId: Long,
    val action: String,
    val recaptchaKeyId: String
)

class AuthRepository(private val tdLibManager: TdLibManager) {

    val authState: StateFlow<TdLibAuthState> = tdLibManager.authState
    val connectionState: StateFlow<TdLibConnectionState> = tdLibManager.connectionState
    val recaptchaRequests: SharedFlow<RecaptchaRequest> = tdLibManager.recaptchaRequests

    suspend fun restartClient(apiId: Int, apiHash: String) {
        tdLibManager.restartClient(apiId, apiHash)
    }

    suspend fun submitPhoneNumber(phone: String): Result<Unit> {
        return try {
            tdLibManager.setPhoneNumber(phone)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitBotToken(botToken: String): Result<Unit> {
        return try {
            tdLibManager.checkAuthenticationBotToken(botToken)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestQrCodeAuthentication(): Result<Unit> {
        return try {
            tdLibManager.requestQrCodeAuthentication()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    suspend fun submitRecaptchaToken(verificationId: Long, token: String): Result<Unit> {
        return try {
            tdLibManager.setApplicationVerificationToken(verificationId, token)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitCode(code: String): Result<Unit> {
        return try {
            tdLibManager.submitAuthCode(code)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitPassword(password: String): Result<Unit> {
        return try {
            tdLibManager.submit2FAPassword(password)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout(): Result<Unit> {
        return try {
            tdLibManager.logout()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isAuthenticated(): Boolean {
        return authState.value is TdLibAuthState.Ready
    }

    suspend fun getUserInfo(): Result<Pair<String, String?>> {
        return try {
            Result.success(tdLibManager.getMe())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
