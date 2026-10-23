package app.vpn.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.ClientStore
import app.vpn.client.data.ServerRepository
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import app.vpn.ui.SectionTitle
import kotlinx.coroutines.launch

@Composable
fun ServersScreen(
    activity: MainActivity,
    onBack: () -> Unit,
    onOpenPro: () -> Unit,
    onSelected: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val store = remember { ClientStore(activity) }
    var selectedKey by remember { mutableStateOf(store.selectedKey) }
    val config by ServerRepository.config.collectAsState()
    val freeServers by ServerRepository.freeServers.collectAsState()
    val proServers by ServerRepository.proServers.collectAsState()
    val proShowcase by ServerRepository.proShowcase.collectAsState()
    val accountIsPro by ServerRepository.accountIsPro.collectAsState()
    val delays by ServerRepository.delays.collectAsState()
    val testing by ServerRepository.testing.collectAsState()
    val refreshing by ServerRepository.refreshing.collectAsState()
    var lockedDialog by remember { mutableStateOf(false) }

    fun select(key: String) {
        selectedKey = key
        store.selectedKey = key
        onSelected()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { AppIcon(R.drawable.ic_back) }
            Text("انتخاب سرور", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (testing || refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = AppColors.Primary)
                Spacer(Modifier.width(12.dp))
            } else {
                TextButton(onClick = {
                    scope.launch { ServerRepository.testServers(activity, ServerRepository.usableServers(activity)) }
                }) {
                    AppIcon(R.drawable.ic_bolt, tint = AppColors.Primary, size = 18.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("تست سرعت", color = AppColors.Primary)
                }
                IconButton(onClick = { scope.launch { ServerRepository.refreshAll(activity) } }) {
                    AppIcon(R.drawable.ic_refresh, tint = AppColors.TextSecondary)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                ServerRow(
                    title = "خودکار",
                    subtitle = "اتصال به سریع‌ترین سرور",
                    icon = R.drawable.ic_bolt,
                    selected = selectedKey == ClientStore.AUTO,
                    delay = null,
                    locked = false,
                    onClick = { select(ClientStore.AUTO) },
                )
            }

            if (accountIsPro && proServers.isNotEmpty()) {
                item { SectionTitle("سرورهای پرو ⭐") }
                items(proServers, key = { "p_" + it.key }) { s ->
                    ServerRow(
                        title = s.remarks,
                        subtitle = null,
                        icon = R.drawable.ic_star,
                        selected = selectedKey == s.key,
                        delay = delays[s.key],
                        locked = false,
                        onClick = { select(s.key) },
                    )
                }
            } else if (proShowcase.isNotEmpty()) {
                item { SectionTitle("سرورهای پرو ⭐") }
                items(proShowcase.size, key = { "show_$it" }) { index ->
                    ServerRow(
                        title = proShowcase[index],
                        subtitle = "مخصوص کاربران پرو",
                        icon = R.drawable.ic_star,
                        selected = false,
                        delay = null,
                        locked = true,
                        onClick = { lockedDialog = true },
                    )
                }
            }

            if (config.free.enabled) {
                item { SectionTitle("سرورهای رایگان") }
                if (freeServers.isEmpty()) {
                    item {
                        Text(
                            if (refreshing) "در حال دریافت…" else "فعلاً سرور رایگانی موجود نیست.",
                            color = AppColors.TextMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                items(freeServers, key = { "f_" + it.key }) { s ->
                    ServerRow(
                        title = s.remarks,
                        subtitle = null,
                        icon = R.drawable.ic_servers,
                        selected = selectedKey == s.key,
                        delay = delays[s.key],
                        locked = false,
                        onClick = { select(s.key) },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (lockedDialog) {
        AlertDialog(
            onDismissRequest = { lockedDialog = false },
            icon = { AppIcon(R.drawable.ic_crown, tint = AppColors.Gold, size = 32.dp) },
            title = { Text("سرور ویژهٔ پرو", style = MaterialTheme.typography.titleMedium, color = AppColors.Gold) },
            text = {
                Text(
                    "این سرور مخصوص کاربران پروست. با تهیهٔ اشتراک پرو به سرورهای پرسرعت اختصاصی و بدون محدودیت دسترسی پیدا می‌کنید.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { lockedDialog = false; onOpenPro() }) { Text("تهیهٔ اشتراک پرو", color = AppColors.Gold) }
            },
            dismissButton = { TextButton(onClick = { lockedDialog = false }) { Text("بعداً") } },
            containerColor = AppColors.SurfaceHigh,
        )
    }
}

@Composable
private fun ServerRow(
    title: String,
    subtitle: String?,
    icon: Int,
    selected: Boolean,
    delay: Long?,
    locked: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) AppColors.Primary else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.Surface.copy(alpha = 0.8f))
            .border(1.5.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background((if (locked) AppColors.Gold else AppColors.Primary).copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(icon, tint = if (locked) AppColors.Gold else AppColors.Primary, size = 20.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (locked) AppColors.TextSecondary else AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
            }
        }
        if (delay != null) {
            DelayPill(delay)
            Spacer(Modifier.width(6.dp))
        }
        if (selected) AppIcon(R.drawable.ic_check, tint = AppColors.Primary, size = 20.dp)
    }
}
