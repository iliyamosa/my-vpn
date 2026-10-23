package app.vpn.client.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.ClientStore
import app.vpn.common.faDigits
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String, val icon: Bitmap?)

@Composable
fun AppsScreen(activity: MainActivity, onBack: () -> Unit, onChanged: () -> Unit) {
    val store = remember { ClientStore(activity) }
    var mode by remember { mutableStateOf(store.vpnAppMode) }
    val selected = remember { mutableStateOf(store.vpnApps.toMutableSet()) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(activity) }
        loading = false
    }

    fun persist() {
        store.vpnAppMode = mode
        store.vpnApps = selected.value.toSet()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("برنامه‌ها و تونل انتخابی", onBack, trailing = {
            Text(
                "ذخیره",
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.Primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { persist(); onChanged(); activity.toast("ذخیره شد."); onBack() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        })

        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "انتخاب کنید کدام برنامه‌ها از VPN استفاده کنند. سایت‌ها و برنامه‌های ایرانی در هر حالت بدون VPN باز می‌شوند.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            // Mode toggle
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.Surface.copy(alpha = 0.7f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ModeTab("همه برنامه‌ها", mode == "all", Modifier.weight(1f)) { mode = "all" }
                ModeTab("فقط برنامه‌های انتخابی", mode == "selected", Modifier.weight(1f)) { mode = "selected" }
            }
            Spacer(Modifier.height(12.dp))
            if (mode == "selected") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("جستجوی برنامه…") },
                    shape = RoundedCornerShape(12.dp),
                    textStyle = LocalTextStyle.current,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                Spacer(Modifier.height(8.dp))
                Text("${selected.value.size} برنامه انتخاب شده".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.Primary)
            }
        }

        if (mode == "all") {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppIcon(R.drawable.ic_apps, tint = AppColors.TextMuted, size = 40.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("VPN برای همه‌ی برنامه‌ها فعال است.", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppColors.Primary)
            }
        } else {
            val filtered = remember(apps, query) {
                if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true) }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                items(filtered, key = { it.pkg }) { a ->
                    val checked = a.pkg in selected.value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(AppColors.Surface.copy(alpha = 0.7f))
                            .clickable {
                                val s = selected.value.toMutableSet()
                                if (checked) s.remove(a.pkg) else s.add(a.pkg)
                                selected.value = s
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (a.icon != null) {
                            Image(bitmap = a.icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(34.dp))
                        } else {
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(AppColors.SurfaceHigh))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(a.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (checked) AppColors.Primary else Color.Transparent)
                                .then(if (checked) Modifier else Modifier.borderMuted()),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (checked) AppIcon(R.drawable.ic_check, tint = Color.White, size = 16.dp)
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) AppColors.Violet else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.White else AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

private fun Modifier.borderMuted(): Modifier = this.then(
    androidx.compose.foundation.border(1.5.dp, AppColors.Border, RoundedCornerShape(7.dp))
)

private fun loadApps(activity: MainActivity): List<AppEntry> {
    val pm = activity.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = try {
        pm.queryIntentActivities(intent, 0)
    } catch (e: Exception) {
        emptyList()
    }
    val own = activity.packageName
    val seen = HashSet<String>()
    val out = ArrayList<AppEntry>()
    for (ri in resolved) {
        val pkg = ri.activityInfo?.packageName ?: continue
        if (pkg == own || !seen.add(pkg)) continue
        val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { pkg }
        val icon = try { drawableToBitmap(ri.loadIcon(pm)) } catch (e: Exception) { null }
        out.add(AppEntry(pkg, label, icon))
    }
    out.sortBy { it.label.lowercase() }
    return out
}

private fun drawableToBitmap(d: Drawable?, size: Int = 96): Bitmap? {
    if (d == null) return null
    if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
    val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else size
    val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else size
    return try {
        val bmp = Bitmap.createBitmap(w.coerceAtMost(144), h.coerceAtMost(144), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, canvas.width, canvas.height)
        d.draw(canvas)
        bmp
    } catch (e: Exception) {
        null
    }
}
