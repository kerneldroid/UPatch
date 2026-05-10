package me.bmax.apatch.ui.viewmodel

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.bmax.apatch.APApplication
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.R
import me.bmax.apatch.apApp
import me.bmax.apatch.util.Version
import me.bmax.apatch.util.copyAndClose
import me.bmax.apatch.util.copyAndCloseOut
import me.bmax.apatch.util.createRootShell
import me.bmax.apatch.util.inputStream
import me.bmax.apatch.util.shellForResult
import me.bmax.apatch.util.writeTo
import me.bmax.apatch.util.shCommand
import me.bmax.apatch.util.shQuote
import org.ini4j.Ini
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringReader
import java.security.MessageDigest
import java.util.Properties

private const val TAG = "PatchViewModel"

class PatchesViewModel : ViewModel() {

    enum class PatchMode(val sId: Int) {
        PATCH_ONLY(R.string.patch_mode_bootimg_patch),
        PATCH_AND_INSTALL(R.string.patch_mode_patch_and_install),
        INSTALL_TO_NEXT_SLOT(R.string.patch_mode_install_to_next_slot),
        UNPATCH(R.string.patch_mode_uninstall_patch)
    }

    var bootSlot by mutableStateOf("")
    var bootDev by mutableStateOf("")
    var kimgInfo by mutableStateOf(KPModel.KImgInfo("", false))
    var kpimgInfo by mutableStateOf(KPModel.KPImgInfo("", "", "", "", ""))
    var superkey by mutableStateOf(APApplication.superKey)
    var existedExtras = mutableStateListOf<KPModel.IExtraInfo>()
    var newExtras = mutableStateListOf<KPModel.IExtraInfo>()
    var newExtrasFileName = mutableListOf<String>()

    var running by mutableStateOf(false)
    var patching by mutableStateOf(false)
    var patchdone by mutableStateOf(false)
    var needReboot by mutableStateOf(false)

    var error by mutableStateOf("")
    var patchLog by mutableStateOf("")

    private val patchDir: ExtendedFile = FileSystemManager.getLocal().getFile(apApp.filesDir.parent, "patch")
    private val patchStateFile = File(apApp.noBackupFilesDir, "patch_state.properties")
    private var srcBoot: ExtendedFile = patchDir.getChildFile("boot.img")
    private var shell: Shell = createRootShell()
    private var prepared: Boolean = false

    private fun prepare() {
        patchDir.deleteRecursively()
        patchDir.mkdirs()
        val execs = listOf(
            "libkptools.so", "libbusybox.so", "libkpatch.so", "libbootctl.so"
        )
        error = ""

        val info = apApp.applicationInfo
        val libs = File(info.nativeLibraryDir).listFiles { _, name ->
            execs.contains(name)
        } ?: emptyArray()

        for (lib in libs) {
            val name = lib.name.substring(3, lib.name.length - 3)
            Os.symlink(lib.path, "$patchDir/$name")
        }

        // Extract scripts
        for (script in listOf(
            "boot_patch.sh", "boot_unpatch.sh", "boot_extract.sh", "util_functions.sh", "kpimg"
        )) {
            val dest = File(patchDir, script)
            apApp.assets.open(script).writeTo(dest)
        }

    }

    private fun persistPatchState() {
        val props = Properties().apply {
            setProperty("bootDev", bootDev)
            setProperty("bootSlot", bootSlot)
            setProperty("srcBoot", srcBoot.path)
            setProperty("superkeySha256", MessageDigest.getInstance("SHA-256").digest(superkey.toByteArray()).joinToString("") { "%02x".format(it) })
        }
        patchStateFile.outputStream().use { props.store(it, "UPatch patch state") }
    }

    private fun validateUnpatchState(): String? {
        if (!patchStateFile.exists()) {
            return "Missing saved patch state; patch again before unpatching."
        }
        val props = Properties().apply { patchStateFile.inputStream().use { load(it) } }
        val savedBootDev = props.getProperty("bootDev", "")
        if (savedBootDev.isBlank() || savedBootDev != bootDev) {
            return "Saved patch state does not match current boot device."
        }
        val oriCheck = shell.newJob().add("[ -f ${shQuote("/data/adb/ap/ori.img")} ]").exec()
        if (!oriCheck.isSuccess) {
            return "Original boot backup not found at /data/adb/ap/ori.img."
        }
        return null
    }

