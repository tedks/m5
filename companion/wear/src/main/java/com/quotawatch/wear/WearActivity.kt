package com.quotawatch.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

class WearActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var quotas by remember { mutableStateOf(QuotaDataListenerService.latestQuotas) }

            DisposableEffect(Unit) {
                val listener = { quotas = QuotaDataListenerService.latestQuotas }
                QuotaDataListenerService.listeners.add(listener)
                onDispose { QuotaDataListenerService.listeners.remove(listener) }
            }

            MaterialTheme {
                WearQuotaScreen(quotas)
            }
        }
    }
}

@Composable
fun WearQuotaScreen(quotas: List<WearQuota>) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                "QuotaWatch",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
        }

        if (quotas.isEmpty()) {
            item {
                Text(
                    "Waiting for\nphone sync...",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        items(quotas) { quota ->
            WearQuotaRow(quota)
        }
    }
}

@Composable
fun WearQuotaRow(quota: WearQuota) {
    val barColor = when {
        quota.percent < 0.5f -> Color(0xFF4CAF50)
        quota.percent < 0.75f -> Color(0xFFFFEB3B)
        quota.percent < 0.9f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(quota.name, fontSize = 13.sp, color = Color.White)
            Text(
                "${"%.0f".format(quota.percent * 100)}%",
                fontSize = 13.sp,
                color = Color.LightGray
            )
        }

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF333333))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(quota.percent)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
    }
}
