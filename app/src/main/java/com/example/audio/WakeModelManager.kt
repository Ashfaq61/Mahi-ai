package com.example.audio

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

enum class WakeModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    EXTRACTING,
    READY,
    ERROR
}

data class WakeModelState(
    val status: WakeModelStatus = WakeModelStatus.NOT_DOWNLOADED,
    val progressPercent: Int = 0,
    val modelName: String = "vosk-model-small-en-us-0.15",
    val sizeLabel: String = "~40 MB",
    val message: String = "Offline wake model not downloaded yet."
)

/**
 * Downloads once & extracts the offline Vosk small English acoustic model into filesDir/vosk_models/.
 * Keeping the model downloadable rather than asset-bundled saves ~40 MB APK weight for users who
 * leave Wake Up Detection OFF while still running 100% offline once downloaded.
 */
class WakeModelManager(private val context: Context) {
    companion object {
        const val MODEL_FOLDER = "vosk-model-small-en-us-0.15"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        private const val ROOT_MODELS_DIR = "vosk_models"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val modelsRoot: File
        get() = File(context.applicationContext.filesDir, ROOT_MODELS_DIR).apply { mkdirs() }

    val modelDirectory: File
        get() = File(modelsRoot, MODEL_FOLDER)

    private val _state = MutableStateFlow(inspectCurrentModelState())
    val state: StateFlow<WakeModelState> = _state.asStateFlow()

    private val downloadMutex = Mutex()

    @Volatile
    private var downloadJob: Job? = null

    fun isModelReady(): Boolean {
        val dir = modelDirectory
        return dir.exists() &&
            File(dir, "am").exists() &&
            File(dir, "conf").exists()
    }

    fun inspectCurrentModelState(): WakeModelState {
        return if (isModelReady()) {
            WakeModelState(
                status = WakeModelStatus.READY,
                progressPercent = 100,
                message = "Offline Vosk wake model ready in app storage."
            )
        } else {
            WakeModelState(
                status = WakeModelStatus.NOT_DOWNLOADED,
                progressPercent = 0,
                message = "Download offline \"Hey Mahi\" model (~40 MB) once to enable wake detection."
            )
        }
    }

    fun refreshState() {
        if (_state.value.status != WakeModelStatus.DOWNLOADING &&
            _state.value.status != WakeModelStatus.EXTRACTING
        ) {
            _state.value = inspectCurrentModelState()
        }
    }

    suspend fun downloadAndInstallModel(): Result<File> = withContext(Dispatchers.IO) {
        if (isModelReady()) {
            _state.value = inspectCurrentModelState()
            return@withContext Result.success(modelDirectory)
        }
        if (!downloadMutex.tryLock()) {
            return@withContext Result.failure(IllegalStateException("Offline model download is already in progress."))
        }
        downloadJob = currentCoroutineContext()[Job]
        val zipFile = File(modelsRoot, "$MODEL_FOLDER.zip.tmp")
        try {
            _state.value = WakeModelState(
                status = WakeModelStatus.DOWNLOADING,
                progressPercent = 2,
                message = "Connecting to download offline wake model (~40 MB)…"
            )

            val request = Request.Builder().url(MODEL_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = "Download failed (HTTP ${response.code}). Check internet and tap Retry."
                    _state.value = WakeModelState(WakeModelStatus.ERROR, 0, message = err)
                    return@withContext Result.failure(IllegalStateException(err))
                }
                val body = response.body ?: return@withContext Result.failure(IllegalStateException("Empty response body"))
                val totalBytes = body.contentLength().coerceAtLeast(1L)
                var downloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(zipFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            val pct = ((downloaded * 85) / totalBytes).toInt().coerceIn(2, 85)
                            _state.value = WakeModelState(
                                status = WakeModelStatus.DOWNLOADING,
                                progressPercent = pct,
                                message = "Downloading offline wake model… $pct%"
                            )
                        }
                    }
                }
            }

            _state.value = WakeModelState(
                status = WakeModelStatus.EXTRACTING,
                progressPercent = 88,
                message = "Extracting offline wake model…"
            )

            if (modelDirectory.exists()) {
                modelDirectory.deleteRecursively()
            }

            val canonicalRoot = modelsRoot.canonicalPath
            ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
                val buf = ByteArray(16 * 1024)
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(modelsRoot, entry.name)
                    if (!outFile.canonicalPath.startsWith(canonicalRoot + File.separator)) {
                        throw SecurityException("Unsafe zip path: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            while (true) {
                                val count = zis.read(buf)
                                if (count <= 0) break
                                fos.write(buf, 0, count)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            zipFile.delete()

            if (!isModelReady()) {
                val err = "Model archive extracted incompletely. Tap Retry."
                _state.value = WakeModelState(WakeModelStatus.ERROR, 0, message = err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            _state.value = WakeModelState(
                status = WakeModelStatus.READY,
                progressPercent = 100,
                message = "Offline \"Hey Mahi\" model ready."
            )
            Result.success(modelDirectory)
        } catch (ce: CancellationException) {
            zipFile.delete()
            _state.value = inspectCurrentModelState()
            throw ce
        } catch (e: Exception) {
            zipFile.delete()
            val err = e.localizedMessage ?: "Model download failed. Check connection and retry."
            _state.value = WakeModelState(WakeModelStatus.ERROR, 0, message = err)
            Result.failure(e)
        } finally {
            downloadJob = null
            if (downloadMutex.isLocked) {
                downloadMutex.unlock()
            }
        }
    }

    fun deleteModel() {
        downloadJob?.cancel()
        if (modelDirectory.exists()) {
            modelDirectory.deleteRecursively()
        }
        _state.value = inspectCurrentModelState()
    }
}
