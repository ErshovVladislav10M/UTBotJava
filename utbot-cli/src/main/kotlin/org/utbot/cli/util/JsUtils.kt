package org.utbot.cli.util

import java.io.File

internal object JsUtils {
    fun makeAbsolutePath(path: String): String {
        return when {
            File(path).isAbsolute -> path
            else -> System.getProperty("user.dir") + File.separator + path
        }
    }
}