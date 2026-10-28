package dev.kaos.kaosgfx

import android.app.Application
import dev.kaos.kaosgfx.drivers.DriverCatalogRepository
import dev.kaos.kaosgfx.drivers.DriverDownloader
import dev.kaos.kaosgfx.drivers.DriverManager
import dev.kaos.kaosgfx.patcher.ApkPatcherEngine
import dev.kaos.kaosgfx.privileged.PrivilegedClient

class KaosGFXApplication : Application() {

    lateinit var catalogRepository: DriverCatalogRepository
        private set
    lateinit var driverDownloader: DriverDownloader
        private set
    lateinit var driverManager: DriverManager
        private set
    lateinit var privilegedClient: PrivilegedClient
        private set
    lateinit var patcherEngine: ApkPatcherEngine
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        catalogRepository = DriverCatalogRepository(this)
        driverDownloader = DriverDownloader(this)
        driverManager = DriverManager(this)
        privilegedClient = PrivilegedClient(this)
        patcherEngine = ApkPatcherEngine()
    }

    companion object {
        lateinit var instance: KaosGFXApplication
            private set
    }
}
