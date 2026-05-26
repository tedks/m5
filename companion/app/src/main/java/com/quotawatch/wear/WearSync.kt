package com.quotawatch.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.quotawatch.api.QuotaSnapshot

class WearSync(context: Context) {

    companion object {
        const val TAG = "WearSync"
        const val QUOTA_PATH = "/quotas"
    }

    private val dataClient: DataClient = Wearable.getDataClient(context)

    fun syncQuotas(snapshot: QuotaSnapshot) {
        val request = PutDataMapRequest.create(QUOTA_PATH).apply {
            dataMap.putLong("timestamp", snapshot.timestamp)
            dataMap.putInt("count", snapshot.quotas.size)
            snapshot.quotas.forEachIndexed { i, q ->
                dataMap.putString("name_$i", q.name)
                dataMap.putFloat("used_$i", q.used)
                dataMap.putFloat("limit_$i", q.limit)
                dataMap.putString("unit_$i", q.unit)
            }
        }.asPutDataRequest().setUrgent()

        dataClient.putDataItem(request)
            .addOnSuccessListener { Log.d(TAG, "Synced to wear") }
            .addOnFailureListener { Log.e(TAG, "Wear sync failed", it) }
    }
}