    private fun secureSuperKeyValidation(candidate: String): Boolean {
        return candidate.length in 8..63 &&
            candidate.any { it.isDigit() } &&
            candidate.any { it.isLetter() } &&
            candidate.none { it.isWhitespace() || it.isISOControl() }
    }

    private fun parseKpimg() {
        val result = shellForResult(
            shell, "cd ${shQuote(patchDir.path)}", "./kptools -l -k kpimg"
        )

        if (result.isSuccess) {
            val ini = Ini(StringReader(result.out.joinToString("\n")))
            val kpimg = ini["kpimg"]
            if (kpimg != null) {
                kpimgInfo = KPModel.KPImgInfo(
                    kpimg["version"].toString(),
                    kpimg["compile_time"].toString(),
                    kpimg["config"].toString(),
                    APApplication.superKey,     // current key
                    kpimg["root_superkey"].toString(),   // empty
                )
            } else {
                error += "parse kpimg error\n"
            }
        } else {
            error = result.err.joinToString("\n")
        }
    }

    private fun parseBootimg(bootimg: String) {
        val result = shellForResult(
            shell,
            "cd ${shQuote(patchDir.path)}",
            "./kptools unpacknolog ${shQuote(bootimg)}",
            "./kptools -l -i kernel",
        )
        if (result.isSuccess) {
            val ini = Ini(StringReader(result.out.joinToString("\n")))
            Log.d(TAG, "kernel image info: $ini")

            val kernel = ini["kernel"]
            if (kernel == null) {
                error += "empty kernel section"
                Log.d(TAG, error)
                return
            }
            kimgInfo = KPModel.KImgInfo(kernel["banner"].toString(), kernel["patched"].toBoolean())
            if (kimgInfo.patched) {
                val superkey = ini["kpimg"]?.getOrDefault("superkey", "") ?: ""
                kpimgInfo.superKey = superkey
                if (secureSuperKeyValidation(superkey)) {
                    this.superkey = superkey
                }
                var kpmNum = kernel["extra_num"]?.toInt()
                if (kpmNum == null) {
                    val extras = ini["extras"]
                    kpmNum = extras?.get("num")?.toInt()
                }
                if (kpmNum != null && kpmNum > 0) {
                    for (i in 0..<kpmNum) {
                        val extra = ini["extra $i"]
                        if (extra == null) {
                            error += "empty extra section"
                            break
                        }
                        val type = KPModel.ExtraType.valueOf(extra["type"]!!.uppercase())
                        val name = extra["name"].toString()
                        val args = extra["args"].toString()
                        var event = extra["event"].toString()
                        if (event.isEmpty()) {
                            event = KPModel.TriggerEvent.PRE_KERNEL_INIT.event
                        }
                        if (type == KPModel.ExtraType.KPM) {
                            val kpmInfo = KPModel.KPMInfo(
                                type, name, event, args,
                                extra["version"].toString(),
                                extra["license"].toString(),
                                extra["author"].toString(),
                                extra["description"].toString(),
                            )
                            existedExtras.add(kpmInfo)
                        }
                    }

                }
            }
        } else {
            error += result.err.joinToString("\n")
        }
    }

    val checkSuperKeyValidation: (superKey: String) -> Boolean = { candidate -> secureSuperKeyValidation(candidate) }

