package dev.kaos.kaosgfx.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

enum class ShizukuState {
    UNAVAILABLE,
    PERMISSION_REQUIRED,
    READY
}

enum class AngleDriverType(val value: String) {
    DEFAULT("default"),
    ANGLE("angle"),
    NATIVE("native")
}

class PrivilegedClient(private val context: Context) {

    companion object {
        private const val TAG = "KaosGFX-PrivClient"
        private const val SHIZUKU_REQUEST_CODE = 4242
        private const val SERVICE_SUFFIX = "privileged_service"
    }

    private val _shizukuState = MutableStateFlow(ShizukuState.UNAVAILABLE)
    val shizukuState: StateFlow<ShizukuState> = _shizukuState

    private var privilegedService: IPrivilegedService? = null
    private var connectionDeferred: CompletableDeferred<IPrivilegedService>? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, PrivilegedService::class.java.name))
            .daemon(false)
            .processNameSuffix(SERVICE_SUFFIX)
            .debuggable(true)
            .version(1)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "PrivilegedService connected: $name")
            val stub = IPrivilegedService.Stub.asInterface(service)
            privilegedService = stub
            connectionDeferred?.complete(stub)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "PrivilegedService disconnected: $name")
            privilegedService = null
            connectionDeferred = null
        }
    }

    fun checkStatus(): ShizukuState {
        val state = try {
            if (!Shizuku.pingBinder()) {
                ShizukuState.UNAVAILABLE
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.READY
            } else {
                ShizukuState.PERMISSION_REQUIRED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ping Shizuku binder", e)
            ShizukuState.UNAVAILABLE
        }
        _shizukuState.value = state
        return state
    }

    suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        if (checkStatus() == ShizukuState.READY) {
            continuation.resume(true)
            return@suspendCancellableCoroutine
        }

        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == SHIZUKU_REQUEST_CODE) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    val granted = grantResult == PackageManager.PERMISSION_GRANTED
                    _shizukuState.value = if (granted) ShizukuState.READY else ShizukuState.PERMISSION_REQUIRED
                    continuation.resume(granted)
                }
            }
        }

        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
            Shizuku.removeRequestPermissionResultListener(listener)
            continuation.resume(false)
        }
    }

    suspend fun getService(): Result<IPrivilegedService> = withContext(Dispatchers.IO) {
        privilegedService?.let { return@withContext Result.success(it) }

        if (checkStatus() != ShizukuState.READY) {
            return@withContext Result.failure(IllegalStateException("Shizuku is not ready (state: ${_shizukuState.value})"))
        }

        val deferred = CompletableDeferred<IPrivilegedService>()
        connectionDeferred = deferred

        try {
            Shizuku.bindUserService(userServiceArgs, connection)
            val service = deferred.await()
            Result.success(service)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to PrivilegedService", e)
            Result.failure(e)
        }
    }

    suspend fun installApk(
        packageName: String,
        baseApk: File,
        splitApks: List<File> = emptyList(),
        allowReinstallOnSignatureMismatch: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val service = getService().getOrElse { return@withContext Result.failure(it) }
        val splitPaths = splitApks.map { it.absolutePath }
        val errorMsg = service.installPackageDetailed(
            packageName,
            baseApk.absolutePath,
            splitPaths,
            allowReinstallOnSignatureMismatch
        )
        if (errorMsg.isNullOrEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(RuntimeException(errorMsg))
        }
    }

    suspend fun forceStop(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val service = getService().getOrElse { return@withContext Result.failure(it) }
        try {
            service.forceStop(packageName)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setAngleDriver(packageName: String, driverType: AngleDriverType): Result<Unit> = withContext(Dispatchers.IO) {
        val service = getService().getOrElse { return@withContext Result.failure(it) }
        try {
            // angle_gl_driver_selection_pkgs and angle_gl_driver_selection_values are comma-separated
            val currentPkgs = service.getGlobalSetting("angle_gl_driver_selection_pkgs")
                .split(",")
                .filter { it.isNotBlank() }
                .toMutableList()
            val currentVals = service.getGlobalSetting("angle_gl_driver_selection_values")
                .split(",")
                .filter { it.isNotBlank() }
                .toMutableList()

            val index = currentPkgs.indexOf(packageName)
            if (index >= 0) {
                if (driverType == AngleDriverType.DEFAULT) {
                    currentPkgs.removeAt(index)
                    if (index < currentVals.size) currentVals.removeAt(index)
                } else {
                    if (index < currentVals.size) {
                        currentVals[index] = driverType.value
                    } else {
                        currentVals.add(driverType.value)
                    }
                }
            } else if (driverType != AngleDriverType.DEFAULT) {
                currentPkgs.add(packageName)
                currentVals.add(driverType.value)
            }

            service.setGlobalSetting("angle_gl_driver_selection_pkgs", currentPkgs.joinToString(","))
            service.setGlobalSetting("angle_gl_driver_selection_values", currentVals.joinToString(","))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun stageDriver(packageName: String, driverZip: File?, configJson: String): Result<Unit> = withContext(Dispatchers.IO) {
        val service = getService().getOrElse { return@withContext Result.failure(it) }
        try {
            val ok = service.stageDriverBundle(packageName, driverZip?.absolutePath, configJson)
            if (ok) Result.success(Unit) else Result.failure(RuntimeException("stageDriverBundle failed in service"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDriverConfig(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        val service = getService().getOrElse { return@withContext Result.failure(it) }
        try {
            val config = service.getDriverConfig(packageName)
            Result.success(config ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPipelineLogs(packageName: String, maxLines: Int = 150): Result<String> = withContext(Dispatchers.IO) {
        val service = getService().getOrElse { return@withContext Result.failure(it) }
        try {
            val logs = service.getPipelineLogs(packageName, maxLines)
            Result.success(logs ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPackageProcessId(packageName: String): Result<Int> = withContext(Dispatchers.IO) {
        val service = getService().getOrElse { return@withContext Result.failure(it) }
        try {
            val pid = service.getPackageProcessId(packageName)
            Result.success(pid)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun unbind() {
        try {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (ignored: Exception) {}
        privilegedService = null
    }
}
