package app.vpn.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.vpn.BuildConfig
import app.vpn.R
import app.vpn.common.AdItem
import app.vpn.common.FreeConfig
import app.vpn.common.ManualServer
import app.vpn.common.RemoteConfig
import app.vpn.common.SubscriptionFetcher
import app.vpn.common.faDigits
import app.vpn.common.formatBytes
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab(val title: String) {
    SERVERS("سرورها"), PRO("پرو"), ADS("تبلیغات"), NOTICE("اطلاعیه"), UPDATE("آپدیت"), OTHER("سایر")
}

@Composable
fun AdminApp(activity: AdminActivity) {
    val store = remember { AdminStore(activity) }
    var loggedIn by remember { mutableStateOf(store.token.isNotBlank()) }
    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .safeDrawingPadding()
    ) {
        if (!loggedIn) {
            SetupScreen(activity, store) { loggedIn = true }
        } else {
            EditorScreen(activity, store) {
                store.token = ""
                loggedIn = false
            }
        }
    }
}

// ---------------------------------------------------------------- setup

@Composable
private fun SetupScreen(activity: AdminActivity, store: AdminStore, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf(store.repo) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_shield, tint = AppColors.Gold, size = 30.dp)
            Spacer(Modifier.width(10.dp))
            Text("پنل مدیریت", style = MaterialTheme.typography.headlineSmall)
        }
        AppCard {
            Text("اتصال به GitHub", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "تنظیمات برنامه (سرورها، تبلیغات، اطلاعیه‌ها) در مخزن GitHub شما ذخیره می‌شود. " +
                    "دکمه‌ی زیر را بزنید؛ صفحه‌ی ساخت کلید با تیک public_repo باز می‌شود. " +
                    "Expiration را روی No expiration بگذارید، پایین صفحه Generate token را بزنید و کلیدی را که با ghp_ شروع می‌شود کپی کنید. " +
                    "این کلید فقط روی همین گوشی ذخیره می‌شود؛ آن را به کسی ندهید.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { activity.openLink(GitHubClient.NEW_TOKEN_URL) }) {
                Text("ساخت کلید در GitHub", color = AppColors.Primary)
            }
        }
        Field("نام مخزن (owner/repo)", repo, { repo = it.trim() }, ltr = true)
        Field("کلید GitHub (Token)", token, { token = it.trim() }, ltr = true, password = true)
        if (error != null) Text(error.orEmpty(), color = AppColors.Danger, style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                if (token.isBlank() || !repo.contains("/")) {
                    error = "نام مخزن و کلید را کامل وارد کنید."
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    val err = withContext(Dispatchers.IO) {
                        try {
                            GitHubClient(token, repo, store.branch).checkAccess()
                        } catch (e: Exception) {
                            "اتصال به GitHub انجام نشد: ${e.message ?: "خطای شبکه"} — اگر GitHub فیلتر است، اول فیلترشکن را روشن کنید."
                        }
                    }
                    busy = false
                    if (err == null) {
                        store.token = token
                        store.repo = repo
                        onDone()
                    } else {
                        error = err
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text("ورود")
        }
    }
}

// ---------------------------------------------------------------- editor

