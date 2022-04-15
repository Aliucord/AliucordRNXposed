package com.aliucord.xposed

import android.annotation.SuppressLint
import android.content.res.AssetManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.net.URL

class InsteadHook(private val hook: (MethodHookParam) -> Any?) : XC_MethodHook() {
    override fun beforeHookedMethod(param: MethodHookParam) {
        param.result = hook(param)
    }
}

class Main : IXposedHookLoadPackage {
    @SuppressLint("PrivateApi", "BlockedPrivateApi")
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.discord") return

        val doNothing = InsteadHook { null }

        // disable updater
        val bundleUpdater = param.classLoader.loadClass("com.discord.bundle_updater.BundleUpdater")
        XposedBridge.hookMethod(
            bundleUpdater.declaredMethods.find { m -> m.name == "checkForUpdate" },
            doNothing
        )

        val cache = File(param.appInfo.dataDir, "cache")
        val modulesFile = File(cache, "modulesPatch.js")
        if (!modulesFile.exists()) {
            modulesFile.parentFile?.mkdirs()
            modulesFile.writeText("""
                const oldObjectCreate = this.Object.create;
                const _window = this;
                _window.Object.create = (...args) => {
                    const obj = oldObjectCreate.apply(_window.Object, args);
                    if (args[0] === null) {
                        _window.modules = obj;
                        _window.Object.create = oldObjectCreate;
                    }
                    return obj;
                };
            """.trimIndent())
        }

        val urlFilter = param.classLoader.loadClass("com.android.okhttp.HttpHandler\$CleartextURLFilter")
        val checkURLPermitted = urlFilter.getDeclaredMethod("checkURLPermitted", URL::class.java)
        XposedBridge.hookMethod(checkURLPermitted, doNothing)

        val catalystInstanceImpl = param.classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")
        val loadScriptFromAssets = catalystInstanceImpl.getDeclaredMethod(
            "loadScriptFromAssets",
            AssetManager::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )
        val loadScriptFromFile = catalystInstanceImpl.getDeclaredMethod(
            "jniLoadScriptFromFile",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        XposedBridge.hookMethod(loadScriptFromAssets, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                loadScriptFromFile.invoke(param.thisObject, modulesFile.absolutePath, modulesFile.absolutePath, param.args[2])
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val aliucordFile = File(cache, "Aliucord.js")
                aliucordFile.writeBytes(URL("http://localhost:3000/Aliucord.js").readBytes())
                loadScriptFromFile.invoke(param.thisObject, aliucordFile.absolutePath, aliucordFile.absolutePath, param.args[2])
            }
        })
    }
}
