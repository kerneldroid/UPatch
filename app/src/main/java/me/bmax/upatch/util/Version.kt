package me.bmax.upatch.util

import android.system.Os
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import me.bmax.upatch.UPApplication
import me.bmax.upatch.BuildConfig
import me.bmax.upatch.Natives
import me.bmax.upatch.upApp
import org.ini4j.Ini
import java.io.File
import java.io.StringReader

/**
 * version string is like 0.9.0 or 0.9.0-dev
 * version uint is hex number like: 0x000900
 */
object Version {
    private const val TAG = "Version"

    private fun string2UInt(ver: String): UInt {
        val v = ver.trim().split("-")[0]
        val vn = v.split('.')
        val vi = vn[0].toInt().shl(16) + vn[1].toInt().shl(8) + vn[2].toInt()
        return vi.toUInt()
    }

    fun getKpImg(): String {
        val patchDir: ExtendedFile = FileSystemManager.getLocal().getFile(upApp.filesDir.parent, "check")
        patchDir.deleteRecursively()
        patchDir.mkdirs()

        val execs = listOf("libkptools.so", "libbusybox.so")
        val info = upApp.applicationInfo
        val libs = File(info.nativeLibraryDir).listFiles { _, name ->
            execs.contains(name)
        } ?: emptyArray()

        for (lib in libs) {
            val name = lib.name.substring(3, lib.name.length - 3)
            runCatching {
                Os.symlink(lib.path, "$patchDir/$name")
            }.onFailure {
                Log.w(TAG, "Failed to stage $name for kpimg inspection", it)
            }
        }

        for (script in listOf(
            "boot_patch.sh", "boot_unpatch.sh", "boot_extract.sh", "util_functions.sh", "kpimg"
        )) {
            val dest = File(patchDir, script)
            upApp.assets.open(script).writeTo(dest)
        }

        return try {
            createRootShell().use { shell ->
                val result = shellForResult(
                    shell,
                    "cd ${shQuote(patchDir.path)}",
                    "./kptools -l -k kpimg"
                )
                if (!result.isSuccess) {
                    Log.w(TAG, "getKpImg command failed: ${result.err.joinToString("\\n")}")
                    return@use "unknown"
                }

                val ini = Ini(StringReader(result.out.joinToString("\n")))
                val kpimg = ini["kpimg"] ?: return@use "unknown"
                kpimg["compile_time"].orEmpty().ifBlank { "unknown" }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "getKpImg failed", t)
            "unknown"
        } finally {
            patchDir.deleteRecursively()
        }
    }

    fun uInt2String(ver: UInt): String {
        return "%d.%d.%d".format(
            ver.and(0xff0000u).shr(16).toInt(),
            ver.and(0xff00u).shr(8).toInt(),
            ver.and(0xffu).toInt()
        )
    }

    fun installedKPTime(): String {
        val time = Natives.kernelPatchBuildTime()
        return if (time.startsWith("ERROR_")) "unavailable" else time
    }

    fun buildKPVUInt(): UInt {
        val buildVS = BuildConfig.buildKPV
        return string2UInt(buildVS)
    }

    fun buildKPVString(): String {
        return BuildConfig.buildKPV
    }

    /**
     * installed KernelPatch version (installed kpimg)
     */
    fun installedKPVUInt(): UInt {
        return Natives.kernelPatchVersion().toUInt()
    }

    fun installedKPVString(): String {
        return uInt2String(installedKPVUInt())
    }

    private fun installedKPatchVString(): String {
        val resultShell = rootShellForResult(shCommand(UPApplication.APD_PATH, "-V"))
        val result = resultShell.out.joinToString("\n")
        return result.trim().ifEmpty { "0" }
    }

    fun installedKPatchVUInt(): UInt {
        return installedKPatchVString().trim().toUInt(0x10)
    }

    private fun installedApdVString(): String {
        val resultShell = rootShellForResult(shCommand(UPApplication.APD_PATH, "-V"))
        installedApdVString = if (resultShell.isSuccess) {
            val result = resultShell.out.joinToString("\n")
            Log.i(TAG, "[installedApdVString] resultFromShell: $result")
            Regex("\\d+").find(result)?.value ?: "0"
        } else {
            "0"
        }
        return installedApdVString
    }

    fun installedApdVUInt(): Int {
        installedApdVInt = installedApdVString().toInt()
        return installedApdVInt
    }

    fun getManagerVersion(): Pair<String, Long> {
        val packageInfo = upApp.packageManager.getPackageInfo(upApp.packageName, 0)!!
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        return Pair(packageInfo.versionName!!, versionCode)
    }

    var installedApdVInt: Int = 0
    var installedApdVString: String = "0"
}
