package app.vpn.client.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.ServerRepository
import app.vpn.common.faDigits
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import kotlinx.coroutines.launch

@Composable
fun ProScreen(activity: MainActivity, onBack: () -> Unit, onActivated: () -> Unit) {
    val scope = rememberCoroutineScope()
    val config by ServerRepository.config.collectAsState()
    val proServers by ServerRepository.proServers.collectAsState()
    val proInfo by ServerRepository.proInfo.collectAsState()
    val proLines by ServerRepository.proInfoLines.collectAsState()
    val isPro = remember(proServers, proInfo) { ServerRepository.isPro(activity) }
    val problem = remember(proServers, proInfo) { ServerRepository.proProblem(activity) }

    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    val pro = config.pro

    fun activate() {
        if (link.isBlank()) {
            message = "لینک اشتراک را وارد کنید."
            messageIsError = true
            return
        }
        busy = true
        message = null
        scope.launch {
            val err = ServerRepository.activatePro(activity, link)
            busy = false
            if (err == null) {
                message = "اشتراک پرو با موفقیت فعال شد."
                messageIsError = false
                link = ""
                onActivated()
            } else {
                message = err
                messageIsError = true
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
            Text(pro.title, style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isPro) {
                ProStatusCard(info = proInfo, onClick = {})
                if (proLines.isNotEmpty()) {
                    AppCard {
                        proLines.forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        }
                    }
                }
                Text(
                    "${proServers.size} سرور پرو در دسترس است.".faDigits(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                ServerRepository.refreshAll(activity)
                                busy = false
                                activity.toast("به‌روزرسانی شد.")
                            }
                        },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("به‌روزرسانی") }
                    if (pro.buyLink.isNotBlank()) {
                        Button(
                            onClick = { activity.openLink(pro.buyLink) },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Gold, contentColor = Color(0xFF1F1500)),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("تمدید اشتراک") }
                    }
                }
                TextButton(onClick = { confirmRemove = true }) {
                    Text("حذف اشتراک از این گوشی", color = AppColors.Danger)
                }
            } else {
                AppCard(color = Color(0xFF2A2210)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        AppIcon(R.drawable.ic_star, tint = AppColors.Gold, size = 48.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(pro.title, style = MaterialTheme.typography.titleLarge, color = AppColors.Gold)
                        Spacer(Modifier.height(6.dp))
                        Text(pro.description, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        if (pro.priceText.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(pro.priceText, style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary, textAlign = TextAlign.Center)
                        }
                        if (pro.buyLink.isNotBlank()) {
                            Spacer(Modifier.height(14.dp))
                            Button(
                                onClick = { activity.openLink(pro.buyLink) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Gold, contentColor = Color(0xFF1F1500)),
                                shape = RoundedCornerShape(14.dp),
                            ) { Text(pro.buyText.ifBlank { "خرید اشتراک" }) }
                        }
                    }
                }
                if (problem != null) {
                    Text(problem, color = AppColors.Danger, style = MaterialTheme.typography.bodyMedium)
                }
            }

            AppCard {
                Text(
                    if (isPro) "تغییر لینک اشتراک" else "فعال‌سازی اشتراک",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "بعد از خرید، لینک اشتراک (Subscription) را اینجا وارد کنید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://…") },
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { activate() }, enabled = !busy, shape = RoundedCornerShape(12.dp)) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("فعال‌سازی")
                    }
                    OutlinedButton(
                        onClick = {
                            val text = try {
                                val cm = activity.getSystemService(ClipboardManager::class.java)
                                cm?.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
                            } catch (e: Exception) {
                                ""
                            }
                            if (text.isBlank()) activity.toast("کلیپ‌بورد خالی است.") else link = text.trim()
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("چسباندن") }
                }
                val msg = message
                if (msg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = if (messageIsError) AppColors.Danger else AppColors.Connected, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("حذف اشتراک", style = MaterialTheme.typography.titleMedium) },
            text = { Text("لینک اشتراک پرو از این گوشی حذف شود؟", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    ServerRepository.removePro(activity)
                    onActivated()
                }) { Text("حذف", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("انصراف") } },
            containerColor = AppColors.SurfaceHigh,
        )
    }
}
