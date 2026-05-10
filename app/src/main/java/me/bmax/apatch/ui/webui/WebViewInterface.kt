package me.bmax.apatch.ui.webui

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Window
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.internal.UiThreadHandler
import me.bmax.apatch.ui.WebUIActivity
import me.bmax.apatch.ui.viewmodel.SuperUserViewModel
import me.bmax.apatch.util.containsUnsafeControlChars
import me.bmax.apatch.util.createRootShell
import me.bmax.apatch.util.isSafeCallbackRef
import me.bmax.apatch.util.isSafeEnvKey
import me.bmax.apatch.util.shCommand
import me.bmax.apatch.util.shQuote
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture

class WebViewInterface(val context: Context, private val webView: WebView) {
    private fun isTrustedPage(): Boolean {
        val url = webView.url ?: return false
        val parsed = Uri.parse(url)
        return parsed.scheme.equals("https", ignoreCase = true) &&
            parsed.host.equals(WebUIActivity.WEBUI_DOMAIN, ignoreCase = true)
    }

    private fun requireTrustedPage(): Boolean {
        val trusted = isTrustedPage()
        if (!trusted) {
            webView.post {
                Toast.makeText(context, "Blocked WebUI call from untrusted page", Toast.LENGTH_SHORT).show()
            }
        }
        return trusted
    }

    private fun sanitizeCallbackRef(callbackRef: String): String {
        val trimmed = callbackRef.trim()
        require(isSafeCallbackRef(trimmed)) { "Invalid callback reference" }
        return trimmed
    }

    private fun processOptions(sb: StringBuilder, options: String?) {
        val opts = if (options == null) JSONObject() else JSONObject(options)

        val cwd = opts.optString("cwd")
        if (!TextUtils.isEmpty(cwd)) {
            require(!containsUnsafeControlChars(cwd)) { "Invalid cwd" }
            sb.append("cd ").append(shQuote(cwd)).append(";")
        }

        opts.optJSONObject("env")?.let { env ->
            env.keys().forEach { key ->
                require(isSafeEnvKey(key)) { "Invalid env key" }
                val value = env.getString(key)
                require(!containsUnsafeControlChars(value)) { "Invalid env value" }
                sb.append("export ").append(key).append("=").append(shQuote(value)).append(";")
            }
        }
    }

    @JavascriptInterface
    fun exec(cmd: String): String {
        if (!requireTrustedPage()) return ""
        require(cmd.length <= 8192) { "Command too large" }
        return createRootShell().use { shell ->
            ShellUtils.fastCmd(shell, cmd)
        }
    }

    @JavascriptInterface
    fun exec(cmd: String, callbackFunc: String) {
        exec(cmd, null, callbackFunc)
    }

    @JavascriptInterface
    fun exec(cmd: String, options: String?, callbackFunc: String) {
        if (!requireTrustedPage()) return
        require(cmd.length <= 8192) { "Command too large" }
        val callback = sanitizeCallbackRef(callbackFunc)
        val finalCommand = StringBuilder()
        processOptions(finalCommand, options)
        finalCommand.append(cmd)

        val result = createRootShell().use { shell ->
            shell.newJob().add(finalCommand.toString()).to(ArrayList(), ArrayList()).exec()
        }
        val stdout = result.out.joinToString(separator = "\n")
        val stderr = result.err.joinToString(separator = "\n")

        val jsCode = """
            (function() {
                try {
                    $callback(${result.code}, ${JSONObject.quote(stdout)}, ${JSONObject.quote(stderr)});
                } catch (e) {
                    console.error(e);
                }
            })();
        """.trimIndent()
        webView.post {
            webView.evaluateJavascript(jsCode, null)
        }
    }

