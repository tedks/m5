package com.quotawatch

import android.app.Application
import com.quotawatch.data.QuotaRepository

class QuotaWatchApp : Application() {
    // Process-lifetime singleton owning the quota pipeline. Held here (rather than in the
    // ViewModel) so the foreground refresh service can keep refreshing after the last Activity
    // is gone. Simple lazy val — no DI framework for a two-consumer app.
    val repository: QuotaRepository by lazy { QuotaRepository(this) }
}