@Composable
private fun EditorScreen(activity: AdminActivity, store: AdminStore, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val client = remember(store.token, store.repo) { GitHubClient(store.token, store.repo, store.branch) }

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(RemoteConfig()) }
    var cfg by remember { mutableStateOf(RemoteConfig()) }
    var sha by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(Tab.SERVERS) }
    var confirmExit by remember { mutableStateOf(false) }

    val dirty = cfg != loaded

    fun load() {
        loading = true
        loadError = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { client.loadConfig() }
                loaded = r.config
                cfg = r.config
                sha = r.sha
            } catch (e: Exception) {
                loadError = (e.message ?: "خطای شبکه") + "\nاگر GitHub فیلتر است، اول فیلترشکن را روشن کنید."
            }
            loading = false
        }
    }

    fun save() {
        saving = true
        scope.launch {
            try {
                val prepared = prepareForSave(cfg, loaded)
                val newSha = withContext(Dispatchers.IO) { client.saveConfig(prepared, sha) }
                sha = newSha
                loaded = prepared
                cfg = prepared
                withContext(Dispatchers.IO) { client.purgeCdn() }
                activity.toast("ذخیره شد. گوشی کاربران حداکثر تا چند دقیقه‌ی دیگر تغییرات را می‌گیرند.")
            } catch (e: Exception) {
                activity.toast(e.message ?: "ذخیره انجام نشد.")
            }
            saving = false
        }
    }

    LaunchedEffect(Unit) { load() }
    BackHandler(enabled = dirty) { confirmExit = true }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(R.drawable.ic_shield, tint = AppColors.Gold, size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("پنل مدیریت", style = MaterialTheme.typography.titleLarge)
                Text(
                    "نسخه‌ی ${loaded.revision}".faDigits() + if (dirty) " • تغییرات ذخیره نشده" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dirty) AppColors.Connecting else AppColors.TextMuted,
                )
            }
            IconButton(onClick = { load() }, enabled = !loading && !saving) {
                AppIcon(R.drawable.ic_refresh, tint = AppColors.TextSecondary)
            }
            Button(
                onClick = { save() },
                enabled = dirty && !saving && !loading && loadError == null,
                shape = RoundedCornerShape(12.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    AppIcon(R.drawable.ic_save, tint = Color.White, size = 18.dp)
                }
                Spacer(Modifier.width(6.dp))
                Text("ذخیره")
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Tab.entries.forEach { t ->
                val selected = t == tab
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) AppColors.Primary else AppColors.Surface)
                        .clickable { tab = t }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(t.title, color = if (selected) Color.White else AppColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.Primary)
            }

            loadError != null -> Column(Modifier.padding(20.dp)) {
                Text(loadError.orEmpty(), color = AppColors.Danger)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { load() }) { Text("تلاش دوباره") }
                TextButton(onClick = onLogout) { Text("تغییر کلید GitHub") }
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    Tab.SERVERS -> ServersTab(activity, cfg) { cfg = it }
                    Tab.PRO -> ProTab(cfg) { cfg = it }
                    Tab.ADS -> AdsTab(activity, client, cfg) { cfg = it }
                    Tab.NOTICE -> NoticeTab(cfg) { cfg = it }
                    Tab.UPDATE -> UpdateTab(client, cfg) { cfg = it }
                    Tab.OTHER -> OtherTab(store, cfg, onChange = { cfg = it }, onLogout = onLogout)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("تغییرات ذخیره نشده") },
            text = { Text("تغییراتی که داده‌اید هنوز ذخیره نشده‌اند. خارج می‌شوید؟") },
            confirmButton = { TextButton(onClick = { activity.finish() }) { Text("خروج بدون ذخیره", color = AppColors.Danger) } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("ماندن") } },
            containerColor = AppColors.SurfaceHigh,
        )
    }
}

private fun prepareForSave(cfg: RemoteConfig, loaded: RemoteConfig): RemoteConfig {
    val now = System.currentTimeMillis()
    var notice = cfg.notice
    val noticeChanged = notice.title != loaded.notice.title || notice.message != loaded.notice.message ||
        notice.link != loaded.notice.link || notice.buttonText != loaded.notice.buttonText ||
        (notice.enabled && !loaded.notice.enabled)
    if (notice.id.isBlank() || noticeChanged) notice = notice.copy(id = "n$now")
    val ads = cfg.ads.items.mapIndexed { i, ad -> if (ad.id.isBlank()) ad.copy(id = "ad${now}_$i") else ad }
    return cfg.copy(
        revision = maxOf(cfg.revision, loaded.revision) + 1,
        updatedAt = now / 1000,
        notice = notice,
        ads = cfg.ads.copy(items = ads),
        free = cfg.free.copy(
            subscriptions = cfg.free.subscriptions.map { it.trim() }.filter { it.isNotBlank() },
            servers = cfg.free.servers.map { it.copy(name = it.name.trim(), link = it.link.trim()) }.filter { it.link.isNotBlank() },
            hideKeywords = cfg.free.hideKeywords.map { it.trim() }.filter { it.isNotBlank() },
        ),
        pro = cfg.pro.copy(showcase = cfg.pro.showcase.map { it.trim() }.filter { it.isNotBlank() }),
        mirrors = cfg.mirrors.map { it.trim() }.filter { it.isNotBlank() },
    )
}

// ---------------------------------------------------------------- tabs

