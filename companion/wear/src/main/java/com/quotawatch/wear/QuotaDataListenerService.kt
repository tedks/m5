package com.quotawatch.wear

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

data class WearQuota(
    val name: String,
    val used: Float,
    val limit: Float,
    val unit: String
) {
    val percent: Float get() = if (limit > 0) (used / limit).coerceIn(0f, 1f) else 0f
}

class QuotaDataListenerService : WearableListenerService() {

    companion object {
        var latestQuotas: List<WearQuota> = emptyList()
            private set
        var latestTimestamp: Long = 0
            private set
        var listeners: MutableList<() -> Unit> = mutableListOf()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.dataItem.uri.path == "/quotas") {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val count = dataMap.getInt("count", 0)
                val quotas = (0 until count).map { i ->
                    WearQuota(
                        name = dataMap.getString("name_$i", ""),
                        used = dataMap.getFloat("used_$i", 0f),
                        limit = dataMap.getFloat("limit_$i", 0f),
                        unit = dataMap.getString("unit_$i", "")
                    )
                }
                latestQuotas = quotas
                latestTimestamp = dataMap.getLong("timestamp", 0)
                listeners.forEach { it() }
            }
        }
    }
}