    fun copyAndParseBootimg(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            if (running) return@launch
            running = true
            error = ""
            try {
                uri.inputStream().buffered().use { src ->
                    srcBoot.also {
                        src.copyAndCloseOut(it.newOutputStream())
                    }
                }
                parseBootimg(srcBoot.path)
            } catch (e: IOException) {
                error = e.message ?: "Failed to copy boot image"
                Log.e(TAG, "copy boot image error", e)
            } finally {
                running = false
            }
        }
    }

    private fun extractAndParseBootimg(mode: PatchMode) {
        var cmdBuilder = "./boot_extract.sh"

        if (mode == PatchMode.INSTALL_TO_NEXT_SLOT) {
            cmdBuilder += " true"
        }

        val result = shellForResult(
            shell,
            "export ASH_STANDALONE=1",
            "cd ${shQuote(patchDir.path)}",
            "./busybox sh $cmdBuilder",
        )

        if (result.isSuccess) {
            val slot = result.out.firstOrNull { it.startsWith("SLOT=") }
                ?.removePrefix("SLOT=")
                ?.trim()
                .orEmpty()
            val bootImage = result.out.firstOrNull { it.startsWith("BOOTIMAGE=") }
                ?.removePrefix("BOOTIMAGE=")
                ?.trim()

            if (bootImage.isNullOrEmpty()) {
                error = "boot_extract.sh did not return BOOTIMAGE"
                return
            }

            bootSlot = slot
            bootDev = bootImage
            Log.i(TAG, "current slot: $bootSlot")
            Log.i(TAG, "current bootimg: $bootDev")
            srcBoot = FileSystemManager.getLocal().getFile(bootDev)
            parseBootimg(bootDev)
            persistPatchState()
        } else {
            error = result.err.joinToString("\n")
        }
    }

    fun prepare(mode: PatchMode) {
        viewModelScope.launch(Dispatchers.IO) {
            if (prepared) return@launch

            running = true
            error = ""
            try {
                prepare()
                if (mode != PatchMode.UNPATCH) {
                    parseKpimg()
                }
                if (mode == PatchMode.PATCH_AND_INSTALL || mode == PatchMode.UNPATCH || mode == PatchMode.INSTALL_TO_NEXT_SLOT) {
                    extractAndParseBootimg(mode)
                }
                prepared = error.isEmpty()
            } catch (t: Throwable) {
                prepared = false
                error = t.message ?: "Failed to prepare patch environment"
                Log.e(TAG, "prepare failed", t)
            } finally {
                running = false
            }
        }
    }

    fun embedKPM(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            if (running) return@launch
            running = true
            error = ""

            val rand = (1..4).map { ('a'..'z').random() }.joinToString("")
            val kpmFileName = "${rand}.kpm"
            val kpmFile: ExtendedFile = patchDir.getChildFile(kpmFileName)

            Log.i(TAG, "copy kpm to: " + kpmFile.path)
            try {
                uri.inputStream().buffered().use { src ->
                    kpmFile.also {
                        src.copyAndCloseOut(it.newOutputStream())
                    }
                }

                val result = shellForResult(
                    shell, "cd ${shQuote(patchDir.path)}", "./kptools -l -M ${shQuote(kpmFile.path)}"
                )

                if (result.isSuccess) {
                    val ini = Ini(StringReader(result.out.joinToString("\n")))
                    val kpm = ini["kpm"]
                    if (kpm != null) {
                        val kpmInfo = KPModel.KPMInfo(
                            KPModel.ExtraType.KPM,
                            kpm["name"].toString(),
                            KPModel.TriggerEvent.PRE_KERNEL_INIT.event,
                            "",
                            kpm["version"].toString(),
                            kpm["license"].toString(),
                            kpm["author"].toString(),
                            kpm["description"].toString(),
                        )
                        newExtras.add(kpmInfo)
                        newExtrasFileName.add(kpmFileName)
                    } else {
                        error = "Missing KPM metadata"
                        kpmFile.delete()
                    }
                } else {
                    error = result.err.joinToString("\n").ifBlank { "Invalid KPM" }
                    kpmFile.delete()
                }
            } catch (e: IOException) {
                error = e.message ?: "Failed to copy KPM"
                kpmFile.delete()
                Log.e(TAG, "Copy kpm error", e)
            } finally {
                running = false
            }
        }
    }

    fun doUnpatch() {
        viewModelScope.launch(Dispatchers.IO) {
            patching = true
            patchdone = false
            needReboot = false
            patchLog = ""
            error = ""
            Log.i(TAG, "starting unpatching...")

            val logs = object : CallbackList<String>() {
                override fun onAddElement(e: String?) {
                    val line = e ?: return
                    patchLog += line
                    Log.i(TAG, line)
                    patchLog += "\n"
                }
            }

            try {
                val stateError = validateUnpatchState()
                if (stateError != null) {
                    error = stateError
                    logs.add(stateError)
                    logs.add("****************************")
                    return@launch
                }

                val result = shell.newJob().add(
                    "export ASH_STANDALONE=1",
                    "cd ${shQuote(patchDir.path)}",
                    "cp ${shQuote("/data/adb/ap/ori.img")} new-boot.img",
                    "./busybox sh ./boot_unpatch.sh ${shQuote(bootDev)}",
                    "rm -f ${shQuote(APApplication.APD_PATH)}",
                    "rm -rf ${shQuote(APApplication.APATCH_FOLDER)}",
                ).to(logs, logs).exec()

                if (result.isSuccess) {
                    logs.add(" Unpatch successful")
                    needReboot = true
                    APApplication.markNeedReboot()
                } else {
                    logs.add(" Unpatch failed")
                    error = result.err.joinToString("\n")
                }
                logs.add("****************************")
            } catch (t: Throwable) {
                error = t.message ?: "Unpatch failed"
                logs.add(error)
                logs.add("****************************")
                Log.e(TAG, "unpatch failed", t)
            } finally {
                patchdone = true
                patching = false
            }
        }
    }

    fun isSuExecutable(): Boolean {
        val suFile = File("/system/bin/su")
        return suFile.exists() && suFile.canExecute()
    }
    fun doPatch(mode: PatchMode) {
        viewModelScope.launch(Dispatchers.IO) {
            patching = true
            patchdone = false
            needReboot = false
            patchLog = ""
            error = ""
            Log.d(TAG, "starting patching...")

            val apVer = Version.getManagerVersion().second
            val rand = (1..4).map { ('a'..'z').random() }.joinToString("")
            val outFilename = "apatch_patched_${apVer}_${BuildConfig.buildKPV}_${rand}.img"

            val logs = object : CallbackList<String>() {
                override fun onAddElement(e: String?) {
                    val line = e ?: return
                    patchLog += line
                    Log.d(TAG, line)
                    patchLog += "\n"
                }
            }
            logs.add("****************************")

            try {
                val patchCommand = mutableListOf("./busybox", "sh", "boot_patch.sh")

                // adapt for 0.10.7 and lower KP
                var isKpOld = false

                if (mode == PatchMode.PATCH_AND_INSTALL || mode == PatchMode.INSTALL_TO_NEXT_SLOT) {
                    val kpCheckCommand = shCommand(
                        APApplication.SUPERCMD,
                        superkey,
                        "-Z",
                        APApplication.MAGISK_SCONTEXT,
                        "-c",
                        "whoami"
                    )
                    val kpCheck = shell.newJob().add(kpCheckCommand).exec()

                    if (kpCheck.isSuccess && !isSuExecutable()) {
                        patchCommand.addAll(
                            0,
                            listOf(
                                APApplication.SUPERCMD,
                                APApplication.superKey,
                                "-Z",
                                APApplication.MAGISK_SCONTEXT,
                                "-c"
                            )
                        )
                        patchCommand.addAll(listOf(superkey, srcBoot.path, "true"))
                    } else {
                        patchCommand.addAll(listOf(superkey, srcBoot.path, "true"))
                        isKpOld = true
                    }
                } else {
                    patchCommand.addAll(listOf(superkey, srcBoot.path))
                }

                for (i in 0..<newExtrasFileName.size) {
                    patchCommand.addAll(listOf("-M", newExtrasFileName[i]))
                    val extra = newExtras[i]
                    if (extra.args.isNotEmpty()) {
                        patchCommand.addAll(listOf("-A", extra.args))
                    }
                    if (extra.event.isNotEmpty()) {
                        patchCommand.addAll(listOf("-V", extra.event))
                    }
                    patchCommand.addAll(listOf("-T", extra.type.desc))
                }
                for (i in 0..<existedExtras.size) {
                    val extra = existedExtras[i]
                    patchCommand.addAll(listOf("-E", extra.name))
                    if (extra.args.isNotEmpty()) {
                        patchCommand.addAll(listOf("-A", extra.args))
                    }
                    if (extra.event.isNotEmpty()) {
                        patchCommand.addAll(listOf("-V", extra.event))
                    }
                    patchCommand.addAll(listOf("-T", extra.type.desc))
                }

                Log.i(TAG, "patchCommand: $patchCommand")

                val succ = if (isKpOld) {
                    val resultString = shCommand(patchCommand)
                    val result = shell.newJob().add(
                        "export ASH_STANDALONE=1",
                        "cd ${shQuote(patchDir.path)}",
                        resultString,
                    ).to(logs, logs).exec()
                    result.isSuccess
                } else {
                    val builder = ProcessBuilder(patchCommand)
                    builder.environment()["ASH_STANDALONE"] = "1"
                    builder.directory(patchDir)
                    builder.redirectErrorStream(true)

                    val process = builder.start()
                    val logThread = Thread {
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val logLine = line ?: continue
                                patchLog += logLine
                                Log.i(TAG, logLine)
                                patchLog += "\n"
                            }
                        }
                    }
                    logThread.start()
                    val exitedCleanly = process.waitFor() == 0
                    logThread.join()
                    exitedCleanly
                }

                if (!succ) {
                    error = " Patch failed."
                    logs.add(error)
                    logs.add("****************************")
                    return@launch
                }

                if (mode == PatchMode.PATCH_AND_INSTALL || mode == PatchMode.INSTALL_TO_NEXT_SLOT) {
                    persistPatchState()
                }

                if (mode == PatchMode.PATCH_AND_INSTALL) {
                    logs.add("- Reboot to finish the installation...")
                    needReboot = true
                    APApplication.markNeedReboot()
                } else if (mode == PatchMode.INSTALL_TO_NEXT_SLOT) {
                    logs.add("- Connecting boot hal...")
                    val bootctlStatus = shell.newJob().add(
                        "cd ${shQuote(patchDir.path)}",
                        "chmod 0755 ${shQuote("${patchDir.path}/bootctl")}",
                        "./bootctl hal-info"
                    ).to(logs, logs).exec()
                    if (!bootctlStatus.isSuccess) {
                        logs.add("[X] Failed to connect to boot hal, you may need switch slot manually")
                    } else {
                        val currSlot = shellForResult(
                            shell,
                            "cd ${shQuote(patchDir.path)}",
                            "./bootctl get-current-slot"
                        ).out.joinToString("\n")
                        val targetSlot = if (currSlot.contains("0")) 1 else 0
                        logs.add("- Switching to next slot: $targetSlot...")
                        val setNextActiveSlot = shell.newJob().add(
                            "cd ${shQuote(patchDir.path)}",
                            "./bootctl set-active-boot-slot $targetSlot"
                        ).exec()
                        if (setNextActiveSlot.isSuccess) {
                            logs.add("- Switch done")
                            logs.add("- Writing boot marker script...")
                            val postOtaScript = """
                                #!/system/bin/sh
                                chmod 0755 ${patchDir.path}/bootctl
                                chown root:root ${patchDir.path}/bootctl
                                ${patchDir.path}/bootctl mark-boot-successful

                                rm -rf ${patchDir.path}
                                rm -f /data/adb/post-fs-data.d/post_ota.sh
                            """.trimIndent()
                            val markBootableScript = shell.newJob().add(
                                "mkdir -p /data/adb/post-fs-data.d",
                                """
                                cat > /data/adb/post-fs-data.d/post_ota.sh <<'EOF'
                                $postOtaScript
                                EOF
                                """.trimIndent(),
                                "chmod 0700 /data/adb/post-fs-data.d/post_ota.sh",
                                "chown root:root /data/adb/post-fs-data.d/post_ota.sh",
                            ).to(logs, logs).exec()
                            if (markBootableScript.isSuccess) {
                                logs.add("- Boot marker script write done")
                            } else {
                                logs.add("[X] Boot marker scripts write failed")
                            }
                        }
                    }
                    logs.add("- Reboot to finish the installation...")
                    needReboot = true
                    APApplication.markNeedReboot()
                } else if (mode == PatchMode.PATCH_ONLY) {
                    val newBootFile = patchDir.getChildFile("new-boot.img")
                    val outDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!outDir.exists()) outDir.mkdirs()
                    val outPath = File(outDir, outFilename)
                    val inputUri = newBootFile.getUri(apApp)

                    val writeSucceeded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val outUri = createDownloadUri(apApp, outFilename)
                        insertDownload(apApp, outUri, inputUri)
                    } else {
                        newBootFile.inputStream().copyAndClose(outPath.outputStream())
                        true
                    }
                    if (writeSucceeded) {
                        logs.add(" Output file is written to ")
                        logs.add(" ${outPath.path}")
                    } else {
                        logs.add(" Write patched boot.img failed")
                    }
                }
                logs.add("****************************")
            } catch (t: Throwable) {
                error = t.message ?: "Patch failed"
                logs.add(error)
                logs.add("****************************")
                Log.e(TAG, "patch failed", t)
            } finally {
                patchdone = true
                patching = false
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun createDownloadUri(context: Context, outFilename: String): Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, outFilename)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun insertDownload(context: Context, outUri: Uri?, inputUri: Uri): Boolean {
        if (outUri == null) return false

        val resolver = context.contentResolver
        try {
            val inputStream = resolver.openInputStream(inputUri) ?: run {
                resolver.delete(outUri, null, null)
                return false
            }
            val outputStream = resolver.openOutputStream(outUri) ?: run {
                inputStream.close()
                resolver.delete(outUri, null, null)
                return false
            }

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(outUri, contentValues, null, null)

            return true
        } catch (_: FileNotFoundException) {
            resolver.delete(outUri, null, null)
            return false
        }
    }

    fun File.getUri(context: Context): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, this)
    }


    override fun onCleared() {
        runCatching { shell.close() }
        super.onCleared()
    }

}