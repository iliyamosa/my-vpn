package app.vpn.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.vpn.client.MainActivity
import app.vpn.common.AdminAccount
import app.vpn.common.AdminApi
import app.vpn.common.CryptoOrder
import app.vpn.common.AdminMission
import app.vpn.common.AdminNotice
import app.vpn.common.AdminPlan
import app.vpn.common.AdminProServer
import app.vpn.common.PaymentSettings
import app.vpn.common.RemoteConfig
import app.vpn.common.faDigits
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun days(proUntil: Long): Long = ((proUntil - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)

// ------------------------------------------------------------------ accounts

@Composable
fun AdminAccounts(activity: MainActivity, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val base = adminBase(); val token = adminToken(activity)
    var query by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf<List<AdminAccount>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { AdminApi.listAccounts(base, token, query.trim(), 0) }
                accounts = page.accounts; total = page.total; msg = null
            } catch (e: Exception) { msg = e.message; error = true }
        }
    }
    LaunchedEffect(Unit) { load() }

    fun act(id: String, block: suspend () -> Unit, ok: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                msg = ok; error = false
                load()
            } catch (e: Exception) { msg = e.message; error = true }
        }
    }

    AdminSectionScaffold("حساب‌های کاربران", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("ایمیل، نام کاربری یا نام") },
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { load() }, shape = RoundedCornerShape(12.dp)) { Text("جستجو") }
        }
        AdminMessage(msg, error)
        Text("مجموع حساب‌ها: $total".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)

        accounts.forEach { a ->
            AppCard(modifier = Modifier.clickable { expanded = if (expanded == a.id) null else a.id }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.displayName, style = MaterialTheme.typography.titleSmall)
                        Text("@${a.username} • ${a.email}", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        Text(
                            "امتیاز: ${a.points} • دستگاه: ${a.devices} • دعوت: ${a.referrals}".faDigits(),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextMuted,
                        )
                    }
                    if (a.isPro) {
                        Text("پرو ${days(a.proUntil)} روز".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.Gold)
                    }
                }
                if (expanded == a.id) {
                    Spacer(Modifier.height(10.dp))
                    AccountActions(
                        account = a,
                        onGrantDays = { d -> act(a.id, { AdminApi.grantDays(base, token, a.id, d) }, "پرو اضافه شد.") },
                        onAddPoints = { p -> act(a.id, { AdminApi.addPoints(base, token, a.id, p) }, "امتیاز اعمال شد.") },
                        onResetPass = { np -> act(a.id, { AdminApi.resetPassword(base, token, a.id, np) }, "رمز عوض شد.") },
                        onClearDevices = { act(a.id, { AdminApi.clearDevices(base, token, a.id) }, "دستگاه‌ها پاک شدند.") },
                        onDelete = { act(a.id, { AdminApi.deleteAccount(base, token, a.id) }, "حساب حذف شد.") },
                    )
                }
            }
        }
        if (accounts.isEmpty()) Text("حسابی پیدا نشد.", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMuted)
    }
}

@Composable
private fun AccountActions(
    account: AdminAccount,
    onGrantDays: (Int) -> Unit,
    onAddPoints: (Int) -> Unit,
    onResetPass: (String) -> Unit,
    onClearDevices: () -> Unit,
    onDelete: () -> Unit,
) {
    var daysText by remember { mutableStateOf("30") }
    var pointsText by remember { mutableStateOf("100") }
    var passText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallNumberField("روز پرو", daysText, Modifier.weight(1f)) { daysText = it }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onGrantDays(daysText.toIntOrNull() ?: 0) }, shape = RoundedCornerShape(10.dp)) { Text("افزودن پرو") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallNumberField("امتیاز (می‌تواند منفی)", pointsText, Modifier.weight(1f)) { pointsText = it }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onAddPoints(pointsText.toIntOrNull() ?: 0) }, shape = RoundedCornerShape(10.dp)) { Text("اعمال") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = passText, onValueChange = { passText = it }, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text("رمز جدید") }, shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { if (passText.length >= 6) onResetPass(passText) }, shape = RoundedCornerShape(10.dp)) { Text("تغییر رمز") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onClearDevices, shape = RoundedCornerShape(10.dp)) { Text("پاک‌کردن دستگاه‌ها") }
            if (!confirmDelete) {
                OutlinedButton(onClick = { confirmDelete = true }, shape = RoundedCornerShape(10.dp)) { Text("حذف حساب", color = AppColors.Danger) }
            } else {
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Danger, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("تأیید حذف") }
            }
        }
    }
}

