package app.vpn.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.AccountManager
import app.vpn.client.data.ServerRepository
import app.vpn.common.AccountApi
import app.vpn.common.Backend
import app.vpn.common.Http
import app.vpn.common.Plan
import app.vpn.common.faDigits
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProScreen(activity: MainActivity, onBack: () -> Unit, onActivated: () -> Unit) {
    val scope = rememberCoroutineScope()
    val config by ServerRepository.config.collectAsState()
    val account by AccountManager.account.collectAsState()
    val proServers by ServerRepository.proServers.collectAsState()
    val accountIsPro by ServerRepository.accountIsPro.collectAsState()

    var plans by remember { mutableStateOf<List<Plan>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgError by remember { mutableStateOf(false) }

    // Always open the purchase website on our own domain (the Zarinpal flow). The old
    // config.pro.buyLink is often a Telegram link and must not hijack the buy button.
    val buyUrl = remember(config) { Backend.baseUrl(config) + "/buy" }

    fun reload() {
        scope.launch {
            plans = withContext(Dispatchers.IO) {
                try { AccountApi.plans(Backend.baseUrl(config), viaTunnel = Http.tunnelSocksPort > 0) } catch (e: Exception) { emptyList() }
            }
            AccountManager.refreshMe(activity)
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun redeem(plan: Plan) {
        busy = true
        msg = null
        scope.launch {
            val r = AccountManager.redeem(activity, plan.id)
            busy = false
            r.onSuccess {
                msgError = false
                msg = "اشتراک پرو با امتیاز فعال شد!"
                onActivated()
            }
            r.onFailure {
                msgError = true
                msg = it.message ?: "خطا رخ داد."
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(config.pro.title.ifBlank { "اشتراک پرو" }, onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (accountIsPro) {
                ProStatusCard(account = account, onClick = {})
                Text(
                    "${proServers.size} سرور پرو در دسترس شماست.".faDigits(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { reload(); activity.toast("به‌روزرسانی شد.") }, shape = RoundedCornerShape(12.dp)) {
                        Text("به‌روزرسانی")
                    }
                    Button(
                        onClick = { activity.openLink(buyUrl) },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Gold, contentColor = Color(0xFF1F1500)),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("تمدید اشتراک") }
                }
            } else {
                AppCard(color = Color(0xFF2A2210), glow = AppColors.Gold) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        AppIcon(R.drawable.ic_crown, tint = AppColors.Gold, size = 48.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(config.pro.title.ifBlank { "اشتراک پرو" }, style = MaterialTheme.typography.titleLarge, color = AppColors.Gold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            config.pro.description.ifBlank { "سرورهای پرسرعت اختصاصی، بدون محدودیت زمان و بدون تبلیغات." },
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // Plans
            if (plans.isNotEmpty()) {
                Text(if (accountIsPro) "تمدید / پلن‌ها" else "انتخاب پلن", style = MaterialTheme.typography.titleSmall, color = AppColors.TextSecondary)
                val points = account?.points ?: 0
                plans.forEach { plan ->
                    PlanCard(
                        plan = plan,
                        userPoints = points,
                        busy = busy,
                        onBuy = { activity.openLink(buyUrl) },
                        onRedeem = { redeem(plan) },
                    )
                }
            }

            // How it works
            AppCard {
                Text("خرید اشتراک چطور است؟", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "روی «خرید» بزنید تا صفحهٔ پرداخت باز شود. با همین نام کاربری و رمز خودتان وارد شوید، پرداخت کنید و اشتراک بلافاصله روی حسابتان فعال می‌شود. بعد از پرداخت، به برنامه برگردید و «به‌روزرسانی» را بزنید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }

            val m = msg
            if (m != null) {
                Text(m, color = if (msgError) AppColors.Danger else AppColors.Connected, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PlanCard(plan: Plan, userPoints: Int, busy: Boolean, onBuy: () -> Unit, onRedeem: () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(plan.title.ifBlank { "${plan.days} روزه".faDigits() }, style = MaterialTheme.typography.titleSmall)
                Text("${plan.days} روز".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
            }
            Column(horizontalAlignment = Alignment.End) {
                val priceLabel = when {
                    plan.price.isNotBlank() -> plan.price
                    plan.amountToman > 0 -> formatToman(plan.amountToman)
                    else -> "رایگان"
                }
                Text(priceLabel.faDigits(), style = MaterialTheme.typography.titleSmall, color = AppColors.Gold)
                if (plan.pointCost > 0) {
                    Text("یا ${plan.pointCost} امتیاز".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.Primary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (plan.amountToman > 0 || plan.price.isNotBlank()) {
                Button(
                    onClick = onBuy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Gold, contentColor = Color(0xFF1F1500)),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("خرید") }
            }
            if (plan.pointCost > 0) {
                val enough = userPoints >= plan.pointCost
                OutlinedButton(
                    onClick = onRedeem,
                    modifier = Modifier.weight(1f),
                    enabled = enough && !busy,
                    shape = RoundedCornerShape(12.dp),
                ) { Text(if (enough) "دریافت با امتیاز" else "امتیاز کافی نیست", color = if (enough) AppColors.Primary else AppColors.TextMuted) }
            }
        }
    }
}

private fun formatToman(toman: Long): String {
    val s = toman.toString().reversed().chunked(3).joinToString(",").reversed()
    return "$s تومان"
}
