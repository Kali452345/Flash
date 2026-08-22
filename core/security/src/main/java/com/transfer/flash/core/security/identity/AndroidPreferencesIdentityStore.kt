package com.transfer.flash.core.security.identity

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import java.util.UUID

/**
 * Android [SharedPreferences] implementation of [FlashIdentityStore].
 * Maintains 100% backward compatibility with Flash 1.0 identity storage keys.
 */
class AndroidPreferencesIdentityStore(
    private val preferences: SharedPreferences,
    private val defaultNameProvider: () -> String = ::defaultDeviceName,
) : FlashIdentityStore {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    )

    override fun getIdentity(): FlashIdentity {
        val rawDeviceId = preferences.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { generated ->
                preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
            }

        val friendlyName = preferences.getString(KEY_FRIENDLY_NAME, null)
            ?: defaultNameProvider().also { generated ->
                preferences.edit().putString(KEY_FRIENDLY_NAME, generated).apply()
            }

        return FlashIdentity(
            deviceId = FlashDeviceId(rawDeviceId),
            friendlyName = friendlyName,
        )
    }

    override fun updateFriendlyName(name: String): FlashResult<Unit> {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            return FlashResult.Failure(FlashError.StorageError("Friendly name cannot be blank"))
        }
        preferences.edit().putString(KEY_FRIENDLY_NAME, trimmed).apply()
        return FlashResult.Success(Unit)
    }

    companion object {
        const val PREFERENCES_NAME = "flash_identity"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_FRIENDLY_NAME = "friendly_name"

        fun defaultDeviceName(): String {
            val model = Build.MODEL?.trim().orEmpty()
            return if (model.isBlank()) "Flash Android" else "Flash $model"
        }
    }
}
