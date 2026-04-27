package com.rjnr.pocketnode.data.gateway

import com.nervosnetwork.ckblightclient.LightClientNative
import javax.inject.Inject
import javax.inject.Singleton

/** Indirection over the static JNI bridge so tests can fake it. */
fun interface BroadcastClient {
    /** Returns the JNI-returned tx hash JSON string, or null on failure. */
    suspend fun sendRaw(txJson: String): String?
}

@Singleton
class LightClientBroadcastClient @Inject constructor() : BroadcastClient {
    override suspend fun sendRaw(txJson: String): String? =
        LightClientNative.nativeSendTransaction(txJson)
}