@Composable
private fun SmallNumberField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier, singleLine = true,
        shape = RoundedCornerShape(10.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

// ------------------------------------------------------------------ pro servers

@Composable
fun AdminProServers(activity: MainActivity, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val base = adminBase(); val token = adminToken(activity)
    var items by remember { mutableStateOf<List<AdminProServer>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // editor
    var editId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }

    fun clearEditor() { editId = ""; name = ""; link = ""; enabled = true }
    fun load() {
        scope.launch {
            try { items = withContext(Dispatchers.IO) { AdminApi.listProServers(base, token) }; msg = null }
            catch (e: Exception) { msg = e.message; error = true }
        }
    }
    LaunchedEffect(Unit) { load() }

    fun save() {
        if (name.isBlank() || link.isBlank()) { msg = "نام و لینک سرور لازم است."; error = true; return }
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { AdminApi.saveProServer(base, token, AdminProServer(editId, name.trim(), link.trim(), enabled, 0)) }
                msg = "ذخیره شد."; error = false; clearEditor(); load()
            } catch (e: Exception) { msg = e.message; error = true } finally { saving = false }
        }
    }
    fun del(id: String) {
        scope.launch {
            try { withContext(Dispatchers.IO) { AdminApi.deleteProServer(base, token, id) }; msg = "حذف شد."; error = false; load() }
            catch (e: Exception) { msg = e.message; error = true }
        }
    }

    AdminSectionScaffold("سرورهای پرو", onBack) {
        Text(
            "فقط نام سرور به کاربران رایگان نشان داده می‌شود (با ستاره و قفل). لینک اتصال فقط برای کاربران پرو فرستاده می‌شود.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
        )
        AppCard {
            Text(if (editId.isBlank()) "سرور جدید" else "ویرایش سرور", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            AdminField("نام سرور (مثلاً آلمان ۱)", name, { name = it })
            Spacer(Modifier.height(8.dp))
            AdminField("لینک اتصال (vless/vmess/…)", link, { link = it }, singleLine = false)
            Spacer(Modifier.height(4.dp))
            AdminSwitchRow("فعال", enabled) { enabled = it }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { AdminSaveButton(if (editId.isBlank()) "افزودن" else "ذخیره", enabled = !saving) { save() } }
                if (editId.isNotBlank()) OutlinedButton(onClick = { clearEditor() }, shape = RoundedCornerShape(12.dp)) { Text("جدید") }
            }
        }
        AdminMessage(msg, error)
        items.forEach { s ->
            AppCard(modifier = Modifier.clickable { editId = s.id; name = s.name; link = s.link; enabled = s.enabled }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall, color = if (s.enabled) AppColors.TextPrimary else AppColors.TextMuted)
                        Text(if (s.link.length > 40) s.link.take(40) + "…" else s.link, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                    }
                    TextButton(onClick = { del(s.id) }) { Text("حذف", color = AppColors.Danger) }
                }
            }
        }
        if (items.isEmpty()) Text("هنوز سرور پرویی اضافه نشده است.", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMuted)
    }
}

// ------------------------------------------------------------------ plans

