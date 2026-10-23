package app.vpn.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.vpn.client.MainActivity
import app.vpn.client.data.NoticesStore
import app.vpn.common.faDigits
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors

@Composable
fun NoticesScreen(activity: MainActivity, onBack: () -> Unit) {
    val notices by NoticesStore.notices.collectAsState()

    LaunchedEffect(Unit) { NoticesStore.refresh(activity) }
    DisposableEffect(Unit) {
        onDispose { NoticesStore.markSeen(activity) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("اعلان‌ها", onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (notices.isEmpty()) {
                Spacer(Modifier.height(40.dp))
                Text(
                    "فعلاً اعلانی وجود ندارد.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            notices.forEach { n ->
                AppCard {
                    if (n.title.isNotBlank()) {
                        Text(n.title, style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (n.body.isNotBlank()) {
                        Text(n.body, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                    }
                    if (n.created > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(relativeTime(n.created).faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                    }
                    if (n.link.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { activity.openLink(n.link) },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Violet, contentColor = Color.White),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text(n.button.ifBlank { "مشاهده" }) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun relativeTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val mins = diff / 60_000
    val hours = mins / 60
    val days = hours / 24
    return when {
        mins < 1 -> "همین حالا"
        mins < 60 -> "$mins دقیقه پیش"
        hours < 24 -> "$hours ساعت پیش"
        days < 30 -> "$days روز پیش"
        else -> "${days / 30} ماه پیش"
    }
}
