package app.vpn.client.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.ClientStore
import app.vpn.client.data.ServerRepository
import app.vpn.common.Backend
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import app.vpn.ui.GradientButton

enum class AdminSection { HOME, ACCOUNTS, PRO_SERVERS, PLANS, MISSIONS, NOTICES, PAYMENT, CRYPTO_ORDERS, CONFIG }

/** Resolves the admin session token and backend base for the admin API calls. */
fun adminToken(activity: MainActivity): String = ClientStore(activity).adminToken
fun adminBase(): String = Backend.baseUrl(ServerRepository.config.value)

@Composable
fun AdminPanel(activity: MainActivity, onExit: () -> Unit) {
    var section by remember { mutableStateOf(AdminSection.HOME) }

    BackHandler(enabled = section != AdminSection.HOME) { section = AdminSection.HOME }

    when (section) {
        AdminSection.HOME -> AdminHome(activity, onExit = onExit, open = { section = it })
        AdminSection.ACCOUNTS -> AdminAccounts(activity) { section = AdminSection.HOME }
        AdminSection.PRO_SERVERS -> AdminProServers(activity) { section = AdminSection.HOME }
        AdminSection.PLANS -> AdminPlans(activity) { section = AdminSection.HOME }
        AdminSection.MISSIONS -> AdminMissions(activity) { section = AdminSection.HOME }
        AdminSection.NOTICES -> AdminNotices(activity) { section = AdminSection.HOME }
        AdminSection.PAYMENT -> AdminPayment(activity) { section = AdminSection.HOME }
        AdminSection.CRYPTO_ORDERS -> AdminCryptoOrders(activity) { section = AdminSection.HOME }
        AdminSection.CONFIG -> AdminConfig(activity) { section = AdminSection.HOME }
    }
}

@Composable
private fun AdminHome(activity: MainActivity, onExit: () -> Unit, open: (AdminSection) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("پنل مدیریت", onBack = onExit)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("مدیر: iliyamosa", style = MaterialTheme.typography.bodyMedium, color = AppColors.Magenta)
            AppCard {
                AdminMenuRow(R.drawable.ic_user, "حساب‌های کاربران", "جستجو، پرو، امتیاز، حذف") { open(AdminSection.ACCOUNTS) }
                AdminMenuRow(R.drawable.ic_star, "سرورهای پرو", "افزودن و نام‌گذاری سرورهای ویژه") { open(AdminSection.PRO_SERVERS) }
                AdminMenuRow(R.drawable.ic_crown, "پلن‌های اشتراک", "قیمت، مدت و هزینهٔ امتیازی") { open(AdminSection.PLANS) }
                AdminMenuRow(R.drawable.ic_gift, "ماموریت‌ها", "تعریف ماموریت و امتیاز") { open(AdminSection.MISSIONS) }
                AdminMenuRow(R.drawable.ic_bell, "اعلان‌ها", "ارسال پیام به کاربران") { open(AdminSection.NOTICES) }
                AdminMenuRow(R.drawable.ic_wallet, "روش‌های پرداخت", "کریپتو (TON و…) و کارت‌به‌کارت") { open(AdminSection.PAYMENT) }
                AdminMenuRow(R.drawable.ic_gift, "تأیید پرداخت‌ها", "تأیید واریزی‌های کریپتو و کارت کاربران") { open(AdminSection.CRYPTO_ORDERS) }
                AdminMenuRow(R.drawable.ic_tune, "تنظیمات زنده", "سرور رایگان، پشتیبانی، به‌روزرسانی", last = true) { open(AdminSection.CONFIG) }
            }
            AppCard(modifier = Modifier.clickable { onExit() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_logout, tint = AppColors.Danger, size = 22.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("خروج از پنل مدیریت", color = AppColors.Danger, style = MaterialTheme.typography.titleSmall)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AdminMenuRow(icon: Int, title: String, subtitle: String, last: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(AppColors.Magenta.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { AppIcon(icon, tint = AppColors.Magenta, size = 20.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
        }
    }
    if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.Border.copy(alpha = 0.4f)))
}

// ---- shared small widgets used by all admin sections ----

@Composable
fun AdminField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    number: Boolean = false,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
    )
}

@Composable
fun AdminSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppColors.Violet),
        )
    }
}

@Composable
fun AdminMessage(msg: String?, error: Boolean) {
    if (msg != null) {
        Text(msg, color = if (error) AppColors.Danger else AppColors.Connected, style = MaterialTheme.typography.bodySmall)
    }
}

/** A standard screen scaffold for an admin section: header + scrolling body. */
@Composable
fun AdminSectionScaffold(title: String, onBack: () -> Unit, body: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            body()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun AdminSaveButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    GradientButton(text, onClick = onClick, enabled = enabled, loading = !enabled)
}