@Composable
fun AdminPlans(activity: MainActivity, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val base = adminBase(); val token = adminToken(activity)
    var items by remember { mutableStateOf<List<AdminPlan>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    var editId by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var daysText by remember { mutableStateOf("30") }
    var price by remember { mutableStateOf("") }
    var toman by remember { mutableStateOf("") }
    var usd by remember { mutableStateOf("") }
    var pointCost by remember { mutableStateOf("0") }
    var enabled by remember { mutableStateOf(true) }

    fun clearEditor() { editId = ""; title = ""; daysText = "30"; price = ""; toman = ""; usd = ""; pointCost = "0"; enabled = true }
    fun load() {
        scope.launch {
            try { items = withContext(Dispatchers.IO) { AdminApi.listPlans(base, token) }; msg = null }
            catch (e: Exception) { msg = e.message; error = true }
        }
    }
    LaunchedEffect(Unit) { load() }

    fun save() {
        if (title.isBlank()) { msg = "عنوان پلن لازم است."; error = true; return }
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AdminApi.savePlan(base, token, AdminPlan(
                        editId, title.trim(), daysText.toIntOrNull() ?: 0, price.trim(),
                        pointCost.toIntOrNull() ?: 0, toman.toLongOrNull() ?: 0, usd.toDoubleOrNull() ?: 0.0, enabled, 0,
                    ))
                }
                msg = "ذخیره شد."; error = false; clearEditor(); load()
            } catch (e: Exception) { msg = e.message; error = true } finally { saving = false }
        }
    }
    fun del(id: String) {
        scope.launch {
            try { withContext(Dispatchers.IO) { AdminApi.deletePlan(base, token, id) }; msg = "حذف شد."; error = false; load() }
            catch (e: Exception) { msg = e.message; error = true }
        }
    }

    AdminSectionScaffold("پلن‌های اشتراک", onBack) {
        Text(
            "مبلغ تومان برای خرید آنلاین لازم است. هزینهٔ امتیازی را بگذارید تا کاربر بتواند با امتیاز هم پرو بگیرد (۰ یعنی غیرفعال).",
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
        )
        AppCard {
            Text(if (editId.isBlank()) "پلن جدید" else "ویرایش پلن", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            AdminField("عنوان (مثلاً یک‌ماهه)", title, { title = it })
            Spacer(Modifier.height(8.dp))
            AdminField("مدت (روز)", daysText, { daysText = it }, number = true)
            Spacer(Modifier.height(8.dp))
            AdminField("مبلغ ریالی (تومان) — برای درگاه بانکی", toman, { toman = it }, number = true)
            Spacer(Modifier.height(8.dp))
            AdminField("قیمت دلاری (USD) — برای پرداخت ارزی/Beyounger، مثلاً 2.5", usd, { usd = it })
            Spacer(Modifier.height(8.dp))
            AdminField("برچسب قیمت (اختیاری، مثلاً ۴۹٬۰۰۰ تومان)", price, { price = it })
            Spacer(Modifier.height(8.dp))
            AdminField("هزینهٔ امتیازی (۰ = غیرفعال)", pointCost, { pointCost = it }, number = true)
            Spacer(Modifier.height(4.dp))
            AdminSwitchRow("فعال", enabled) { enabled = it }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { AdminSaveButton(if (editId.isBlank()) "افزودن" else "ذخیره", enabled = !saving) { save() } }
                if (editId.isNotBlank()) OutlinedButton(onClick = { clearEditor() }, shape = RoundedCornerShape(12.dp)) { Text("جدید") }
            }
        }
        AdminMessage(msg, error)
        items.forEach { p ->
            AppCard(modifier = Modifier.clickable {
                editId = p.id; title = p.title; daysText = p.days.toString(); price = p.price
                toman = if (p.amountToman > 0) p.amountToman.toString() else ""
                usd = if (p.amountUsd > 0) (if (p.amountUsd % 1.0 == 0.0) p.amountUsd.toInt().toString() else p.amountUsd.toString()) else ""
                pointCost = p.pointCost.toString(); enabled = p.enabled
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleSmall, color = if (p.enabled) AppColors.TextPrimary else AppColors.TextMuted)
                        Text("${p.days} روز • ${p.amountToman} تومان • ${p.pointCost} امتیاز".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                    }
                    TextButton(onClick = { del(p.id) }) { Text("حذف", color = AppColors.Danger) }
                }
            }
        }
        if (items.isEmpty()) Text("هنوز پلنی تعریف نشده است.", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMuted)
    }
}

// ------------------------------------------------------------------ missions

