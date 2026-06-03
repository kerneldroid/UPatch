package me.bmax.upatch.util

import android.content.Context
import android.os.Build
import android.system.Os
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private fun dumpCommand(shell: Shell, command: String, target: File) {
    shell.newJob().add("$command > ${shQuote(target.absolutePath)} 2>&1 || true").exec()
}

private fun archiveDirIfExists(shell: Shell, sourceDir: String, target: File) {
    shell.newJob().add(
        "if [ -d ${shQuote(sourceDir)} ]; then tar -czf ${shQuote(target.absolutePath)} -C ${shQuote(sourceDir)} .; else : > ${shQuote(target.absolutePath)}; fi"
    ).exec()
}

private fun copyFileIfExists(shell: Shell, sourcePath: String, target: File) {
    shell.newJob().add(
        "if [ -f ${shQuote(sourcePath)} ]; then cp ${shQuote(sourcePath)} ${shQuote(target.absolutePath)}; else : > ${shQuote(target.absolutePath)}; fi"
    ).exec()
}

fun getBugreportFile(context: Context): File {
    val bugreportDir = File(context.cacheDir, "bugreport")
    bugreportDir.mkdirs()

    val dmesgFile = File(bugreportDir, "dmesg.txt")
    val logcatFile = File(bugreportDir, "logcat.txt")
    val tombstonesFile = File(bugreportDir, "tombstones.tar.gz")
    val dropboxFile = File(bugreportDir, "dropbox.tar.gz")
    val pstoreFile = File(bugreportDir, "pstore.tar.gz")
    val diagFile = File(bugreportDir, "diag.tar.gz")
    val bootlogFile = File(bugreportDir, "bootlog.tar.gz")
    val mountsFile = File(bugreportDir, "mounts.txt")
    val fileSystemsFile = File(bugreportDir, "filesystems.txt")
    val apFileTree = File(bugreportDir, "ap_tree.txt")
    val appListFile = File(bugreportDir, "packages.txt")
    val propFile = File(bugreportDir, "props.txt")
    val packageConfigFile = File(bugreportDir, "package_config")
    val kernelConfig = File(bugreportDir, "defconfig")

    tryGetRootShell().use { shell ->
        dumpCommand(shell, "dmesg", dmesgFile)
        dumpCommand(shell, "logcat -d", logcatFile)
        archiveDirIfExists(shell, "/data/tombstones", tombstonesFile)
        archiveDirIfExists(shell, "/data/system/dropbox", dropboxFile)
        archiveDirIfExists(shell, "/sys/fs/pstore", pstoreFile)
        archiveDirIfExists(shell, "/data/vendor/diag", diagFile)
        archiveDirIfExists(shell, "/data/adb/up/log", bootlogFile)

        dumpCommand(shell, "cat /proc/1/mountinfo", mountsFile)
        dumpCommand(shell, "cat /proc/filesystems", fileSystemsFile)
        dumpCommand(shell, "ls -alRZ /data/adb", apFileTree)
        copyFileIfExists(shell, "/data/system/packages.list", appListFile)
        dumpCommand(shell, "getprop", propFile)
        copyFileIfExists(shell, "/data/adb/up/package_config", packageConfigFile)
        dumpCommand(shell, "zcat /proc/config.gz", kernelConfig)

        val selinux = ShellUtils.fastCmd(shell, "getenforce")

        val buildInfo = File(bugreportDir, "basic.txt")
        PrintWriter(FileWriter(buildInfo)).use { pw ->
            pw.println("Kernel: ${System.getProperty("os.version")}")
            pw.println("BRAND: ${Build.BRAND}")
            pw.println("MODEL: ${Build.MODEL}")
            pw.println("PRODUCT: ${Build.PRODUCT}")
            pw.println("MANUFACTURER: ${Build.MANUFACTURER}")
            pw.println("SDK: ${Build.VERSION.SDK_INT}")
            pw.println("PREVIEW_SDK: ${Build.VERSION.PREVIEW_SDK_INT}")
            pw.println("FINGERPRINT: ${Build.FINGERPRINT}")
            pw.println("DEVICE: ${Build.DEVICE}")
            pw.println("Manager: ${Version.getManagerVersion()}")
            pw.println("SELinux: $selinux")

            val uname = Os.uname()
            pw.println("KernelRelease: ${uname.release}")
            pw.println("KernelVersion: ${uname.version}")
            pw.println("Machine: ${uname.machine}")
            pw.println("Nodename: ${uname.nodename}")
            pw.println("Sysname: ${uname.sysname}")

            pw.println("KPatch: ${Version.installedKPVString()}")
            pw.println("UPatch: ${Version.installedApdVString}")
            val safeMode = false
            pw.println("SafeMode: $safeMode")
        }

        val modulesFile = File(bugreportDir, "modules.json")
        modulesFile.writeText(runCatching { listModules() }.getOrDefault("[]"))

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH_mm")
        val current = LocalDateTime.now().format(formatter)
        val targetFile = File(context.cacheDir, "UPatch_bugreport_${current}.tar.gz")

        shell.newJob().add(
            "tar czf ${shQuote(targetFile.absolutePath)} -C ${shQuote(bugreportDir.absolutePath)} ."
        ).exec()
        shell.newJob().add("rm -rf ${shQuote(bugreportDir.absolutePath)}").exec()
        shell.newJob().add("chmod 0644 ${shQuote(targetFile.absolutePath)}").exec()

        return targetFile
    }
}
