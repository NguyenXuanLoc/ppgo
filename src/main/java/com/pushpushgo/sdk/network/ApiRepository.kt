package com.pushpushgo.sdk.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.pushpushgo.sdk.data.Event
import com.pushpushgo.sdk.data.EventType
import com.pushpushgo.sdk.data.Payload
import com.pushpushgo.sdk.exception.PushPushException
import com.pushpushgo.sdk.network.data.TokenRequest
import com.pushpushgo.sdk.utils.getPlatformPushToken
import com.pushpushgo.sdk.utils.logDebug
import com.pushpushgo.sdk.utils.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

internal class ApiRepository(
    private val apiService: ApiService,
    private val context: Context,
    private val sharedPref: SharedPreferencesHelper,
    private val projectId: String,
    private val apiKey: String,
) {

    suspend fun registerToken(token: String?, apiKey: String = this.apiKey, projectId: String = this.projectId) {
        logDebug("registerToken invoked: $token")
        val tokenToRegister = token ?: sharedPref.lastToken.takeIf { it.isNotEmpty() } ?: withContext(Dispatchers.IO) {
            getPlatformPushToken(context)
        }
        logDebug("Token to register: $tokenToRegister")

        val data = apiService.registerSubscriber(
            token = apiKey,
            projectId = projectId,
            body = TokenRequest(tokenToRegister)
        )
        if (data.id.isNotBlank()) {
            sharedPref.subscriberId = data.id
            sharedPref.isSubscribed = true
        }
        logDebug("RegisterSubscriber received: $data")
    }

    suspend fun unregisterSubscriber(isSubscribed: Boolean = false) {
        logDebug("unregisterSubscriber($isSubscribed) invoked")

        apiService.unregisterSubscriber(
            token = apiKey,
            projectId = projectId,
            subscriberId = sharedPref.subscriberId,
        )
        sharedPref.subscriberId = ""
        sharedPref.isSubscribed = false
    }

    suspend fun unregisterSubscriber(projectId: String, token: String, subscriberId: String) {
        try {
            apiService.unregisterSubscriber(
                token = token,
                projectId = projectId,
                subscriberId = subscriberId,
            )
        } catch (e: PushPushException) {
            when (e.message.orEmpty()) {
                "Not Found", "Subscriber not found" -> logError(e)
                else -> throw e
            }
        }
    }

    suspend fun migrateSubscriber(newProjectId: String, newToken: String) {
        logDebug("migrateSubscriber($newProjectId, $newToken) invoked")

        if (newProjectId.isBlank() || newToken.isBlank()) {
            return logDebug("Empty new project info!")
        }

        // unregister current
        unregisterSubscriber(
            token = apiKey,
            projectId = projectId,
            subscriberId = sharedPref.subscriberId,
        )

        // register new
        registerToken(
            token = null,
            apiKey = newToken,
            projectId = newProjectId,
        )
    }

    suspend fun sendBeacon(beacon: String) {
        if (!sharedPref.isSubscribed) {
            logDebug("Beacon not sent. Reason: not subscribed")
            return
        }

        apiService.sendBeacon(
            token = apiKey,
            projectId = projectId,
            subscriberId = sharedPref.subscriberId,
            beacon = beacon.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        ).apply {
            Log.e("TAG", "sendBeacon: REWSPONSE"+this.code()+":::NESSAGE"+this.message())
        }
    }

    suspend fun sendEvent(type: EventType, buttonId: Int, campaign: String, project: String?, subscriber: String?) {
        apiService.sendEvent(
            token = apiKey,
            projectId = project ?: projectId,
            event = Event(
                type = type.value,
                payload = Payload(
                    button = buttonId,
                    campaign = campaign,
                    subscriber = subscriber ?: sharedPref.subscriberId,
                )
            ),
        )
    }

    suspend fun getBitmapFromUrl(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null

        return BitmapFactory.decodeStream(
            apiService.getRawResponse(url).byteStream()
        )
    }
}