@Composable
fun AdminMissions(activity: MainActivity, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val base = adminBase(); val token = adminToken(activity)
    var items by remember { mutableStateOf<List<AdminMission>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    var editId by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("visit") }
    var goalText by remember { mutableStateOf("1") }
    var link by remember { mutableStateOf("") }
    var pointsText by remember { mutableStateOf("50") }
    var enabled by remember { mutableStateOf(true) }

    fun clearEditor() { editId = ""; title = ""; body = ""; kind = "visit"; goalText = "1"; link = ""; pointsText = "50"; enabled = true }
    fun load() {
        scope.launch {
            try { items = withContext(Dispatchers.IO) { AdminApi.listMissions(base, token) }; msg = null }
            catch (e: Exception) { msg = e.message; error = true }
        }
    }
    LaunchedEffect(Unit) { load() }

    fun save() {
        if (title.isBlank()) { msg = "عنوان ماموریت لازم است."; error = true; return }
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AdminApi.saveMission(base, token, AdminMission(
                        editId, title.trim(), body.trim(), kind, goalText.toIntOrNull() ?: 1, link.trim(),
                        pointsText.toIntOrNull() ?: 0, enabled, 0,
                    ))
                }
                msg = "ذخیره شد."; error = false; clearEditor(); load()
            } catch (e: Exception) { msg = e.message; error = true } finally { saving = false }
        }
    }
    fun del(id: String) {
        scope.launch {
            try { withContext(Dispatchers.IO) { AdminApi.deleteMission(base, token, id) }; msg = "حذف شد."; error = false; load() }
            catch (e: Exception) { msg = e.message; error = true }
        }
    }

    AdminSectionScaffold("ماموریت‌ها", onBack) {
        Text(
            "نوع «عکس» وقتی کاربر تصویر پروفایل بگذارد و «دعوت» با تعداد دوستان دعوت‌شده خودکار بررسی می‌شود. نوع «بازدید» با زدن دکمه توسط کاربر تأیید می‌شود.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
        )
        AppCard {
            Text(if (editId.isBlank()) "ماموریت جدید" else "ویرایش ماموریت", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            AdminField("عنوان", title, { title = it })
            Spacer(Modifier.height(8.dp))
            AdminField("توضیح", body, { body = it }, singleLine = false)
            Spacer(Modifier.height(8.dp))
            Text("نوع ماموریت", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KindChip("عکس پروفایل", kind == "photo") { kind = "photo" }
                KindChip("دعوت دوستان", kind == "invite") { kind = "invite" }
                KindChip("بازدید از لینک", kind == "visit") { kind = "visit" }
            }
            Spacer(Modifier.height(8.dp))
            if (kind == "invite") {
                AdminField("تعداد لازم (goal)", goalText, { goalText = it }, number = true)
                Spacer(Modifier.height(8.dp))
            }
            if (kind == "visit") {
                AdminField("لینک (اختیاری)", link, { link = it })
                Spacer(Modifier.height(8.dp))
            }
            AdminField("امتیاز", pointsText, { pointsText = it }, number = true)
            Spacer(Modifier.height(4.dp))
            AdminSwitchRow("فعال", enabled) { enabled = it }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { AdminSaveButton(if (editId.isBlank()) "افزودن" else "ذخیره", enabled = !saving) { save() } }
                if (editId.isNotBlank()) OutlinedButton(onClick = { clearEditor() }, shape = RoundedCornerShape(12.dp)) { Text("جدید") }
            }
        }
        AdminMessage(msg, error)
        items.forEach { m ->
            AppCard(modifier = Modifier.clickable {
                editId = m.id; title = m.title; body = m.body; kind = m.kind; goalText = m.goal.toString()
                link = m.link; pointsText = m.points.toString(); enabled = m.enabled
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.title, style = MaterialTheme.typography.titleSmall, color = if (m.enabled) AppColors.TextPrimary else AppColors.TextMuted)
                        Text("${kindLabel(m.kind)} • ${m.points} امتیاز".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                    }
                    TextButton(onClick = { del(m.id) }) { Text("حذف", color = AppColors.Danger) }
                }
            }
        }
        if (items.isEmpty()) Text("هنوز ماموریتی تعریف نشده است.", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMuted)
    }
}

private fun kindLabel(kind: String): String = when (kind) {
    "photo" -> "عکس پروفایل"; "invite" -> "دعوت دوستان"; else -> "بازدید"
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) AppColors.Violet else AppColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (selected) Color.White else AppColors.TextSecondary)
    }
}

// ------------------------------------------------------------------ notices

