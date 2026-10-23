package com.dashcam.domain.usecase

import com.dashcam.domain.repository.PinSession
import com.dashcam.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

class ManagePinUseCase(
    private val settings: SettingsRepository,
    private val session: PinSession,
) {
    suspend fun setPin(pin: String) {
        require(pin.length in PIN_LENGTH_RANGE && pin.all(Char::isDigit))
        settings.update { it.copy(pinHash = hash(pin)) }
        session.unlock()
    }

    suspend fun clearPin() = settings.update { it.copy(pinHash = null) }

    /** Returns true and unlocks the session when [pin] matches the stored PIN. */
    suspend fun verify(pin: String): Boolean {
        val stored = settings.settings.first().pinHash ?: return true
        val matches = stored == hash(pin)
        if (matches) session.unlock()
        return matches
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("dashcam:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        val PIN_LENGTH_RANGE = 4..8
    }
}
