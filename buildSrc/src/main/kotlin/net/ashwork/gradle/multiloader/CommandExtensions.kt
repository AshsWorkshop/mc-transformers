package net.ashwork.gradle.multiloader

import java.io.File
import java.util.concurrent.TimeUnit

// From: https://discuss.kotlinlang.org/t/use-git-hash-as-version-number-in-build-gradle-kts/19818/8
fun String.runCommand(workingDir: File = File("."), timeoutAmount: Long = 60, timeoutUnit: TimeUnit = TimeUnit.SECONDS, orElse: String? = null): String =
    ProcessBuilder(split("\\s(?=(?:[^'\"`]*(['\"`])[^'\"`]*\\1)*[^'\"`]*$)".toRegex()))
        .directory(workingDir)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
        .apply { waitFor(timeoutAmount, timeoutUnit) }
        .run {
            val error = errorStream.bufferedReader().readText().trim()
            if (error.isNotEmpty()) {
                if (orElse != null) {
                    return orElse;
                } else {
                    throw Exception(error)
                }
            }
            inputStream.bufferedReader().readText().trim()
        }