@Composable
private fun ServersTab(activity: AdminActivity, cfg: RemoteConfig, onChange: (RemoteConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    val free = cfg.free
    fun setFree(f: FreeConfig) = onChange(cfg.copy(free = f))
    var checkResult by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var bulk by remember { mutableStateOf("") }

    AppCard {
        SwitchRow("سرورهای رایگان فعال باشد", "اگر خاموش کنید فقط کاربران پرو می‌توانند وصل شوند.", free.enabled) {
            setFree(free.copy(enabled = it))
        }
    }

    AppCard {
        Text("لینک‌های اشتراک رایگان (Subscription)", style = MaterialTheme.typography.titleSmall)
        Text(
            "لینک سابسکریپشن یک کاربر مرزبان را اینجا بگذارید. همه‌ی سرورهای آن برای کاربران رایگان نمایش داده می‌شود و هر بار خودکار به‌روز می‌شود.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        StringListEditor(free.subscriptions, "https://…/sub/…", ltr = true) { setFree(free.copy(subscriptions = it)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                checking = true
                checkResult = null
                scope.launch {
                    val lines = withContext(Dispatchers.IO) {
                        free.subscriptions.filter { it.isNotBlank() }.mapIndexed { i, url ->
                            try {
                                val r = SubscriptionFetcher.fetch(url)
                                val usage = r.info?.let { inf ->
                                    if (inf.total > 0) " — ${formatBytes(inf.used)} از ${formatBytes(inf.total)}" else ""
                                }.orEmpty()
                                "✓ لینک ${i + 1}: ${r.servers.size} سرور$usage\n" +
                                    r.servers.take(8).joinToString("\n") { "   • " + it.remarks }
                            } catch (e: Exception) {
                                "✗ لینک ${i + 1}: دریافت نشد (${e.message})"
                            }
                        }
                    }
                    checkResult = if (lines.isEmpty()) "لینکی وارد نشده است." else lines.joinToString("\n\n")
                    checking = false
                }
            },
            enabled = !checking,
            shape = RoundedCornerShape(12.dp),
        ) {
            if (checking) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("بررسی لینک‌ها")
        }
        if (checkResult != null) {
            Spacer(Modifier.height(8.dp))
            Text(checkResult.orEmpty().faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
    }

    AppCard {
        Text("کانفیگ‌های تکی", style = MaterialTheme.typography.titleSmall)
        Text(
            "لینک‌های vless:// ، vmess:// ، trojan:// یا ss:// را اضافه کنید. می‌توانید چند لینک را یکجا (هر خط یکی) بچسبانید.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        free.servers.forEachIndexed { i, s ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.SurfaceHigh)
                    .padding(10.dp)
            ) {
                Field("نام نمایشی (اختیاری)", s.name, { v ->
                    setFree(free.copy(servers = free.servers.toMutableList().also { it[i] = s.copy(name = v) }))
                })
                Spacer(Modifier.height(6.dp))
                Field("لینک", s.link, { v ->
                    setFree(free.copy(servers = free.servers.toMutableList().also { it[i] = s.copy(link = v.trim()) }))
                }, ltr = true)
                ItemActions(
                    index = i,
                    size = free.servers.size,
                    onMove = { from, to -> setFree(free.copy(servers = move(free.servers, from, to))) },
                    onDelete = { setFree(free.copy(servers = free.servers.filterIndexed { j, _ -> j != i })) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Field("چسباندن لینک‌ها (هر خط یک لینک)", bulk, { bulk = it }, ltr = true, singleLine = false, minLines = 3)
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = {
                val links = bulk.lines().flatMap { it.trim().split(Regex("\\s+")) }.filter { it.contains("://") }
                if (links.isEmpty()) {
                    activity.toast("لینکی پیدا نشد.")
                } else {
                    setFree(free.copy(servers = free.servers + links.map { ManualServer("", it) }))
                    bulk = ""
                    activity.toast("${links.size} لینک اضافه شد.".faDigits())
                }
            },
            shape = RoundedCornerShape(12.dp),
        ) {
            AppIcon(R.drawable.ic_add, tint = AppColors.Primary, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text("افزودن")
        }
    }

    AppCard {
        Text("مخفی کردن سرورها", style = MaterialTheme.typography.titleSmall)
        Text(
            "سرورهایی که نامشان یکی از این کلمه‌ها را دارد به کاربران رایگان نمایش داده نمی‌شود.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        StringListEditor(free.hideKeywords, "مثلاً: نت ملی") { setFree(free.copy(hideKeywords = it)) }
    }
}

@Composable
private fun ProTab(cfg: RemoteConfig, onChange: (RemoteConfig) -> Unit) {
    val pro = cfg.pro
    fun set(p: app.vpn.common.ProConfig) = onChange(cfg.copy(pro = p))
    AppCard {
        SwitchRow("بخش پرو نمایش داده شود", null, pro.enabled) { set(pro.copy(enabled = it)) }
    }
    AppCard {
        Text("متن‌ها", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Field("عنوان", pro.title, { set(pro.copy(title = it)) })
        Spacer(Modifier.height(6.dp))
        Field("توضیح", pro.description, { set(pro.copy(description = it)) }, singleLine = false, minLines = 2)
        Spacer(Modifier.height(6.dp))
        Field("قیمت (مثلاً: ماهانه ۱۵۰ هزار تومان)", pro.priceText, { set(pro.copy(priceText = it)) })
        Spacer(Modifier.height(6.dp))
        Field("متن دکمه‌ی خرید", pro.buyText, { set(pro.copy(buyText = it)) })
        Spacer(Modifier.height(6.dp))
        Field("لینک خرید (مثلاً آیدی تلگرام: https://t.me/...)", pro.buyLink, { set(pro.copy(buyLink = it.trim())) }, ltr = true)
    }
    AppCard {
        Text("لوکیشن‌های پرو (ویترین)", style = MaterialTheme.typography.titleSmall)
        Text(
            "این نام‌ها با علامت قفل به کاربران رایگان نشان داده می‌شود تا ترغیب به خرید شوند. سرورهای واقعی پرو از لینک اشتراک خود خریدار می‌آید.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        StringListEditor(pro.showcase, "مثلاً: 🇩🇪 آلمان") { set(pro.copy(showcase = it)) }
    }
}

@Composable
private fun AdsTab(activity: AdminActivity, client: GitHubClient, cfg: RemoteConfig, onChange: (RemoteConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    val ads = cfg.ads
    fun setAds(a: app.vpn.common.AdsConfig) = onChange(cfg.copy(ads = a))
    var uploadingIndex by remember { mutableStateOf(-1) }
    // The upload finishes later; always apply its result to the newest settings, not a stale copy.
    val latestCfg by rememberUpdatedState(cfg)
    val latestOnChange by rememberUpdatedState(onChange)
    fun applyImageUrl(index: Int, url: String) {
        val c = latestCfg
        if (index !in c.ads.items.indices) return
        val items = c.ads.items.toMutableList()
        items[index] = items[index].copy(imageUrl = url)
        latestOnChange(c.copy(ads = c.ads.copy(items = items)))
    }

    AppCard {
        SwitchRow("نمایش تبلیغ قبل از اتصال", "بعد از زدن دکمه‌ی اتصال، تبلیغ نمایش داده می‌شود و بعد از شمارش معکوس، VPN وصل می‌شود.", ads.enabled) {
            setAds(ads.copy(enabled = it))
        }
        SwitchRow("به کاربران پرو هم نمایش داده شود", null, ads.showForPro) { setAds(ads.copy(showForPro = it)) }
    }

    ads.items.forEachIndexed { i, ad ->
        fun setAd(a: AdItem) = setAds(ads.copy(items = ads.items.toMutableList().also { it[i] = a }))
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("تبلیغ ${i + 1}".faDigits(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Switch(checked = ad.enabled, onCheckedChange = { setAd(ad.copy(enabled = it)) })
            }
            Spacer(Modifier.height(6.dp))
            Field("عنوان", ad.title, { setAd(ad.copy(title = it)) })
            Spacer(Modifier.height(6.dp))
            Field("متن", ad.text, { setAd(ad.copy(text = it)) }, singleLine = false, minLines = 2)
            Spacer(Modifier.height(6.dp))
            Field("لینک عکس (اختیاری)", ad.imageUrl, { setAd(ad.copy(imageUrl = it.trim())) }, ltr = true)
            OutlinedButton(
                onClick = {
                    activity.pickImage { uri ->
                        if (uri == null) return@pickImage
                        uploadingIndex = i
                        scope.launch {
                            try {
                                val bytes = withContext(Dispatchers.IO) { activity.readImageForUpload(uri) }
                                if (bytes == null) {
                                    activity.toast("خواندن عکس ممکن نشد.")
                                } else {
                                    val url = withContext(Dispatchers.IO) { client.uploadAdImage(bytes, "jpg") }
                                    applyImageUrl(i, url)
                                    activity.toast("عکس آپلود شد. فراموش نکنید «ذخیره» را بزنید.")
                                }
                            } catch (e: Exception) {
                                activity.toast(e.message ?: "آپلود انجام نشد.")
                            }
                            uploadingIndex = -1
                        }
                    }
                },
                enabled = uploadingIndex == -1,
                shape = RoundedCornerShape(12.dp),
            ) {
                if (uploadingIndex == i) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("انتخاب عکس از گالری و آپلود")
            }
            Spacer(Modifier.height(6.dp))
            Field("لینک کلیک (مثلاً کانال تلگرام)", ad.link, { setAd(ad.copy(link = it.trim())) }, ltr = true)
            Spacer(Modifier.height(6.dp))
            Field("متن دکمه", ad.buttonText, { setAd(ad.copy(buttonText = it)) })
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    Field("ثانیه‌ی نمایش", ad.seconds.toString(), { v ->
                        setAd(ad.copy(seconds = v.filter { it.isDigit() }.take(2).toIntOrNull() ?: 0))
                    }, number = true)
                }
                Box(Modifier.weight(1f)) {
                    Field("وزن (احتمال نمایش)", ad.weight.toString(), { v ->
                        setAd(ad.copy(weight = (v.filter { it.isDigit() }.take(3).toIntOrNull() ?: 1).coerceAtLeast(1)))
                    }, number = true)
                }
            }
            ItemActions(
                index = i,
                size = ads.items.size,
                onMove = { from, to -> setAds(ads.copy(items = move(ads.items, from, to))) },
                onDelete = { setAds(ads.copy(items = ads.items.filterIndexed { j, _ -> j != i })) },
            )
        }
    }

    OutlinedButton(
        onClick = { setAds(ads.copy(items = ads.items + AdItem(id = "", title = "", text = ""))) },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        AppIcon(R.drawable.ic_add, tint = AppColors.Primary, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text("افزودن تبلیغ جدید")
    }
}

@Composable
private fun NoticeTab(cfg: RemoteConfig, onChange: (RemoteConfig) -> Unit) {
    val n = cfg.notice
    fun set(v: app.vpn.common.NoticeConfig) = onChange(cfg.copy(notice = v))
    AppCard {
        SwitchRow("نمایش اطلاعیه", "پیامی که یک بار هنگام باز کردن برنامه به همه نمایش داده می‌شود. با هر تغییر متن، دوباره به همه نمایش داده می‌شود.", n.enabled) {
            set(n.copy(enabled = it))
        }
        Spacer(Modifier.height(8.dp))
        Field("عنوان", n.title, { set(n.copy(title = it)) })
        Spacer(Modifier.height(6.dp))
        Field("متن پیام", n.message, { set(n.copy(message = it)) }, singleLine = false, minLines = 3)
        Spacer(Modifier.height(6.dp))
        Field("متن دکمه (اختیاری)", n.buttonText, { set(n.copy(buttonText = it)) })
        Spacer(Modifier.height(6.dp))
        Field("لینک دکمه (اختیاری)", n.link, { set(n.copy(link = it.trim())) }, ltr = true)
    }
}

@Composable
private fun UpdateTab(client: GitHubClient, cfg: RemoteConfig, onChange: (RemoteConfig) -> Unit) {
    val u = cfg.update
    fun set(v: app.vpn.common.UpdateConfig) = onChange(cfg.copy(update = v))
    AppCard {
        Text("اطلاع‌رسانی نسخه‌ی جدید", style = MaterialTheme.typography.titleSmall)
        Text(
            "شماره‌ی نسخه‌ی فعلی ساخته‌شده: ${BuildConfig.VERSION_CODE}. اگر «آخرین نسخه» از نسخه‌ی کاربر بیشتر باشد، پیشنهاد آپدیت می‌بیند. اگر «حداقل نسخه» بیشتر باشد، بدون آپدیت نمی‌تواند استفاده کند.".faDigits(),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                Field("آخرین نسخه", u.latestVersionCode.toString(), { v ->
                    set(u.copy(latestVersionCode = v.filter { it.isDigit() }.take(6).toIntOrNull() ?: 0))
                }, number = true)
            }
            Box(Modifier.weight(1f)) {
                Field("حداقل نسخه", u.minVersionCode.toString(), { v ->
                    set(u.copy(minVersionCode = v.filter { it.isDigit() }.take(6).toIntOrNull() ?: 0))
                }, number = true)
            }
        }
        TextButton(onClick = { set(u.copy(latestVersionCode = BuildConfig.VERSION_CODE)) }) {
            Text("آخرین نسخه = نسخه‌ی فعلی (${BuildConfig.VERSION_CODE})".faDigits(), color = AppColors.Primary)
        }
        Field("لینک دانلود", u.url, { set(u.copy(url = it.trim())) }, ltr = true)
        TextButton(onClick = {
            set(u.copy(url = "https://github.com/${client.repo}/releases/latest/download/app-universal.apk"))
        }) { Text("استفاده از لینک دانلود GitHub", color = AppColors.Primary) }
        Field("پیام", u.message, { set(u.copy(message = it)) }, singleLine = false, minLines = 2)
    }
}

@Composable
private fun OtherTab(store: AdminStore, cfg: RemoteConfig, onChange: (RemoteConfig) -> Unit, onLogout: () -> Unit) {
    AppCard {
        Text("پشتیبانی", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Field("لینک پشتیبانی (تلگرام)", cfg.support.telegram, { onChange(cfg.copy(support = cfg.support.copy(telegram = it.trim()))) }, ltr = true)
        Spacer(Modifier.height(6.dp))
        Field("عنوان بخش پشتیبانی", cfg.support.text, { onChange(cfg.copy(support = cfg.support.copy(text = it))) })
    }
    AppCard {
        SwitchRow(
            "سایت‌های ایرانی بدون VPN (پیش‌فرض)",
            "کاربر می‌تواند در تنظیمات برنامه این را عوض کند.",
            cfg.bypassIranDefault,
        ) { onChange(cfg.copy(bypassIranDefault = it)) }
    }
    AppCard {
        Text("پیشرفته", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Field("آدرس تست سرعت", cfg.testUrl, { onChange(cfg.copy(testUrl = it.trim())) }, ltr = true)
        Spacer(Modifier.height(8.dp))
        Text(
            "آدرس‌های جایگزین فایل تنظیمات (اگر روزی فایل تنظیمات را جای دیگری هم گذاشتید):",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        StringListEditor(cfg.mirrors, "https://…/config.json", ltr = true) { onChange(cfg.copy(mirrors = it)) }
    }
    AppCard {
        Text("حساب GitHub", style = MaterialTheme.typography.titleSmall)
        Text("مخزن: ${store.repo}", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        TextButton(onClick = onLogout) { Text("خروج و تغییر کلید", color = AppColors.Danger) }
    }
}

// ---------------------------------------------------------------- small widgets

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    ltr: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    number: Boolean = false,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        shape = RoundedCornerShape(12.dp),
        textStyle = if (ltr || number) LocalTextStyle.current.copy(textDirection = TextDirection.Ltr) else LocalTextStyle.current,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
    )
}

@Composable
private fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!desc.isNullOrBlank()) {
                Text(desc, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StringListEditor(items: List<String>, placeholder: String, ltr: Boolean = false, onChange: (List<String>) -> Unit) {
    items.forEachIndexed { i, v ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Field(placeholder, v, { nv -> onChange(items.toMutableList().also { it[i] = nv }) }, ltr = ltr)
            }
            IconButton(onClick = { onChange(items.filterIndexed { j, _ -> j != i }) }) {
                AppIcon(R.drawable.ic_delete, tint = AppColors.Danger, size = 20.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    TextButton(onClick = { onChange(items + "") }) {
        AppIcon(R.drawable.ic_add, tint = AppColors.Primary, size = 18.dp)
        Spacer(Modifier.width(4.dp))
        Text("افزودن", color = AppColors.Primary)
    }
}

@Composable
private fun ItemActions(index: Int, size: Int, onMove: (Int, Int) -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (index > 0) onMove(index, index - 1) }, enabled = index > 0) {
            AppIcon(R.drawable.ic_up, tint = AppColors.TextSecondary)
        }
        IconButton(onClick = { if (index < size - 1) onMove(index, index + 1) }, enabled = index < size - 1) {
            AppIcon(R.drawable.ic_down, tint = AppColors.TextSecondary)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { confirm = true }) {
            AppIcon(R.drawable.ic_delete, tint = AppColors.Danger, size = 18.dp)
            Spacer(Modifier.width(4.dp))
            Text("حذف", color = AppColors.Danger)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("حذف") },
            text = { Text("این مورد حذف شود؟") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onDelete()
                }) { Text("حذف", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("انصراف") } },
            containerColor = AppColors.SurfaceHigh,
        )
    }
}

private fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
    if (from !in list.indices || to !in list.indices) return list
    val m = list.toMutableList()
    val item = m.removeAt(from)
    m.add(to, item)
    return m
}
