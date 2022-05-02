package com.aliucord.xposed

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import dalvik.system.PathClassLoader
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

class InsteadHook(private val hook: (MethodHookParam) -> Any?) : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        param.result = hook(param)
    }
}

class Main : IXposedHookLoadPackage {
    @TargetApi(Build.VERSION_CODES.O)
    @SuppressLint("PrivateApi", "BlockedPrivateApi")
    override fun handleLoadPackage(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        if (loadPackageParam.packageName != "com.discord") return

        val moduleClassLoader = Main::class.java.classLoader
        val moduleApk = moduleClassLoader.javaClass.declaredFields.first().apply { isAccessible = true }.get(moduleClassLoader) as String

        // disable updater
        val bundleUpdater = loadPackageParam.classLoader.loadClass("com.discord.bundle_updater.BundleUpdater")
        XposedBridge.hookMethod(
            bundleUpdater.declaredMethods.find { m -> m.name == "checkForUpdate" },
            InsteadHook { null }
        )

        // inject our native module
        (loadPackageParam.classLoader as PathClassLoader).addDexPath("/sdcard/AliucordRN/AliucordNative.zip")

        XposedHelpers.findAndHookMethod(
            "com.discord.bridge.DCDReactNativeHost",
            loadPackageParam.classLoader,
            "getPackages",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    @Suppress("UNCHECKED_CAST") val packages = param.result as ArrayList<Any>
                    packages.add(loadPackageParam.classLoader.loadClass("com.aliucord.AliucordNativePackage").getConstructor().newInstance())
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            "com.aliucord.AliucordNativeModule",
            loadPackageParam.classLoader,
            "checkPermissionsInternal",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            }
        )

        // enable cleartext traffic
        XposedHelpers.findAndHookMethod(
            "android.security.net.config.ManifestConfigSource",
            loadPackageParam.classLoader,
            "getConfigSource",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val mApplicationInfoField = XposedHelpers.findField(param.thisObject.javaClass, "mApplicationInfo")
                    val applicationInfo = mApplicationInfoField.get(param.thisObject) as ApplicationInfo
                    applicationInfo.flags = applicationInfo.flags or ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC
                    mApplicationInfoField.set(param.thisObject, applicationInfo)
                }
            }
        )

        // inject libhermes.so
        val soLoaderClass = loadPackageParam.classLoader.loadClass("com.facebook.soloader.SoLoader")

        val addDirectApkSoSource = soLoaderClass.declaredMethods.find {
            it.parameterCount == 2 && it.parameterTypes[0] == Context::class.java && it.parameterTypes[1] == ArrayList::class.java
        }

        val directApkSoSource = loadPackageParam.classLoader.loadClass("com.facebook.soloader.c")

        XposedBridge.hookMethod(
            addDirectApkSoSource,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    @Suppress("UNCHECKED_CAST") val soSources = param!!.args[1] as ArrayList<Any>

                    val source = directApkSoSource.getDeclaredConstructor(File::class.java).apply { isAccessible = true }.newInstance(File(moduleApk))
                    val mDirectApkLdPath = directApkSoSource.declaredFields.single { it.type == String::class.java }.apply { isAccessible = true }
                    mDirectApkLdPath.set(source, "$moduleApk!/lib/" + Build.SUPPORTED_ABIS[0])
                    soSources.add(0, source)
                }
            }
        )
    }
}
