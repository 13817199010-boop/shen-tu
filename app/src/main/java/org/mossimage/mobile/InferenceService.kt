package org.mossimage.mobile

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class InferenceService : Service() {
    companion object { const val GENERATE = 1; const val CANCEL = 2; const val EVENT = 3; const val SUBSCRIBE = 4 }
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var client: Messenger? = null
    private val main = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var lastLog = "就绪：CPU，尚无实机验证"
    private var outputPath: String? = null
    private var actualStep = 0
    private var actualSteps = 0
    private var logFile: File? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        message.replyTo?.let { client = it }
        when (message.what) {
            SUBSCRIBE -> emit(lastLog, !busy.get(), outputPath)
            CANCEL -> if (busy.get()) { workerCancel(); emit("已请求取消；模型加载期间可能需要等待。长时间无响应可使用强制停止。") }
            GENERATE -> if (busy.compareAndSet(false, true)) {
                outputPath = null; actualStep = 0; actualSteps = 0
                val data = Bundle(message.data)
                showNotification()
                wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moss:inference").apply { acquire(6 * 60 * 60 * 1000L) }
                try { NativeEngine.prepare(); worker.execute { generate(data) } }
                catch(error: Throwable) {
                    busy.set(false); wake?.let { if(it.isHeld) it.release() }; wake=null
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); emit("原生引擎启动失败：${error.message}", true)
                }
            }
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService must be acknowledged even before the IPC request arrives.
        showNotification()
        if (intent?.action == "cancel") workerCancel()
        return START_NOT_STICKY
    }
    private fun showNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("inference", "离线生成", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(1, Notification.Builder(this, "inference").setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("MOSS Image Mobile").setContentText("本机 CPU 推理，请保持足够可用内存").setContentIntent(open).build())
    }
    private fun workerCancel() { runCatching { NativeEngine.cancel() } }
    @Synchronized fun onNativeLog(bytes: ByteArray) {
        val text = bytes.toString(Charsets.UTF_8).takeLast(4000)
        logFile?.appendText(text)
        main.post { emit(text) }
    }
    fun onNativeProgress(step: Int, total: Int, seconds: Float) {
        main.post { actualStep = step; actualSteps = total; emit("采样 $step/$total，${seconds}s/step") }
    }
    private fun generate(data: Bundle) {
        try { ModelLease.exclusive(ModelStore.directory(this)) { generateLocked(data) } }
        catch (error: Exception) { main.post {
            busy.set(false); wake?.let { if(it.isHeld) it.release() }; wake=null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); emit("无法独占模型：${error.message}", true)
        } }
    }
    private fun generateLocked(data: Bundle) {
        val start = SystemClock.elapsedRealtime()
        var result: String? = null
        var status: String
        try {
            val config = GenerationConfig(data.getInt("size"), data.getInt("steps"), data.getLong("seed"), data.getFloat("cfg"), data.getInt("threads"))
            config.error(data.getString("prompt").orEmpty(), ModelStore.complete(this))?.let { error(it) }
            val history = File(filesDir, "history").apply { mkdirs() }
            logFile = File(history, "$start.log")
            logFile!!.writeText("CPU threads=${config.threads} size=${config.size} steps=${config.steps} seed=${config.seed} cfg=${config.cfg}\n")
            onNativeLog("加载模型；加载阶段没有可用的百分比进度。".toByteArray())
            val pixels = NativeEngine.generate(ModelStore.directory(this).absolutePath.toByteArray(),
                data.getString("prompt")!!.toByteArray(), config.size, config.steps, config.seed, config.cfg, config.threads, this)
            val colors = IntArray(config.size * config.size) { i ->
                (0xff shl 24) or ((pixels[i*3].toInt() and 255) shl 16) or ((pixels[i*3+1].toInt() and 255) shl 8) or (pixels[i*3+2].toInt() and 255)
            }
            val bitmap = Bitmap.createBitmap(colors, config.size, config.size, Bitmap.Config.ARGB_8888)
            val file = File(history, "${System.currentTimeMillis()}.png")
            val partial = File(history, file.name + ".part")
            try {
                partial.outputStream().use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.fd.sync()
                }
                check(partial.renameTo(file)) { "PNG 提交失败" }
            } finally { bitmap.recycle(); partial.delete() }
            result = file.absolutePath
            File(history, "${file.nameWithoutExtension}.json").writeText(JSONObject().apply {
                put("prompt", data.getString("prompt")); put("seed", config.seed); put("size", config.size)
                put("steps", config.steps); put("cfg", config.cfg); put("threads", config.threads)
                put("backend", "CPU"); put("elapsedMs", SystemClock.elapsedRealtime()-start)
            }.toString(2))
            status = "PNG 已生成：耗时 ${(SystemClock.elapsedRealtime()-start)/1000}s；原生上下文已释放。"
        } catch (error: Throwable) { status = "生成失败：${error.message}；耗时 ${(SystemClock.elapsedRealtime()-start)/1000}s" }
        finally { logFile = null; main.post { wake?.let { if (it.isHeld) it.release() }; wake = null; busy.set(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
        main.post { outputPath = result; emit(status, true, result) }
    }
    private fun emit(text: String, done: Boolean = false, output: String? = null) {
        lastLog = text
        val data = Bundle().apply {
            putString("log", text); putBoolean("done", done); putString("output", output)
            putInt("step", actualStep); putInt("total", actualSteps)
            putLong("pssKb", Debug.getPss()); putInt("pid", Process.myPid())
        }
        try { client?.send(Message.obtain(null, EVENT).apply { this.data = data }) } catch (_: RemoteException) { client = null }
    }
    override fun onDestroy() { workerCancel(); worker.shutdown(); wake?.let { if(it.isHeld) it.release() }; super.onDestroy() }
}
