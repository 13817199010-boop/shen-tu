package org.mossimage.mobile

import android.app.*
import android.content.*
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if(isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen(viewModel()) }
            }
        }
    }
}

class MossViewModel(app: Application) : AndroidViewModel(app) {
    var prompt by mutableStateOf("一只可爱的 Q 版猫咪，举着写有‘你好’的牌子")
    var size by mutableStateOf(256)
    var steps by mutableStateOf("50")
    var seed by mutableStateOf("42")
    var cfg by mutableStateOf("3.0")
    var threads by mutableStateOf(4)
    var busy by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var importing by mutableStateOf(false)
    var logs by mutableStateOf("CPU 首版：实机推理未验证。导入完整四个模型后才能生成。")
    var progress by mutableStateOf(0)
    var total by mutableStateOf(0)
    var modelRevision by mutableStateOf(0)
    var output by mutableStateOf<File?>(null)
    var history by mutableStateOf(listOf<File>())
    var pss by mutableStateOf(0L)
    var nativePid = 0
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var service: Messenger? = null
    private val reply = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if(msg.what == InferenceService.EVENT) {
            val b = msg.data
            append(b.getString("log").orEmpty())
            busy = !b.getBoolean("done")
            progress = b.getInt("step"); total = b.getInt("total")
            pss = b.getLong("pssKb"); nativePid = b.getInt("pid")
            b.getString("output")?.let { output = File(it); refreshHistory() }
        }
        true
    })
    private val connection = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder); connected = true
            send(InferenceService.SUBSCRIBE)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null; connected = false; busy = false; nativePid = 0
            append("推理进程已退出：可能被系统回收、内存不足或原生错误；没有生成成功的证明。")
        }
    }
    init {
        app.bindService(Intent(app, InferenceService::class.java), connection, Context.BIND_AUTO_CREATE)
        refreshHistory()
    }
    private fun send(what: Int, data: Bundle = Bundle()) {
        try { service?.send(Message.obtain(null, what).apply { this.data = data; replyTo = reply }) }
        catch(error: RemoteException) { busy = false; append("服务不可用：${error.message}") }
    }
    fun append(text: String) { logs = (logs + "\n" + text).takeLast(24000) }
    fun generate() {
        if(busy || importing || !connected) return
        val config = GenerationConfig(size, steps.toIntOrNull() ?: 0, seed.toLongOrNull() ?: 0,
            cfg.toFloatOrNull() ?: Float.NaN, threads)
        if(seed.toLongOrNull() == null) { append("Seed 必须为 64 位整数"); return }
        config.error(prompt, ModelStore.complete(getApplication()))?.let { append(it); return }
        busy = true; progress = 0; total = 0; output = null
        try {
            getApplication<Application>().startForegroundService(Intent(getApplication(), InferenceService::class.java))
            send(InferenceService.GENERATE, Bundle().apply {
                putString("prompt", prompt); putInt("size", size); putInt("steps", config.steps)
                putLong("seed", config.seed); putFloat("cfg", config.cfg); putInt("threads", threads)
            })
        } catch(error: Exception) { busy = false; append("无法启动：${error.message}") }
    }
    fun cancel() = send(InferenceService.CANCEL)
    fun forceStop() {
        // Same-UID isolated service process only; never kill the UI or arbitrary process.
        if(nativePid > 0 && nativePid != Process.myPid()) Process.killProcess(nativePid)
    }
    fun import(index: Int, uri: Uri) {
        if(busy || importing) return
        importing = true
        io.execute {
            try {
                var last = 0L
                val status = ModelStore.import(getApplication(), index, uri) { count ->
                    val now = SystemClock.elapsedRealtime()
                    if(now-last > 1000) { last=now; main.post { append("正在导入：${count/1024/1024} MiB") } }
                }
                main.post { append(status); modelRevision++ }
            } catch(error: Exception) { main.post { append("导入失败：${error.message}") } }
            finally { main.post { importing=false } }
        }
    }
    fun delete(index: Int) {
        if(busy || importing) return
        try { ModelLease.exclusive(ModelStore.directory(getApplication())) {
            File(ModelStore.directory(getApplication()), ModelStore.names[index]).delete()
            File(ModelStore.directory(getApplication()), ModelStore.names[index]+".sha256").delete()
        } } catch(error: Exception) { append(error.message.orEmpty()) }
        modelRevision++
    }
    fun refreshHistory() { history = File(getApplication<Application>().filesDir,"history").listFiles()?.filter { it.extension == "png" }?.sortedByDescending { it.name } ?: emptyList() }
    fun export(uri: Uri) {
        val file = output ?: return
        io.execute { runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { stream -> file.inputStream().use { it.copyTo(stream) } } ?: error("无法写入")
        }.onSuccess { main.post { append("PNG 已保存到所选位置") } }.onFailure { main.post { append("保存失败：${it.message}") } } }
    }
    fun diagnostics(): String {
        val app = getApplication<Application>()
        val m = ActivityManager.MemoryInfo(); app.getSystemService(ActivityManager::class.java).getMemoryInfo(m)
        val power = app.getSystemService(PowerManager::class.java)
        return "${Build.MANUFACTURER} ${Build.MODEL} / ${if(Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "SoC 信息不可用"}\nAndroid ${Build.VERSION.RELEASE} / ABI ${Build.SUPPORTED_ABIS.joinToString()}\n物理 RAM ${m.totalMem/1024/1024} MiB；系统可用 ${m.availMem/1024/1024} MiB\n推理进程 PSS ${pss/1024} MiB（最近回调采样，非峰值）\n系统热状态 ${power.currentThermalStatus}（非 GPU 温度）；存储可用 ${ModelStore.directory(app).usableSpace/1024/1024} MiB"
    }
    override fun onCleared() {
        getApplication<Application>().unbindService(connection); io.shutdownNow(); super.onCleared()
    }
}

