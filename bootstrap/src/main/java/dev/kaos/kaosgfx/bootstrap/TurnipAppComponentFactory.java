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
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class TurnipAppComponentFactory extends AppComponentFactory {
    private static final String TAG = "KaosGFX-Bootstrap";
    private static final String META_ORIGINAL_FACTORY = "dev.kaos.kaosgfx.ORIGINAL_APP_COMPONENT_FACTORY";
    private static final String CONFIG_FILE_NAME = "kaosgfx_driver_config.json";

    private static AppComponentFactory sOriginalFactory = null;
    private static boolean sInitialized = false;

    public static native boolean nativeInit(
        String pkgDataDir,
        String pkgCodeDir,
        String hookLibDir,
        String customDriverDir,
        String customDriverName
    );

    public static native void nativeClearCrashGuard(String pkgDataDir);

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

        // 4. Resolve driver directory & filename from config or defaults
        DriverConfig config = readDriverConfig(aInfo.dataDir);
        File driverDir = new File(config.driverDir);
        File driverFile = new File(driverDir, config.driverName);

        if (!driverFile.exists()) {
            Log.w(TAG, "Custom driver not found at " + driverFile.getAbsolutePath() + ", skipping hook injection.");
            return;
        }

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
        // If nativeLibraryDir exists and contains libhook_impl.so, use it directly
        if (aInfo.nativeLibraryDir != null) {
            File directHook = new File(aInfo.nativeLibraryDir, "libhook_impl.so");
            if (directHook.exists()) {
                return aInfo.nativeLibraryDir;
            }
        }

        // Otherwise extract from APK into files/kaos_hook_libs
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

    private static DriverConfig readDriverConfig(String pkgDataDir) {
        File configFile = new File(pkgDataDir, "files/" + CONFIG_FILE_NAME);
        if (configFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                String content = sb.toString();
                // Simple parser without heavy json dependency: look for driverDir and driverName
                String driverDir = extractJsonField(content, "driverDir");
                String driverName = extractJsonField(content, "driverName");
                if (driverDir != null && driverName != null) {
                    return new DriverConfig(driverDir, driverName);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Failed to read driver config: " + t.getMessage());
            }
        }

        // Fallback default
        return new DriverConfig(
            new File(pkgDataDir, "files/turnip").getAbsolutePath(),
            "libvulkan_freedreno.so"
        );
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
        if (sOriginalFactory != null) {
            return sOriginalFactory.instantiateApplication(cl, className);
        }
        return super.instantiateApplication(cl, className);
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (sOriginalFactory != null) {
            return sOriginalFactory.instantiateActivity(cl, className, intent);
        }
        return super.instantiateActivity(cl, className, intent);
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
