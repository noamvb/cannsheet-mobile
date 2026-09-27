package com.example.wear

import android.content.Context
import com.example.widget.currentPenTileModel
import com.example.widget.resolve
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

object WearPenStatePublisher {
    suspend fun publish(context: Context) {
        runCatching {
            val appContext = context.applicationContext
            val model = currentPenTileModel(appContext)
            val fields = wearPenStateFields(
                label = model.label.resolve(appContext),
                action = wearPenAction(model.state),
                stampMillis = System.currentTimeMillis(),
            )
            val request = PutDataMapRequest.create(WEAR_PEN_STATE_PATH)
            fields.forEach { (key, value) ->
                when (value) {
                    is Int -> request.dataMap.putInt(key, value)
                    is String -> request.dataMap.putString(key, value)
                    is Long -> request.dataMap.putLong(key, value)
                }
            }
            Wearable.getDataClient(appContext)
                .putDataItem(request.asPutDataRequest().setUrgent())
        }
    }
}
