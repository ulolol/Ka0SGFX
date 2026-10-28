package dev.kaos.kaosgfx.bootstrap;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class TurnipAppComponentFactory extends AppComponentFactory {
    private static final String TAG = "KaosGFX-Bootstrap";
    private static final String META_ORIGINAL_FACTORY = "dev.kaos.kaosgfx.ORIGINAL_APP_COMPONENT_FACTORY";
    private static final String CONFIG_FILE_NAME = "kaosgfx_driver_config.json";

    private static AppComponentFactory sOriginalFactory = null;
    private static boolean sInitialized = false;
    private static boolean sPipelineActive = false;
    private static String sActiveDriverName = "Turnip Freedreno";
    private static boolean sToastShown = false;

    public static native boolean nativeInit(
        String pkgDataDir,
        String pkgCodeDir,
        String hookLibDir,
        String customDriverDir,
        String customDriverName
    );

    public static native void nativeClearCrashGuard(String pkgDataDir);
    public static native void nativeRefreshHooks();

    private static synchronized void initKaosGFX(ApplicationInfo aInfo) {
        if (sInitialized) return;
        sInitialized = true;

        if (aInfo == null) {
            Log.e(TAG, "ApplicationInfo is null, cannot initialize KaosGFX");
            return;
        }

        Log.i(TAG, "TurnipAppComponentFactory early init for: " + aInfo.packageName);
        Log.i(TAG, "  dataDir: " + aInfo.dataDir);
        Log.i(TAG, "  nativeLibraryDir: " + aInfo.nativeLibraryDir);
        Log.i(TAG, "  sourceDir: " + aInfo.sourceDir);

        // 1. Resolve original factory if chained
        resolveOriginalFactory(aInfo);

        // 2. Resolve hook libraries directory (handling extractNativeLibs="false")
        String hookLibDir = resolveHookLibDir(aInfo);
        Log.i(TAG, "  hookLibDir: " + hookLibDir);

        // 3. Load bridge and dependencies
        loadNativeLibraries(hookLibDir);

        try {
            com.bytedance.android.bytehook.ByteHook.init();
            Log.i(TAG, "ByteHook.init() succeeded in Java runtime");
        } catch (Throwable t) {
            Log.w(TAG, "ByteHook.init() in Java warning: " + t.getMessage());
        }

        // 4. Resolve driver directory & filename from config or defaults
        DriverConfig config = readDriverConfig(aInfo);
        File driverDir = new File(config.driverDir);

        // Check if external or internal staged driver.zip exists, and unpack it
        unpackStagedDriverIfNeeded(aInfo, driverDir);

        File driverFile = new File(driverDir, config.driverName);
        if (!driverFile.exists()) {
            // Check meta.json in driverDir for true libraryName
            File metaFile = new File(driverDir, "meta.json");
            if (metaFile.exists()) {
                String metaContent = readTextFile(metaFile);
                String metaLib = extractJsonField(metaContent, "libraryName");
                if (metaLib != null && !metaLib.isEmpty()) {
                    File metaDriver = new File(driverDir, metaLib);
                    if (metaDriver.exists()) {
                        driverFile = metaDriver;
                        config.driverName = metaLib;
                        Log.i(TAG, "Resolved driver library from meta.json: " + metaLib);
                    }
                }
            }
        }

        if (!driverFile.exists() && driverDir.exists()) {
            // Autodetect any valid Vulkan library in driverDir
            File[] soFiles = driverDir.listFiles((dir, name) -> name.endsWith(".so"));
            if (soFiles != null && soFiles.length > 0) {
                File chosen = null;
                for (File f : soFiles) {
                    String n = f.getName().toLowerCase();
                    if (n.startsWith("vulkan") || n.contains("freedreno") || n.contains("adreno") || n.contains("turnip") || n.contains("gsl")) {
                        chosen = f;
                        break;
                    }
                }
                if (chosen == null) chosen = soFiles[0];
                driverFile = chosen;
                config.driverName = chosen.getName();
                Log.i(TAG, "Autodetected driver library from driverDir: " + config.driverName);
            }
        }

        if (!driverFile.exists()) {
            Log.w(TAG, "Custom driver not found at " + driverFile.getAbsolutePath() + ", skipping hook injection.");
            return;
        }

        sActiveDriverName = config.driverName;

        // 5. Invoke native bridge
        try {
            boolean success = nativeInit(
                aInfo.dataDir,
                aInfo.sourceDir,
                hookLibDir,
                driverDir.getAbsolutePath(),
                config.driverName
            );
            Log.i(TAG, "nativeInit completed, status = " + success);

            if (success) {
                sPipelineActive = true;
                // Clear crash guard after 5 seconds of healthy execution
                new Thread(() -> {
                    try {
                        Thread.sleep(5000);
                        nativeClearCrashGuard(aInfo.dataDir);
                    } catch (Throwable ignored) {}
                }).start();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Exception during nativeInit: " + t.getMessage(), t);
        }
    }

    private static void unpackStagedDriverIfNeeded(ApplicationInfo aInfo, File targetDir) {
        File[] candidateZips = new File[]{
            new File("/data/local/tmp/kaosgfx/" + aInfo.packageName + "/driver.zip"),
            new File("/data/local/tmp/kaosgfx/driver.zip"),
            new File("/sdcard/Android/data/" + aInfo.packageName + "/files/kaosgfx/driver.zip"),
            new File(aInfo.dataDir, "files/kaosgfx/driver.zip"),
            new File(aInfo.dataDir, "files/driver.zip")
        };

        for (File zipFile : candidateZips) {
            Log.i(TAG, "Evaluating staged driver zip candidate: " + zipFile.getAbsolutePath() + " (exists=" + zipFile.exists() + ", canRead=" + zipFile.canRead() + ")");
            if (zipFile.exists() && zipFile.canRead()) {
                if (!targetDir.exists()) {
                    targetDir.mkdirs();
                }
                File stampFile = new File(targetDir, ".unpacked_stamp");
                if (stampFile.exists() && stampFile.lastModified() >= zipFile.lastModified()) {
                    Log.i(TAG, "Driver in " + targetDir.getAbsolutePath() + " is already up-to-date with " + zipFile.getAbsolutePath());
                    return; // Already up-to-date
                }

                Log.i(TAG, "Unpacking driver zip: " + zipFile.getAbsolutePath() + " into " + targetDir.getAbsolutePath());
                try (ZipFile zip = new ZipFile(zipFile)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    byte[] buf = new byte[8192];
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        String name = entry.getName();
                        String simpleName = name.substring(name.lastIndexOf('/') + 1);

                        // Extract both flat in targetDir and under relative path if any
                        File outFile = new File(targetDir, simpleName);
                        try (InputStream in = zip.getInputStream(entry);
                             FileOutputStream out = new FileOutputStream(outFile)) {
                            int r;
                            while ((r = in.read(buf)) != -1) {
                                out.write(buf, 0, r);
                            }
                        }
                        outFile.setReadable(true, false);
                        if (simpleName.endsWith(".so")) {
                            outFile.setExecutable(true, false);
                        }
                    }
                    try (FileOutputStream fos = new FileOutputStream(stampFile)) {
                        fos.write("ok".getBytes());
                    }
                    Log.i(TAG, "Driver package unpacked successfully into " + targetDir.getAbsolutePath());
                    return;
                } catch (Throwable t) {
                    Log.e(TAG, "Failed unpacking driver zip " + zipFile.getAbsolutePath() + ": " + t.getMessage(), t);
                }
            }
        }
    }

    private static void resolveOriginalFactory(ApplicationInfo aInfo) {
        if (aInfo.metaData != null && aInfo.metaData.containsKey(META_ORIGINAL_FACTORY)) {
            String originalClassName = aInfo.metaData.getString(META_ORIGINAL_FACTORY);
            if (originalClassName != null && !originalClassName.isEmpty()) {
                try {
                    Class<?> clazz = Class.forName(originalClassName);
                    Constructor<?> ctor = clazz.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    sOriginalFactory = (AppComponentFactory) ctor.newInstance();
                    Log.i(TAG, "Chained to original AppComponentFactory: " + originalClassName);
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to instantiate original factory " + originalClassName + ": " + t.getMessage(), t);
                }
            }
        }
    }

    private static String resolveHookLibDir(ApplicationInfo aInfo) {
        if (aInfo.nativeLibraryDir != null) {
            File directHook = new File(aInfo.nativeLibraryDir, "libhook_impl.so");
            if (directHook.exists()) {
                return aInfo.nativeLibraryDir;
            }
        }

        File targetDir = new File(aInfo.dataDir, "files/kaos_hook_libs");
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        extractHookLibsFromApk(aInfo.sourceDir, targetDir);
        if (aInfo.splitSourceDirs != null) {
            for (String split : aInfo.splitSourceDirs) {
                extractHookLibsFromApk(split, targetDir);
            }
        }

        return targetDir.getAbsolutePath();
    }

    private static void extractHookLibsFromApk(String apkPath, File targetDir) {
        if (apkPath == null) return;
        File apkFile = new File(apkPath);
        if (!apkFile.exists()) return;

        try (ZipFile zip = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("lib/arm64-v8a/lib") && name.endsWith(".so")) {
                    String simpleName = name.substring(name.lastIndexOf('/') + 1);
                    File outFile = new File(targetDir, simpleName);
                    if (!outFile.exists() || outFile.length() != entry.getSize()) {
                        try (InputStream in = zip.getInputStream(entry);
                             FileOutputStream out = new FileOutputStream(outFile)) {
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                            }
                        }
                        outFile.setReadable(true, false);
                        outFile.setExecutable(true, false);
                        Log.i(TAG, "Extracted hook library: " + simpleName + " (" + outFile.length() + " bytes)");
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to inspect APK " + apkPath + " for hook libraries: " + t.getMessage());
        }
    }

    private static void loadNativeLibraries(String hookLibDir) {
        String[] libs = new String[]{
            "libshadowhook.so",
            "libbytehook.so",
            "libkaosgfx_bridge.so"
        };
        for (String lib : libs) {
            File directFile = new File(hookLibDir, lib);
            if (directFile.exists()) {
                try {
                    System.load(directFile.getAbsolutePath());
                    Log.i(TAG, "Loaded via absolute path: " + directFile.getAbsolutePath());
                    continue;
                } catch (Throwable t) {
                    Log.w(TAG, "System.load failed for " + directFile + ": " + t.getMessage());
                }
            }
            String libName = lib.substring(3, lib.length() - 3);
            try {
                System.loadLibrary(libName);
                Log.i(TAG, "Loaded via System.loadLibrary: " + libName);
            } catch (Throwable t) {
                Log.e(TAG, "Failed to load library " + libName + ": " + t.getMessage());
            }
        }
    }

    private static class DriverConfig {
        String driverDir;
        String driverName;

        DriverConfig(String driverDir, String driverName) {
            this.driverDir = driverDir;
            this.driverName = driverName;
        }
    }

    private static DriverConfig readDriverConfig(ApplicationInfo aInfo) {
        File[] candidateConfigFiles = new File[]{
            new File("/data/local/tmp/kaosgfx/" + aInfo.packageName + "/" + CONFIG_FILE_NAME),
            new File("/data/local/tmp/kaosgfx/" + CONFIG_FILE_NAME),
            new File("/sdcard/Android/data/" + aInfo.packageName + "/files/kaosgfx/" + CONFIG_FILE_NAME),
            new File(aInfo.dataDir, "files/kaosgfx/" + CONFIG_FILE_NAME),
            new File(aInfo.dataDir, "files/" + CONFIG_FILE_NAME)
        };

        for (File configFile : candidateConfigFiles) {
            Log.i(TAG, "Evaluating staged config candidate: " + configFile.getAbsolutePath() + " (exists=" + configFile.exists() + ", canRead=" + configFile.canRead() + ")");
            if (configFile.exists() && configFile.canRead()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    String content = sb.toString();
                    String driverDir = extractJsonField(content, "driverDir");
                    String driverName = extractJsonField(content, "driverName");
                    String driverId = extractJsonField(content, "driverId");

                    if (driverName != null && !driverName.isEmpty()) {
                        if (driverId != null && !driverId.isEmpty()) {
                            driverDir = new File(aInfo.dataDir, "files/turnip/" + driverId).getAbsolutePath();
                        } else if (driverDir == null || driverDir.isEmpty()) {
                            driverDir = new File(aInfo.dataDir, "files/turnip").getAbsolutePath();
                        }
                        Log.i(TAG, "Loaded driver config from " + configFile.getAbsolutePath() + ": dir=" + driverDir + ", name=" + driverName + ", id=" + driverId);
                        return new DriverConfig(driverDir, driverName);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Failed reading driver config from " + configFile.getAbsolutePath() + ": " + t.getMessage(), t);
                }
            }
        }

        // Fallback default
        return new DriverConfig(
            new File(aInfo.dataDir, "files/turnip").getAbsolutePath(),
            "libvulkan_freedreno.so"
        );
    }

    private static String readTextFile(File file) {
        if (!file.exists() || !file.canRead()) return "";
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon == -1) return null;
        int quoteStart = json.indexOf('\"', colon + 1);
        if (quoteStart == -1) return null;
        int quoteEnd = json.indexOf('\"', quoteStart + 1);
        if (quoteEnd == -1) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    // --- AppComponentFactory Lifecycle Overrides ---

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo aInfo) {
        initKaosGFX(aInfo);
        if (sOriginalFactory != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return sOriginalFactory.instantiateClassLoader(cl, aInfo);
        }
        return super.instantiateClassLoader(cl, aInfo);
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Application app = (sOriginalFactory != null)
                ? sOriginalFactory.instantiateApplication(cl, className)
                : super.instantiateApplication(cl, className);
        try {
            nativeRefreshHooks();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    try {
                        nativeRefreshHooks();
                    } catch (Throwable ignored) {}
                }

                @Override
                public void onActivityStarted(Activity activity) {
                    try {
                        nativeRefreshHooks();
                    } catch (Throwable ignored) {}

                    if (sPipelineActive && !sToastShown) {
                        sToastShown = true;
                        try {
                            activity.runOnUiThread(() -> {
                                try {
                                    android.widget.Toast.makeText(
                                        activity.getApplicationContext(),
                                        "🎮 KaosGFX: Turnip Pipeline Active (" + sActiveDriverName + ")",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show();
                                } catch (Throwable ignored) {}
                            });
                        } catch (Throwable ignored) {}
                    }
                }

                @Override public void onActivityResumed(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
            Log.i(TAG, "Registered ActivityLifecycleCallbacks for continuous hook enforcement");
        } catch (Throwable t) {
            Log.w(TAG, "Hook setup in instantiateApplication: " + t.getMessage());
        }
        return app;
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Activity act = (sOriginalFactory != null)
                ? sOriginalFactory.instantiateActivity(cl, className, intent)
                : super.instantiateActivity(cl, className, intent);
        try {
            nativeRefreshHooks();
        } catch (Throwable t) {
            Log.w(TAG, "nativeRefreshHooks in instantiateActivity failed: " + t.getMessage());
        }
        return act;
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (sOriginalFactory != null) {
            return sOriginalFactory.instantiateReceiver(cl, className, intent);
        }
        return super.instantiateReceiver(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (sOriginalFactory != null) {
            return sOriginalFactory.instantiateService(cl, className, intent);
        }
        return super.instantiateService(cl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (sOriginalFactory != null) {
            return sOriginalFactory.instantiateProvider(cl, className);
        }
        return super.instantiateProvider(cl, className);
    }
}
