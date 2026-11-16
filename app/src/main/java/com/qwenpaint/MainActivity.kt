package com.qwenpaint

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qwenpaint.ui.QwenPaintTheme

private val SIZES = listOf(
    "512×512" to (512 to 512),
    "512×768" to (512 to 768),
    "768×512" to (768 to 512),
    "640×640" to (640 to 640),
    "384×384" to (384 to 384),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QwenPaintTheme {
                val vm: GenViewModel = viewModel()
                AppScreen(vm)
            }
        }
    }
}

@Composable
private fun AppScreen(vm: GenViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var prompt by rememberSaveable {
        mutableStateOf("a corgi puppy running on a beach at golden hour, cinematic lighting, highly detailed")
    }
    var negative by rememberSaveable {
        mutableStateOf("blurry, low quality, watermark, text, extra limbs, deformed")
    }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var sizeIndex by rememberSaveable { mutableStateOf(0) }
    var steps by rememberSaveable { mutableStateOf(4) }
    var cfg by rememberSaveable { mutableStateOf(1.0f) }
    var seedText by rememberSaveable { mutableStateOf("-1") }
    var autoTranslate by rememberSaveable { mutableStateOf(true) }

    // Turbo 模型与常规模型的最佳参数差异很大，切换时自动纠正
    LaunchedEffect(state.isTurbo) {
        if (state.isTurbo) {
            if (steps > 8) steps = 4
            if (cfg > 2.5f) cfg = 1.0f
        } else {
            if (steps < 10) steps = 20
            if (cfg < 2.5f) cfg = 7.0f
        }
    }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    var pendingSave by remember { mutableStateOf(false) }
    val requestWrite = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingSave) {
            vm.saveImage()
            pendingSave = false
        }
    }

    // 启动时静默检查一次更新（失败不打扰用户）
    LaunchedEffect(Unit) { vm.checkUpdate(silent = true) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(
                versionName = BuildConfig.VERSION_NAME,
                checkingUpdate = state.checkingUpdate,
                updateProgress = state.updateProgress,
                onCheckUpdate = { vm.checkUpdate(silent = false) },
                onProbe = vm::probeHardware,
            )

            UpdateBanner(
                state = state,
                onDownload = vm::downloadUpdate,
                onInstall = vm::installReadyUpdate,
                onCancel = vm::cancelUpdate,
                onDismiss = vm::dismissUpdate,
            )

            ModelCard(
                state = state,
                onImport = { pickModel.launch(arrayOf("*/*")) },
                onLoad = vm::loadModel,
                onSelect = vm::selectSet,
                onPolicy = vm::setPolicy,
                onUseGpu = vm::setUseGpu,
                onThreads = vm::setThreads,
                onDownload = vm::downloadRecommended,
            )

            PromptCard(
                prompt = prompt,
                onPromptChange = { prompt = it },
                negative = negative,
                onNegativeChange = { negative = it },
                showAdvanced = showAdvanced,
                onToggleAdvanced = { showAdvanced = !showAdvanced },
                autoTranslate = autoTranslate,
                onToggleTranslate = { autoTranslate = it },
            )

            ParamCard(
                sizeIndex = sizeIndex,
                onSizeChange = { sizeIndex = it },
                steps = steps,
                onStepsChange = { steps = it },
                cfg = cfg,
                onCfgChange = { cfg = it },
                seedText = seedText,
                onSeedChange = { seedText = it },
                showAdvanced = showAdvanced,
            )

            // 主操作
            Button(
                onClick = {
                    val (w, h) = SIZES[sizeIndex].second
                    val seed = seedText.trim().toLongOrNull() ?: -1L
                    // CLIP 只认英文：中文提示词先过一遍本地词表，转不出来就原样交给模型（会提示）
                    val finalPrompt = if (autoTranslate && ZhPrompt.hasChinese(prompt)) {
                        ZhPrompt.translate(prompt) ?: prompt
                    } else {
                        prompt
                    }
                    vm.generate(finalPrompt, negative, w, h, steps, cfg, seed)
                },
                enabled = state.engineReady && !state.generating && !state.loadingModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                if (state.generating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("生成中…")
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            !state.engineReady -> "请先加载模型"
                            else -> "开始生成"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // 用上一次的实测单步耗时预估本次，改参数时不用反复试错
            if (state.lastStepSeconds > 0f && state.lastPixels > 0) {
                val (curW, curH) = SIZES[sizeIndex].second
                val est = state.lastStepSeconds * (curW * curH).toFloat() / state.lastPixels * steps + 12f
                val over = est > 120f
                Text(
                    buildString {
                        append("预计 ${est.toInt()} 秒")
                        if (over) append("，已超 2 分钟 —— 降到 512×512 或减少步数")
                        append("　（上次实测 ${"%.1f".format(state.lastStepSeconds)} 秒/步）")
                    },
                    fontSize = 11.sp,
                    color = if (over) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.generating) {
                ProgressBlock(
                    completed = state.progressCompleted,
                    total = state.progressTotal,
                    stage = state.progressStage,
                    seconds = state.stepSeconds,
                    onCancel = vm::cancel,
                )
            }

            state.error?.let { NoticeCard(it, isError = true, onClose = vm::clearMessages) }
            if (state.error == null) {
                state.message?.let { NoticeCard(it, isError = false, onClose = vm::clearMessages) }
            }

            // 硬件探测报告
            state.hwProbe?.let { report ->
                AlertDialog(
                    onDismissRequest = vm::dismissProbe,
                    confirmButton = {
                        TextButton(onClick = vm::dismissProbe) { Text("关闭") }
                    },
                    title = { Text("硬件接口探测", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                report,
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                lineHeight = 17.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "截图发给开发者即可，不用自己解读。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }

            state.importProgress?.let { p ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("正在导入模型 ${(p * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    LinearProgressIndicator(
                        progress = { p },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            state.image?.let { bmp ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "生成结果",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                            .clip(RoundedCornerShape(18.dp)),
                    )
                    if (state.lastTiming.isNotBlank()) {
                        Text(
                            state.lastTiming,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${bmp.width} × ${bmp.height}   seed ${state.lastSeed}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        OutlinedButton(
                            onClick = {
                                val needsPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                    ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    ) != PackageManager.PERMISSION_GRANTED
                                if (needsPerm) {
                                    pendingSave = true
                                    requestWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else {
                                    vm.saveImage()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("保存")
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "推理内核 stable-diffusion.cpp · ${SdEngine.version()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun Header(
    versionName: String,
    checkingUpdate: Boolean,
    updateProgress: Float?,
    onCheckUpdate: () -> Unit,
    onProbe: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // 第一行只放标题和图标按钮。图标固定 40dp，窄屏下也不会把入口挤出屏幕。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "QwenPaint",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "端侧离线生图 · v$versionName · 图片不出本机",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onCheckUpdate,
                enabled = !checkingUpdate && updateProgress == null,
            ) {
                if (checkingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "检查更新",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        // 第二行是纯文字入口，三个按钮合计约 210dp，任何机型都放得下。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onCheckUpdate,
                enabled = !checkingUpdate && updateProgress == null,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(if (checkingUpdate) "检查中…" else "检查更新", fontSize = 12.sp)
            }
            TextButton(
                onClick = onProbe,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("硬件探测", fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/dayouAI777/phone-image-huawei/releases/latest"),
                            )
                        )
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("手动下载", fontSize = 12.sp)
            }
        }
    }
}

/** 在线升级横幅：有新版本/下载中/待安装 三种状态 */
@Composable
private fun UpdateBanner(
    state: UiState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val release = state.updateInfo
    val downloading = state.updateProgress != null
    val readyApk = state.updateReadyApk
    if (release == null && readyApk == null) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                readyApk != null -> {
                    Text(
                        "更新包已就绪",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "点击安装。覆盖安装不会清空已下载的模型。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onInstall, modifier = Modifier.weight(1f)) {
                            Text("立即安装")
                        }
                    }
                }
                downloading -> {
                    Text(
                        "正在下载 v${release?.versionName ?: ""}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    LinearProgressIndicator(
                        progress = { state.updateProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Text("取消")
                        }
                    }
                }
                release != null -> {
                    Text(
                        "发现新版本 v${release.versionName}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (release.notes.isNotBlank()) {
                        Text(
                            release.notes,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "覆盖安装，模型文件保留。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDownload, modifier = Modifier.weight(1f)) {
                            Text("下载并安装（${ModelRepository.readableSize(release.size)}）")
                        }
                        OutlinedButton(onClick = onDismiss) {
                            Text("忽略")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    state: UiState,
    onImport: () -> Unit,
    onLoad: () -> Unit,
    onSelect: (ModelSet) -> Unit,
    onPolicy: (MemPolicy) -> Unit,
    onUseGpu: (Boolean) -> Unit,
    onThreads: (Int) -> Unit,
    onDownload: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("模型", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    state.loadingModel -> MaterialTheme.colorScheme.primary
                                    state.engineReady -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            state.loadingModel -> "加载中"
                            state.engineReady -> "已就绪"
                            else -> "未加载"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.currentSet?.let { set ->
                Column {
                    Text(set.displayName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        set.subtitle + if (state.isTurbo) " · Turbo" else " · 标准",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (set.isDiffusion && set.llm == null && set.clipL == null && set.t5 == null) {
                    Text(
                        "⚠ 这个 DiT 模型缺少文本编码器，需同时导入 Qwen3-VL / CLIP / T5 文件",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } ?: Text(
                "还没有模型文件。点「导入模型」选 .gguf / .safetensors；分离式模型（DiT + 编码器 + VAE）逐个导入会自动归组。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.modelSets.size > 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                state.modelSets.take(5).forEach { set ->
                    TextButton(
                        onClick = { onSelect(set) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text(
                            set.displayName,
                            fontSize = 12.sp,
                            maxLines = 1,
                            color = if (set.displayName == state.currentSet?.displayName)
                                MaterialTheme.colorScheme.secondary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            // 计算设备：只有编进了 Vulkan 后端才显示
            if (state.gpuAvailable) {
                Text("计算设备", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = state.useGpu,
                        onClick = { onUseGpu(true) },
                        label = { Text("GPU (Vulkan)", fontSize = 11.sp) },
                        shape = RoundedCornerShape(9.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                    FilterChip(
                        selected = !state.useGpu,
                        onClick = { onUseGpu(false) },
                        label = { Text("CPU", fontSize = 11.sp) },
                        shape = RoundedCornerShape(9.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
                Text(
                    if (state.useGpu) "走 Maleoon GPU 做矩阵运算，通常快数倍；显存不够会自动退回 CPU"
                    else "纯 CPU（NEON）推理，慢但稳，适合大分辨率或显存紧张时",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }

            Text("内存策略", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MemPolicy.entries.forEach { p ->
                    FilterChip(
                        selected = state.policy == p,
                        onClick = { onPolicy(p) },
                        label = { Text(p.label, fontSize = 11.sp) },
                        shape = RoundedCornerShape(9.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
            Text(
                state.policy.hint,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 线程数：麒麟 9020 是 4 大核 + 4 小核，ggml 每个算子都要等齐所有线程，
            // 开 8 线程反而被小核拖慢。默认 4，想验证可以挨个试。
            Text("线程数 · 改完需重新加载模型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(4, 6, 8).forEach { n ->
                    FilterChip(
                        selected = state.userThreads == n,
                        onClick = { onThreads(n) },
                        label = { Text("$n", fontSize = 11.sp) },
                        shape = RoundedCornerShape(9.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
                Text(
                    if (state.userThreads == 4) "只用大核，通常最快" else "小核会拖慢同步等齐",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.downloadProgress != null) {
                LinearProgressIndicator(
                    progress = { state.downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "正在下载模型 ${(state.downloadProgress * 100).toInt()}% · 建议 WiFi",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.modelSets.isEmpty()) {
                Button(
                    onClick = onDownload,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("下载推荐模型 SD-Turbo（1.9 GB）", fontSize = 13.sp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImport, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导入模型", fontSize = 13.sp)
                }
                Button(
                    onClick = onLoad,
                    enabled = state.currentSet?.ready == true && !state.loadingModel && !state.generating,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("加载", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PromptCard(
    prompt: String,
    onPromptChange: (String) -> Unit,
    negative: String,
    onNegativeChange: (String) -> Unit,
    showAdvanced: Boolean,
    onToggleAdvanced: () -> Unit,
    autoTranslate: Boolean,
    onToggleTranslate: (Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("提示词", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                // SD-Turbo 的 CLIP 只认英文，中文提示词走本地词表转换
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "中文自动转英文",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = autoTranslate,
                        onCheckedChange = onToggleTranslate,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                placeholder = { Text("描述你想要的画面，中文会自动转英文", fontSize = 13.sp) },
                shape = RoundedCornerShape(12.dp),
            )
            // 实时显示转换结果，用户能自己判断词有没有被认出来
            if (autoTranslate && ZhPrompt.hasChinese(prompt)) {
                val translated = ZhPrompt.translate(prompt)
                Text(
                    if (translated != null) "→ $translated"
                    else "→ 没认出中文关键词。用「女孩 / 海边 / 黄金时刻 / 赛博朋克」这类常见词，或直接用英文",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = if (translated != null) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.error,
                )
            }
            TextButton(
                onClick = onToggleAdvanced,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(if (showAdvanced) "收起高级设置" else "高级设置（负面提示词 / seed）", fontSize = 12.sp)
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = negative,
                    onValueChange = onNegativeChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("负面提示词", fontSize = 12.sp) },
                    placeholder = { Text("blurry, low quality, watermark", fontSize = 13.sp) },
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ParamCard(
    sizeIndex: Int,
    onSizeChange: (Int) -> Unit,
    steps: Int,
    onStepsChange: (Int) -> Unit,
    cfg: Float,
    onCfgChange: (Float) -> Unit,
    seedText: String,
    onSeedChange: (String) -> Unit,
    showAdvanced: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("参数", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

            Text("尺寸", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SIZES.forEachIndexed { index, (label, _) ->
                    FilterChip(
                        selected = index == sizeIndex,
                        onClick = { onSizeChange(index) },
                        label = { Text(label, fontSize = 11.sp) },
                        shape = RoundedCornerShape(9.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("采样步数", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$steps", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Slider(
                    value = steps.toFloat(),
                    onValueChange = { onStepsChange(it.toInt()) },
                    valueRange = 1f..30f,
                    steps = 28,
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("CFG 引导", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format("%.1f", cfg), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Slider(
                    value = cfg,
                    onValueChange = onCfgChange,
                    valueRange = 1f..12f,
                    steps = 21,
                )
            }

            if (showAdvanced) {
                OutlinedTextField(
                    value = seedText,
                    onValueChange = onSeedChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("seed（-1 随机）", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ProgressBlock(
    completed: Int,
    total: Int,
    stage: String,
    seconds: Float,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildString {
                    if (total > 0) append("$stage  $completed / $total") else append(stage)
                    // 每步耗时是判断「卡在采样」还是「卡在出图」的唯一依据
                    if (seconds > 0.05f) append("  ·  %.1fs/步".format(seconds))
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onCancel, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("取消", fontSize = 12.sp)
            }
        }
        if (total > 0) {
            LinearProgressIndicator(
                progress = { (completed.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun NoticeCard(text: String, isError: Boolean, onClose: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError)
                MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
            else
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                fontSize = 12.sp,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        }
    }
}
