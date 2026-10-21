package app.vpn.client.ui

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vpn.BuildConfig
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.ClientStore
import app.vpn.client.data.ServerRepository
import app.vpn.client.vpn.CoreManager
import app.vpn.common.faDigits
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(activity: MainActivity, onBack: () -> Unit, onSettingsChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember { ClientStore(activity) }
    val config by ServerRepository.config.collectAsState()
    val refreshing by ServerRepository.refreshing.collectAsState()
    var bypass by remember {
        mutableStateOf(
            when (store.bypassIranMode) {
                0 -> false
                1 -> true
                else -> config.bypassIranDefault
            }
        )
    }
    val coreVersion = remember { CoreManager.version(activity) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { AppIcon(R.drawable.ic_back) }
            Text("تنظیمات", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("سایت‌های ایرانی بدون VPN", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "سایت‌ها و برنامه‌های ایرانی مستقیم باز می‌شوند و حجم اشتراک شما مصرف نمی‌شود.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = bypass,
                        onCheckedChange = {
                            bypass = it
                            store.bypassIranMode = if (it) 1 else 0
                            onSettingsChanged()
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Primary),
                    )
                }
            }

            AppCard {
                Text("لیست سرورها", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { scope.launch { ServerRepository.refreshAll(activity) } },
                    enabled = !refreshing,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("به‌روزرسانی سرورها")
                }
            }

            if (config.support.telegram.isNotBlank()) {
                AppCard {
                    Text(config.support.text.ifBlank { "پشتیبانی" }, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { activity.openLink(config.support.telegram) },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        AppIcon(R.drawable.ic_send, tint = AppColors.Primary, size = 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("ارتباط با پشتیبانی")
                    }
                }
            }

            AppCard {
                Text("حریم خصوصی", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "این برنامه سایت‌هایی که باز می‌کنید، IP و ترافیک شما را ذخیره نمی‌کند. " +
                        "فقط آمار ناشناس (مدل گوشی، نسخه‌ی برنامه، تعداد اتصال و نظر شما درباره‌ی سرورها) برای بهتر کردن سرویس ارسال می‌شود.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }

            AppCard {
                Text("درباره", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text("نسخه‌ی برنامه: ${BuildConfig.VERSION_NAME}".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                if (coreVersion.isNotBlank()) {
                    Text(coreVersion, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
