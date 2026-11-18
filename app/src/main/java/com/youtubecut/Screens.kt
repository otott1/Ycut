@file:OptIn(ExperimentalMaterial3Api::class)

package com.youtubecut

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun YoutubeCutApp(vm: MainViewModel, askNotifications: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val dl by DownloadHub.state.collectAsStateWithLifecycle()

    BackHandler(enabled = ui.showSettings && !ui.needsKey) { vm.closeSettings() }

    YoutubeCutTheme {
        // الواجهة عربية: نفرض الاتجاه من اليمين لليسار مهما كانت لغة الجهاز
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when {
                    ui.needsKey -> KeyScreen(
                        ui = ui,
                        onSave = vm::saveKey,
                        onCancel = if (ui.changingKey) vm::cancelKeyChange else null,
                    )
                    ui.showSettings -> SettingsScreen(ui, vm)
                    else -> MainScreen(ui, dl, vm, askNotifications)
                }
            }
        }
    }
}

// ───────────────────────── شاشة المفتاح (تظهر مرة واحدة فقط) ─────────────────────────

@Composable
private fun KeyScreen(ui: UiState, onSave: (String) -> Unit, onCancel: (() -> Unit)?) {
    var key by rememberSaveable { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "قص يوتيوب",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (onCancel != null) "أدخل مفتاح Gemini الجديد."
            else "الصق رابط فيديو طويل فيقترح لك Gemini مقاطعه الجاهزة، وتحمّل منها ما تختاره.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "للبدء أدخل مفتاح Gemini API. يُحفظ على هاتفك فقط ولن يُطلب منك مرة أخرى.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("مفتاح Gemini API") },
            singleLine = true,
            enabled = !ui.keyBusy,
            isError = ui.keyError != null,
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(key) }),
            trailingIcon = {
                TextButton(onClick = { show = !show }) { Text(if (show) "إخفاء" else "إظهار") }
            },
        )
        ui.keyError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { uriHandler.openUri("https://aistudio.google.com/apikey") }) {
            Text("الحصول على مفتاح مجاني من Google AI Studio")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onSave(key) },
            enabled = !ui.keyBusy && key.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (ui.keyBusy) {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("حفظ ومتابعة")
            }
        }
        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("إلغاء") }
        }
    }
}

// ───────────────────────── الشاشة الرئيسية ─────────────────────────

