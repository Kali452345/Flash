package com.transfer.flash.identity

import android.content.Context
import com.transfer.flash.core.security.identity.AndroidPreferencesIdentityStore
import com.transfer.flash.core.security.identity.FlashIdentityStore

/**
 * App-level identity accessor delegating to [AndroidPreferencesIdentityStore].
 * Retained for backward compatibility with existing legacy controllers.
 */
class AppIdentity(
    context: Context,
    private val store: FlashIdentityStore = AndroidPreferencesIdentityStore(context),
) {
    val deviceId: String
        get() = store.getIdentity().deviceId.value

    val friendlyName: String
        get() = store.getIdentity().friendlyName
}