@Composable
fun AdminNotices(activity: MainActivity, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val base = adminBase(); val token = adminToken(activity)
    var items by remember { mutableStateOf<List<AdminNotice>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    var editId by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var button by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }

    fun clearEditor() { editId = ""; title = ""; body = ""; link = ""; button = ""; enabled = true }
    fun load() {
        scope.launch {
            try { items = withContext(Dispatchers.IO) { AdminApi.listNotices(base, token) }; msg = null }
            catch (e: Exception) { msg = e.message; error = true }
        }
    }
    LaunchedEffect(Unit) { load() }

    fun save() {
        if (title.isBlank() && body.isBlank()) { msg = "عنوان یا متن اعلان لازم است."; error = true; return }
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { AdminApi.saveNotice(base, token, AdminNotice(editId, title.trim(), body.trim(), link.trim(), button.trim(), enabled, 0, 0)) }
                msg = "ارسال شد."; error = false; clearEditor(); load()
            } catch (e: Exception) { msg = e.message; error = true } finally { saving = false }
        }
    }
    fun del(id: String) {
        scope.launch {
            try { withContext(Dispatchers.IO) { AdminApi.deleteNotice(base, token, id) }; msg = "حذف شد."; error = false; load() }
            catch (e: Exception) { msg = e.message; error = true }
        }
    }

    AdminSectionScaffold("اعلان‌ها", onBack) {
        AppCard {
            Text(if (editId.isBlank()) "اعلان جدید" else "ویرایش اعلان", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            AdminField("عنوان", title, { title = it })
            Spacer(Modifier.height(8.dp))
            AdminField("متن", body, { body = it }, singleLine = false)
            Spacer(Modifier.height(8.dp))
            AdminField("لینک دکمه (اختیاری)", link, { link = it })
            Spacer(Modifier.height(8.dp))
            AdminField("متن دکمه (اختیاری)", button, { button = it })
            Spacer(Modifier.height(4.dp))
            AdminSwitchRow("فعال (به کاربران نشان داده شود)", enabled) { enabled = it }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { AdminSaveButton(if (editId.isBlank()) "ارسال" else "ذخیره", enabled = !saving) { save() } }
                if (editId.isNotBlank()) OutlinedButton(onClick = { clearEditor() }, shape = RoundedCornerShape(12.dp)) { Text("جدید") }
            }
        }
        AdminMessage(msg, error)
        items.forEach { n ->
            AppCard(modifier = Modifier.clickable { editId = n.id; title = n.title; body = n.body; link = n.link; button = n.button; enabled = n.enabled }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n.title.ifBlank { "(بدون عنوان)" }, style = MaterialTheme.typography.titleSmall, color = if (n.enabled) AppColors.TextPrimary else AppColors.TextMuted)
                        if (n.body.isNotBlank()) Text(n.body.take(60), style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                    }
                    TextButton(onClick = { del(n.id) }) { Text("حذف", color = AppColors.Danger) }
                }
            }
        }
        if (items.isEmpty()) Text("هنوز اعلانی ثبت نشده است.", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMuted)
    }
}

// ------------------------------------------------------------------ payment settings