@Composable private fun Screen(vm: MossViewModel) {
    var importIndex by rememberSaveable { mutableIntStateOf(0) }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(importIndex,it) } }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> uri?.let { vm.export(it) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("MOSS Image Mobile", style=MaterialTheme.typography.headlineMedium)
        Text("完全本地 CPU 推理 · arm64\n实验首版：实机推理与 16GB 内存适配未验证。Vulkan 尚未启用。")
        Text("模型导入",style=MaterialTheme.typography.titleLarge)
        Text("请用平板浏览器从 README 所列来源下载四个文件。App 会复制到私有目录，需额外约 8GB 以上空间；本地 SHA-256 指纹不等于官方完整性验证。")
        val revision = vm.modelRevision
        ModelStore.names.forEachIndexed { index,name ->
            val file = remember(revision,index) { File(ModelStore.directory(context),name) }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(enabled=!vm.busy && !vm.importing, onClick={importIndex=index;importer.launch(arrayOf("*/*"))}) { Text("导入 ${index+1}") }
                Column(Modifier.weight(1f)) { Text(ModelStore.labels[index]); Text(if(file.exists()) "${file.length()/1024/1024} MiB" else "未导入") }
                TextButton(enabled=!vm.busy && !vm.importing && file.exists(), onClick={vm.delete(index)}) { Text("删除") }
            }
        }
        OutlinedTextField(vm.prompt,{vm.prompt=it},label={Text("提示词（中文 / English）")},modifier=Modifier.fillMaxWidth(),enabled=!vm.busy)
        Text("尺寸（768/1024 为高内存实验）")
        Row { listOf(256,512,768,1024).forEach { n -> FilterChip(selected=vm.size==n,onClick={vm.size=n},label={Text("$n")},enabled=!vm.busy) } }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(vm.steps,{vm.steps=it},label={Text("步数 1–100")},modifier=Modifier.weight(1f),enabled=!vm.busy)
            OutlinedTextField(vm.cfg,{vm.cfg=it},label={Text("CFG")},modifier=Modifier.weight(1f),enabled=!vm.busy)
        }
        OutlinedTextField(vm.seed,{vm.seed=it},label={Text("Seed（固定整数便于比较）")},enabled=!vm.busy)
        Text("CPU 线程：保守默认 4，尚未实机测速")
        Row { listOf(4,6,8).forEach { n -> FilterChip(selected=vm.threads==n,onClick={vm.threads=n},label={Text("$n 线程")},enabled=!vm.busy) } }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick=vm::generate,enabled=vm.connected && !vm.busy && !vm.importing) { Text("CPU 生成") }
            OutlinedButton(onClick=vm::cancel,enabled=vm.busy) { Text("取消") }
            TextButton(onClick=vm::forceStop,enabled=vm.busy) { Text("强制停止进程") }
        }
        if(vm.busy) { if(vm.total>0) LinearProgressIndicator(progress={vm.progress.toFloat()/vm.total},modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if(vm.total>0) Text("引擎实际采样 ${vm.progress}/${vm.total}（加载/解码无百分比）")
        vm.output?.let { file ->
            val bitmap = remember(file) { BitmapFactory.decodeFile(file.absolutePath) }
            bitmap?.let { Image(it.asImageBitmap(),"真实生成输出",Modifier.fillMaxWidth().heightIn(max=600.dp)) }
            Button(onClick={exporter.launch("MOSS-${file.name}")}) { Text("保存 PNG") }
        }
        Text("历史图片（点击预览后可保存）")
        vm.history.take(30).forEach { file -> TextButton(onClick={vm.output=file}) { Text(file.name) } }
        Text(vm.diagnostics(),style=MaterialTheme.typography.bodySmall)
        Text("日志",style=MaterialTheme.typography.titleLarge)
        Text(vm.logs,style=MaterialTheme.typography.bodySmall)
    }
}
