package app.vpn.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.DiagLevel
import app.vpn.client.data.DiagLine
import app.vpn.client.data.Diagnostics
import app.vpn.client.data.ServerRepository
import app.vpn.common.faDigits
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(activity: MainActivity, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<DiagLine>() }
    var running by remember { mutableStateOf(false) }

    fun start() {
        if (running) return
        lines.clear()
        running = true
        scope.launch {
            try {
                Diagnostics.run(activity) { line -> activity.runOnUiThread { lines.add(line) } }
                ServerRepository.refreshAll(activity)
            } finally {
                running = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { AppIcon(R.drawable.ic_back) }
            Text("عیب‌یابی اتصال", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppCard {
                Text(
                    "این بخش قدم‌به‌قدم بررسی می‌کند برنامه به کدام منبع‌ها و سرورها دسترسی دارد. " +
                        "بعد از پایان، «کپی گزارش» را بزنید و برای پشتیبانی بفرستید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { start() }, enabled = !running, shape = RoundedCornerShape(12.dp)) {
                        if (running) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (lines.isEmpty()) "شروع بررسی" else "بررسی دوباره")
                    }
                    if (lines.isNotEmpty() && !running) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val cm = activity.getSystemService(ClipboardManager::class.java)
                                    cm?.setPrimaryClip(ClipData.newPlainText("report", Diagnostics.report(lines)))
                                    activity.toast("گزارش کپی شد")
                                } catch (e: Exception) {
                                    activity.toast("کپی ممکن نشد")
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("کپی گزارش") }
                    }
                }
            }
            lines.forEach { l ->
                val color = when (l.level) {
                    DiagLevel.OK -> AppColors.Connected
                    DiagLevel.WARN -> AppColors.Connecting
                    DiagLevel.FAIL -> AppColors.Danger
                    DiagLevel.INFO -> AppColors.Primary
                }
                val mark = when (l.level) {
                    DiagLevel.OK -> "✅"
                    DiagLevel.WARN -> "⚠️"
                    DiagLevel.FAIL -> "❌"
                    DiagLevel.INFO -> "ℹ️"
                }
                AppCard(glow = color) {
                    Text("$mark  ${l.title}", style = MaterialTheme.typography.titleSmall, color = color)
                    Text(l.detail.faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
