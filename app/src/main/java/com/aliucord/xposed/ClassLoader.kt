package com.aliucord.xposed

import android.annotation.SuppressLint
import dalvik.system.BaseDexClassLoader
import java.io.File

@SuppressLint("DiscouragedPrivateApi")
private val pathListField = BaseDexClassLoader::class.java.getDeclaredField("pathList").apply { isAccessible = true }

val BaseDexClassLoader.pathList: Any
    get() = pathListField[this]!!

fun BaseDexClassLoader.addDexPath(path: String) {
    val addDexPath = pathList.javaClass.getDeclaredMethod(
        "addDexPath",
        String::class.java,
        File::class.java
    ).apply { isAccessible = true }

    addDexPath.invoke(pathList, path, null)
}