    @JavascriptInterface
    fun spawn(command: String, args: String, options: String?, callbackFunc: String) {
        if (!requireTrustedPage()) return
        require(command.length <= 512) { "Command too large" }
        require(args.length <= 32768) { "Arguments too large" }
        val callback = sanitizeCallbackRef(callbackFunc)
        val finalCommand = StringBuilder()

        processOptions(finalCommand, options)

        if (!TextUtils.isEmpty(args)) {
            val argv = mutableListOf(command)
            JSONArray(args).let { argsArray ->
                for (i in 0 until argsArray.length()) {
                    argv.add(argsArray.getString(i))
                }
            }
            finalCommand.append(shCommand(argv))
        } else {
            finalCommand.append(shQuote(command))
        }

        val shell = createRootShell()

        val emitData = fun(name: String, data: String) {
            val jsCode = """
                (function() {
                    try {
                        $callback.$name.emit('data', ${JSONObject.quote(data)});
                    } catch (e) {
                        console.error('emitData', e);
                    }
                })();
            """.trimIndent()
            webView.post {
                webView.evaluateJavascript(jsCode, null)
            }
        }

        val stdout = object : CallbackList<String>(UiThreadHandler::runAndWait) {
            override fun onAddElement(s: String) {
                emitData("stdout", s)
            }
        }

        val stderr = object : CallbackList<String>(UiThreadHandler::runAndWait) {
            override fun onAddElement(s: String) {
                emitData("stderr", s)
            }
        }

        val future = shell.newJob().add(finalCommand.toString()).to(stdout, stderr).enqueue()
        val completableFuture = CompletableFuture.supplyAsync {
            future.get()
        }

        completableFuture.whenComplete { result, throwable ->
            try {
                if (throwable != null) {
                    val emitException = """
                        (function() {
                            try {
                                var err = new Error();
                                err.message = ${JSONObject.quote(throwable.message ?: "spawn failed")};
                                $callback.emit('error', err);
                            } catch (e) {
                                console.error('emitThrowable', e);
                            }
                        })();
                    """.trimIndent()
                    webView.post {
                        webView.evaluateJavascript(emitException, null)
                    }
                    return@whenComplete
                }

                val exitResult = result ?: return@whenComplete
                val emitExitCode = """
                    (function() {
                        try {
                            $callback.emit('exit', ${exitResult.code});
                        } catch (e) {
                            console.error('emitExit', e);
                        }
                    })();
                """.trimIndent()
                webView.post {
                    webView.evaluateJavascript(emitExitCode, null)
                }

                if (exitResult.code != 0) {
                    val emitErrCode = """
                        (function() {
                            try {
                                var err = new Error();
                                err.exitCode = ${exitResult.code};
                                err.message = ${JSONObject.quote(exitResult.err.joinToString("\n"))};
                                $callback.emit('error', err);
                            } catch (e) {
                                console.error('emitErr', e);
                            }
                        })();
                    """.trimIndent()
                    webView.post {
                        webView.evaluateJavascript(emitErrCode, null)
                    }
                }
            } finally {
                shell.close()
            }
        }
    }

    @JavascriptInterface
    fun toast(msg: String) {
        webView.post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun fullScreen(enable: Boolean) {
        if (context is Activity) {
            Handler(Looper.getMainLooper()).post {
                if (enable) {
                    hideSystemUI(context.window)
                } else {
                    showSystemUI(context.window)
                }
            }
        }
        enableInsets(enable)
    }

    @JavascriptInterface
    fun enableInsets(enable: Boolean = true) {
        if (context is WebUIActivity) {
            context.enableInsets(enable)
        }
    }

    @JavascriptInterface
    fun listPackages(type: String): String {
        val packageNames = SuperUserViewModel.apps
            .filter { appInfo ->
                val flags = appInfo.packageInfo.applicationInfo?.flags ?: 0
                when (type.lowercase()) {
                    "system" -> (flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    "user" -> (flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    else -> true
                }
            }
            .map { it.packageName }
            .sorted()

        val jsonArray = JSONArray()
        for (pkgName in packageNames) {
            jsonArray.put(pkgName)
        }
        return jsonArray.toString()
    }

    @JavascriptInterface
    fun getPackagesInfo(packageNamesJson: String): String {
        val packageNames = JSONArray(packageNamesJson)
        val jsonArray = JSONArray()
        val appMap = SuperUserViewModel.apps.associateBy { it.packageName }
        for (i in 0 until packageNames.length()) {
            val pkgName = packageNames.getString(i)
            val appInfo = appMap[pkgName]
            if (appInfo != null) {
                val pkg = appInfo.packageInfo
                val app = pkg.applicationInfo
                val obj = JSONObject()
                obj.put("packageName", pkg.packageName)
                obj.put("versionName", pkg.versionName ?: "")
                obj.put("versionCode", PackageInfoCompat.getLongVersionCode(pkg))
                obj.put("appLabel", appInfo.label)
                obj.put("isSystem", if (app != null) ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) else JSONObject.NULL)
                obj.put("uid", app?.uid ?: JSONObject.NULL)
                jsonArray.put(obj)
            } else {
                val obj = JSONObject()
                obj.put("packageName", pkgName)
                obj.put("error", "Package not found or inaccessible")
                jsonArray.put(obj)
            }
        }
        return jsonArray.toString()
    }
}

fun hideSystemUI(window: Window) {
    WindowInsetsControllerCompat(window, window.decorView).let { controller ->
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

fun showSystemUI(window: Window) =
    WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
