package dev.kaos.kaosgfx.privileged;

interface IPrivilegedService {
    /**
     * Installs an APK (and optional splits) using PackageInstaller session
     * with --bypass-low-target-sdk-block and -r flags.
     * Returns 0 on success, or non-zero error code.
     */
    int installPackage(String baseApkPath, in List<String> splitApkPaths);

    /**
     * Installs an APK with full error reporting and automatic signature-mismatch
     * reinstallation (data/obb backup -> uninstall -> install -> restore).
     * Returns empty string on success, or detailed error message on failure.
     */
    String installPackageDetailed(String packageName, String baseApkPath, in List<String> splitApkPaths, boolean allowReinstallOnSignatureMismatch);

    /**
     * Force-stops a package.
     */
    void forceStop(String packageName);

    /**
     * Sets a Settings.Global value (e.g. angle_gl_driver_selection_pkgs).
     */
    void setGlobalSetting(String key, String value);

    /**
     * Reads a Settings.Global value.
     */
    String getGlobalSetting(String key);

    /**
     * Stages a custom driver zip and configuration into the app's external data directory
     * (/sdcard/Android/data/<packageName>/files/kaosgfx/).
     */
    boolean stageDriverBundle(String packageName, String driverZipPath, String configJson);

    /**
     * Destroys the privileged service process.
     */
    void destroy();
}