@Composable
private fun MainScreen(ui: UiState, dl: DlState, vm: MainViewModel, askNotifications: () -> Unit) {
    val inDownload = dl.items.isNotEmpty()
    val meta = ui.meta

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("قص يوتيوب", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = vm::openSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "الإعدادات")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (inDownload) {
                DownloadBar(dl, onCancel = vm::cancelDownload, onDone = vm::closeDownload)
            } else if (ui.clips.isNotEmpty()) {
                SelectionBar(ui, onDownload = {
                    askNotifications()
                    vm.startDownload()
                })
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            if (inDownload) {
                item {
                    Text(
                        "تحميل المقاطع",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                items(dl.items, key = { it.clip.index }) { DownloadRow(it) }
            } else {
                item { UrlInput(ui, vm) }
                if (ui.busy) item { WorkingBox(ui) }
                ui.error?.let { msg -> item { ErrorBox(msg) } }
                if (meta != null && ui.clips.isNotEmpty()) {
                    item { VideoHeader(ui, meta, vm) }
                    items(ui.clips, key = { it.index }) { c ->
                        ClipRow(c, meta.durationSec, onToggle = { vm.toggle(c.index) })
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlInput(ui: UiState, vm: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedTextField(
            value = ui.url,
            onValueChange = vm::setUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("رابط الفيديو") },
            placeholder = { Text("https://youtu.be/…") },
            singleLine = true,
            enabled = !ui.busy,
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.analyze() }),
            trailingIcon = {
                TextButton(
                    onClick = { clipboard.getText()?.text?.let { vm.setUrl(it) } },
                    enabled = !ui.busy,
                ) { Text("لصق") }
            },
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = vm::analyze,
            enabled = !ui.busy && ui.url.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) { Text("اقتراح المقاطع") }
    }
}

@Composable
private fun WorkingBox(ui: UiState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
        Text(ui.status, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        val p = ui.progress
        if (p == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorBox(msg: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
    ) {
        Text(
            msg,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun VideoHeader(ui: UiState, meta: VideoMeta, vm: MainViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp)) {
        Text(
            meta.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        val length = if (meta.durationSec > 0) "طول الفيديو ${fmt(meta.durationSec)}، و" else ""
        Text(
            "${length}اقترح Gemini ${ui.clips.size} مقطعاً",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = { vm.selectAll(true) }) { Text("تحديد الكل") }
            TextButton(onClick = { vm.selectAll(false) }) { Text("إلغاء التحديد") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ClipRow(c: ClipUi, videoSec: Int, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .alpha(if (c.selected) 1f else 0.5f)
                .padding(vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(checked = c.selected, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ltr("${fmt(c.cutStart)} – ${fmt(c.cutEnd)}"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "المدة ${ltr(fmt(c.cutEnd - c.cutStart))}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (videoSec > 0) {
                        Spacer(Modifier.height(8.dp))
                        Timeline(
                            startFrac = c.cutStart.toFloat() / videoSec,
                            lenFrac = (c.cutEnd - c.cutStart).toFloat() / videoSec,
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** شريط رفيع: موضع المقطع وطوله داخل الفيديو الأصلي (يُرسم دائماً من اليسار لليمين كمشغّل الفيديو). */
@Composable
private fun Timeline(startFrac: Float, lenFrac: Float) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val seg = MaterialTheme.colorScheme.secondary
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(6.dp),
    ) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(color = track, cornerRadius = r)
        val w = (size.width * lenFrac).coerceAtLeast(6.dp.toPx()).coerceAtMost(size.width)
        val x = (size.width * startFrac).coerceIn(0f, size.width - w)
        drawRoundRect(color = seg, topLeft = Offset(x, 0f), size = Size(w, size.height), cornerRadius = r)
    }
}

@Composable
private fun SelectionBar(ui: UiState, onDownload: () -> Unit) {
    val chosen = ui.clips.filter { it.selected }
    val total = chosen.sumOf { it.cutEnd - it.cutStart }
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${chosen.size} من ${ui.clips.size} مقطعاً", fontWeight = FontWeight.SemiBold)
                Text(
                    "المجموع ${ltr(fmt(total))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onDownload, enabled = chosen.isNotEmpty()) { Text("حمّل المحدد") }
        }
    }
}

// ───────────────────────── التحميل ─────────────────────────

@Composable
private fun DownloadRow(item: DlItem) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                when (item.state) {
                    ItemState.Queued -> Text("•", color = MaterialTheme.colorScheme.outline)
                    ItemState.Running -> if (item.progress > 0f) {
                        CircularProgressIndicator(progress = { item.progress }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    ItemState.Done -> Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    ItemState.Failed -> Text("✕", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    ItemState.Cancelled -> Text("–", color = MaterialTheme.colorScheme.outline)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.clip.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val (text, color) = when (item.state) {
                    ItemState.Queued -> "في الانتظار" to MaterialTheme.colorScheme.onSurfaceVariant
                    ItemState.Running -> "جارٍ التحميل…" to MaterialTheme.colorScheme.secondary
                    ItemState.Done -> "تم الحفظ" to MaterialTheme.colorScheme.primary
                    ItemState.Failed -> item.note.ifBlank { "فشل التحميل" } to MaterialTheme.colorScheme.error
                    ItemState.Cancelled -> "أُلغي" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = color)
            }
            val uri = item.uri
            if (item.state == ItemState.Done && uri != null) {
                TextButton(onClick = {
                    try {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, ctx.contentResolver.getType(uri))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    } catch (e: ActivityNotFoundException) {
                        // لا يوجد مشغّل مناسب
                    }
                }) { Text("تشغيل") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun DownloadBar(dl: DlState, onCancel: () -> Unit, onDone: () -> Unit) {
    val done = dl.items.count { it.state == ItemState.Done }
    val total = dl.items.size
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (dl.active) {
                Text("تم $done من $total", fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done / total.toFloat() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                )
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("إيقاف") }
            } else {
                Text("اكتمل: حُفظ $done من $total", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "المكان: ${ltr(dl.location)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("رجوع إلى المقاطع") }
            }
        }
    }
}

// ───────────────────────── الإعدادات ─────────────────────────

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
    )
}

@Composable
private fun Stepper(label: String, value: Int, step: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            onClick = { onChange((value - step).coerceAtLeast(min)) },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp),
        ) { Text("−") }
        Text("$value ث", Modifier.width(64.dp), textAlign = TextAlign.Center)
        OutlinedButton(
            onClick = { onChange((value + step).coerceAtMost(max)) },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp),
        ) { Text("+") }
    }
}

@Composable
private fun SettingsScreen(ui: UiState, vm: MainViewModel) {
    val s = ui.settings
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = vm::closeSettings) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            SectionTitle("مفتاح Gemini")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ltr(ui.maskedKey), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = vm::requestKeyChange) { Text("تغيير المفتاح") }
            }

            SectionTitle("نموذج Gemini")
            Text(
                "التلقائي يختار أحدث نموذج Flash متاح لحسابك" + (s.autoModel?.let { " (حالياً ${ltr(it)})" } ?: "") + ".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Box {
                var open by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(s.model?.let { ltr(it) } ?: "تلقائي")
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("تلقائي") }, onClick = {
                        vm.setModel(null)
                        open = false
                    })
                    ui.models.forEach { m ->
                        DropdownMenuItem(text = { Text(ltr(m)) }, onClick = {
                            vm.setModel(m)
                            open = false
                        })
                    }
                }
            }

            SectionTitle("جودة التحميل")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("720" to "720p", "480" to "480p", "360" to "360p", "audio" to "صوت فقط").forEach { (v, label) ->
                    FilterChip(selected = s.quality == v, onClick = { vm.setQuality(v) }, label = { Text(label) })
                }
            }

            SectionTitle("هوامش القص")
            Text(
                "نبدأ القص قبل بداية السؤال وننهيه بعد نهاية الجواب كي لا تُقطع الكلمات.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Stepper("قبل البداية", s.marginStart, 5, 0, 60, vm::setMarginStart)
            Stepper("بعد النهاية", s.marginEnd, 5, 0, 120, vm::setMarginEnd)
            Text(
                "تُطبَّق على الاقتراحات الجديدة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("تعليمات الاستخراج")
            Text(
                "ما الذي يبحث عنه Gemini في الفيديو. عدّلها لأنواع أخرى من المحتوى (صيغة الإخراج تُضاف تلقائياً).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            var text by remember { mutableStateOf(vm.prefs.instructions) }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    vm.setInstructions(it)
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 12,
            )
            TextButton(onClick = {
                vm.resetInstructions()
                text = DEFAULT_INSTRUCTIONS
            }) { Text("استعادة الافتراضي") }

            SectionTitle("محرك التحميل (yt-dlp)")
            Text(
                "يغيّر يوتيوب آلياته كثيراً، فيُحدَّث المحرك تلقائياً كل يوم. إن فشل التحميل فجرّب التحديث الآن.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = vm::updateYtDlp, enabled = ui.ytdlpStatus != "جارٍ التحديث…") {
                Text("تحديث المحرك الآن")
            }
            if (ui.ytdlpStatus.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(ui.ytdlpStatus, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