@Composable
fun AdminPayment(activity: MainActivity, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val base = adminBase(); val token = adminToken(activity)
    var cryptoEnabled by remember { mutableStateOf(false) }
    var cryptoAddresses by remember { mutableStateOf("") }
    var cryptoNote by remember { mutableStateOf("") }
    var cardEnabled by remember { mutableStateOf(false) }
    var cardNumber by remember { mutableStateOf("") }
    var cardHolder by remember { mutableStateOf("") }
    var cardNote by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    fun apply(s: PaymentSettings) {
        cryptoEnabled = s.cryptoEnabled
        cryptoAddresses = s.cryptoAddresses.ifBlank {
            if (s.cryptoAddress.isNotBlank()) (if (s.cryptoAsset.isNotBlank()) "${s.cryptoAsset} = ${s.cryptoAddress}" else s.cryptoAddress) else ""
        }
        cryptoNote = s.cryptoNote
        cardEnabled = s.cardEnabled; cardNumber = s.cardNumber; cardHolder = s.cardHolder; cardNote = s.cardNote
    }

    LaunchedEffect(Unit) {
        try {
            apply(withContext(Dispatchers.IO) { AdminApi.getSettings(base, token) }); loaded = true
        } catch (e: Exception) { msg = e.message; error = true }
    }

    fun save() {
        saving = true
        scope.launch {
            try {
                val s = withContext(Dispatchers.IO) {
                    AdminApi.saveSettings(base, token, PaymentSettings(
                        merchantId = "", gateway = "", sandbox = false, buyUrl = "",
                        cryptoEnabled = cryptoEnabled, cryptoAddresses = cryptoAddresses, cryptoNote = cryptoNote.trim(),
                        cardEnabled = cardEnabled, cardNumber = cardNumber.trim(), cardHolder = cardHolder.trim(), cardNote = cardNote.trim(),
                    ))
                }
                apply(s)
                msg = "تنظیمات پرداخت ذخیره شد."; error = false
            } catch (e: Exception) { msg = e.message; error = true } finally { saving = false }
        }
    }

    AdminSectionScaffold("روش‌های پرداخت", onBack) {
        // ---- crypto (off-bank) ----
        AppCard {
            Text("پرداخت ارزی (کریپتو)", style = MaterialTheme.typography.titleSmall, color = AppColors.Connected)
            Spacer(Modifier.height(4.dp))
            Text(
                "کاربر مبلغ را به یکی از آدرس‌های زیر می‌فرستد و کد تراکنش را ثبت می‌کند؛ بعد از تأیید تو فعال می‌شود. از بانک و شاپرک عبور نمی‌کند، پس ردیابی نمی‌شود.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            AdminSwitchRow("فعال باشد", cryptoEnabled) { cryptoEnabled = it }
            Spacer(Modifier.height(8.dp))
            AdminField("آدرس‌های کیف‌پول — هر خط: «برچسب = آدرس»\nمثال: TON = UQ...", cryptoAddresses, { cryptoAddresses = it }, singleLine = false)
            Spacer(Modifier.height(6.dp))
            Text("می‌توانی چند آدرس در چند خط بگذاری (TON، تتر، مونرو و…).", style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
            Spacer(Modifier.height(8.dp))
            AdminField("توضیح برای کاربر (اختیاری)", cryptoNote, { cryptoNote = it }, singleLine = false)
        }

        // ---- card to card (no gateway) ----
        AppCard {
            Text("کارت‌به‌کارت", style = MaterialTheme.typography.titleSmall, color = AppColors.Gold)
            Spacer(Modifier.height(4.dp))
            Text(
                "کاربر مبلغ را به این کارت واریز می‌کند و کد پیگیری را ثبت می‌کند؛ بعد از تأیید تو فعال می‌شود. بدون درگاه پرداخت.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            AdminSwitchRow("فعال باشد", cardEnabled) { cardEnabled = it }
            Spacer(Modifier.height(8.dp))
            AdminField("شمارهٔ کارت", cardNumber, { cardNumber = it }, number = true)
            Spacer(Modifier.height(8.dp))
            AdminField("نام صاحب کارت", cardHolder, { cardHolder = it })
            Spacer(Modifier.height(8.dp))
            AdminField("توضیح (اختیاری)", cardNote, { cardNote = it }, singleLine = false)
        }

        AdminSaveButton("ذخیره", enabled = loaded && !saving) { save() }
        AdminMessage(msg, error)
    }
}

// ------------------------------------------------------------------ crypto orders

@Composable
fun AdminCryptoOrders(activity: MainActivity, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val base = adminBase(); val token = adminToken(activity)
    var orders by remember { mutableStateOf<List<CryptoOrder>>(emptyList()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    val reasons = remember { mutableStateMapOf<String, String>() }

    fun load() {
        scope.launch {
            try { orders = withContext(Dispatchers.IO) { AdminApi.listCryptoOrders(base, token, "") }; msg = null }
            catch (e: Exception) { msg = e.message; error = true }
        }
    }
    LaunchedEffect(Unit) { load() }

    fun decide(id: String, action: String, reason: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { AdminApi.decideCryptoOrder(base, token, id, action, reason) }
                msg = if (action == "approve") "تأیید شد و پرو فعال شد." else "رد شد."; error = false
                load()
            } catch (e: Exception) { msg = e.message; error = true }
        }
    }

    AdminSectionScaffold("تأیید پرداخت‌ها", onBack) {
        Text(
            "واریزی‌های کاربران (کریپتو و کارت‌به‌کارت) اینجاست. برای کریپتو، کد تراکنش را در بلاک‌چین و برای کارت، واریز را در حسابت بررسی کن؛ اگر درست بود «تأیید» را بزن تا اشتراک فعال شود.",
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
        )
        AdminMessage(msg, error)
        if (orders.isEmpty()) Text("سفارشی ثبت نشده است.", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMuted)
        orders.forEach { o ->
            val pending = o.status == "pending" || o.status == "PENDING_REVIEW"
            val awaiting = o.status == "AWAITING_PAYMENT"
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val methodLabel = if (o.method == "card") "کارت‌به‌کارت" else "کریپتو"
                        Text("@${o.username} • ${o.planTitle}", style = MaterialTheme.typography.titleSmall)
                        val amountTxt = if (o.amount > 0) " • ${formatTomanAdmin(o.amount)}" else ""
                        Text("${o.planDays} روز • $methodLabel$amountTxt".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                    }
                    val color = when {
                        o.status == "approved" || o.status == "APPROVED" -> AppColors.Connected
                        o.status == "rejected" || o.status == "REJECTED" -> AppColors.Danger
                        o.status == "EXPIRED" -> AppColors.TextMuted
                        awaiting -> AppColors.TextSecondary
                        else -> AppColors.Gold
                    }
                    val label = when {
                        o.status == "approved" || o.status == "APPROVED" -> "تأییدشده"
                        o.status == "rejected" || o.status == "REJECTED" -> "ردشده"
                        o.status == "EXPIRED" -> "منقضی"
                        awaiting -> "منتظر واریز"
                        else -> "در انتظار بررسی"
                    }
                    Text(label, style = MaterialTheme.typography.bodySmall, color = color)
                }
                Spacer(Modifier.height(8.dp))
                if (o.method == "card") {
                    DetailRow("کارت مقصد:", o.cardNumber)
                    DetailRow("۶ رقم آخر مبدأ:", o.srcLast6)
                    DetailRow("کد پیگیری:", o.trackRef.ifBlank { o.txid })
                } else {
                    DetailRow("TxID:", o.txid)
                }
                if (o.paidApprox.isNotBlank()) DetailRow("زمان واریز:", o.paidApprox)
                if (o.hasReceipt) Text("📎 رسید ضمیمه شده است", style = MaterialTheme.typography.bodySmall, color = AppColors.Primary)
                if (o.rejectReason.isNotBlank()) DetailRow("دلیل رد:", o.rejectReason)
                if (pending) {
                    Spacer(Modifier.height(10.dp))
                    AdminField("دلیل رد (اختیاری)", reasons[o.id] ?: "", { reasons[o.id] = it })
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            Button(
                                onClick = { decide(o.id, "approve", "") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Connected, contentColor = Color(0xFF05140F)),
                                shape = RoundedCornerShape(12.dp),
                            ) { Text("تأیید و فعال‌سازی") }
                        }
                        OutlinedButton(onClick = { decide(o.id, "reject", reasons[o.id] ?: "") }, shape = RoundedCornerShape(12.dp)) { Text("رد", color = AppColors.Danger) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
        Text(value, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
    }
}

private fun formatTomanAdmin(toman: Long): String {
    val s = toman.toString().reversed().chunked(3).joinToString(",").reversed()
    return "$s تومان"
}

// ------------------------------------------------------------------ live config

@Composable
fun AdminConfig(activity: MainActivity, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val base = adminBase(); val token = adminToken(activity)
    var cfg by remember { mutableStateOf<RemoteConfig?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // editable fields
    var freeEnabled by remember { mutableStateOf(true) }
    var sessionMinutes by remember { mutableStateOf("60") }
    var freeServers by remember { mutableStateOf("") }
    var subscriptions by remember { mutableStateOf("") }
    var proEnabled by remember { mutableStateOf(true) }
    var proTitle by remember { mutableStateOf("") }
    var proDesc by remember { mutableStateOf("") }
    var telegram by remember { mutableStateOf("") }
    var updateUrl by remember { mutableStateOf("") }
    var latestVc by remember { mutableStateOf("0") }
    var minVc by remember { mutableStateOf("0") }
    var updateMsg by remember { mutableStateOf("") }

    fun fill(c: RemoteConfig) {
        freeEnabled = c.free.enabled
        sessionMinutes = c.free.sessionMinutes.toString()
        freeServers = c.free.servers.joinToString("\n") { it.link }
        subscriptions = c.free.subscriptions.joinToString("\n")
        proEnabled = c.pro.enabled
        proTitle = c.pro.title
        proDesc = c.pro.description
        telegram = c.support.telegram
        updateUrl = c.update.url
        latestVc = c.update.latestVersionCode.toString()
        minVc = c.update.minVersionCode.toString()
        updateMsg = c.update.message
    }

    LaunchedEffect(Unit) {
        try {
            val c = withContext(Dispatchers.IO) { AdminApi.getConfig(base, token) }
            cfg = c; fill(c)
        } catch (e: Exception) { msg = e.message; error = true }
    }

    fun save() {
        val base0 = cfg ?: return
        saving = true
        scope.launch {
            try {
                val links = freeServers.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                val subs = subscriptions.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                val updated = base0.copy(
                    free = base0.free.copy(
                        enabled = freeEnabled,
                        sessionMinutes = sessionMinutes.toIntOrNull()?.coerceIn(0, 24 * 60) ?: 60,
                        servers = links.map { app.vpn.common.ManualServer("", it) },
                        subscriptions = subs,
                    ),
                    pro = base0.pro.copy(enabled = proEnabled, title = proTitle.trim(), description = proDesc.trim()),
                    support = base0.support.copy(telegram = telegram.trim()),
                    update = base0.update.copy(
                        url = updateUrl.trim(),
                        latestVersionCode = latestVc.toIntOrNull() ?: 0,
                        minVersionCode = minVc.toIntOrNull() ?: 0,
                        message = updateMsg.trim().ifBlank { base0.update.message },
                    ),
                )
                val delivered = withContext(Dispatchers.IO) { AdminApi.putConfig(base, token, updated) }
                cfg = updated
                msg = "ذخیره و برای $delivered برنامهٔ باز ارسال شد.".faDigits(); error = false
            } catch (e: Exception) { msg = e.message; error = true } finally { saving = false }
        }
    }

    AdminSectionScaffold("تنظیمات زنده", onBack) {
        if (cfg == null && msg == null) {
            Text("در حال بارگذاری…", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMuted)
        }
        AppCard {
            Text("سرور رایگان", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            AdminSwitchRow("سرورهای رایگان فعال باشند", freeEnabled) { freeEnabled = it }
            Spacer(Modifier.height(8.dp))
            AdminField("محدودیت زمان رایگان (دقیقه، ۰=بی‌نهایت)", sessionMinutes, { sessionMinutes = it }, number = true)
            Spacer(Modifier.height(8.dp))
            AdminField("لینک‌های سرور رایگان (هر خط یک لینک)", freeServers, { freeServers = it }, singleLine = false)
            Spacer(Modifier.height(8.dp))
            AdminField("لینک‌های ساب‌اسکریپشن (هر خط یکی)", subscriptions, { subscriptions = it }, singleLine = false)
        }
        AppCard {
            Text("پرو", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            AdminSwitchRow("بخش پرو فعال باشد", proEnabled) { proEnabled = it }
            Spacer(Modifier.height(8.dp))
            AdminField("عنوان پرو", proTitle, { proTitle = it })
            Spacer(Modifier.height(8.dp))
            AdminField("توضیح پرو", proDesc, { proDesc = it }, singleLine = false)
        }
        AppCard {
            Text("پشتیبانی و به‌روزرسانی", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            AdminField("لینک تلگرام پشتیبانی", telegram, { telegram = it })
            Spacer(Modifier.height(8.dp))
            AdminField("لینک دانلود نسخهٔ جدید", updateUrl, { updateUrl = it })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { AdminField("آخرین نسخه", latestVc, { latestVc = it }, number = true) }
                Box(Modifier.weight(1f)) { AdminField("حداقل نسخهٔ لازم", minVc, { minVc = it }, number = true) }
            }
            Spacer(Modifier.height(8.dp))
            AdminField("پیام به‌روزرسانی", updateMsg, { updateMsg = it }, singleLine = false)
        }
        AdminSaveButton("ذخیره و ارسال زنده", enabled = cfg != null && !saving) { save() }
        AdminMessage(msg, error)
    }
}
