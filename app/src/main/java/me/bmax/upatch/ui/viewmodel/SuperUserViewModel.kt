package me.bmax.upatch.ui.viewmodel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.bmax.upatch.IUPRootService
import me.bmax.upatch.Natives
import me.bmax.upatch.upApp
import me.bmax.upatch.services.RootServices
import me.bmax.upatch.util.UPatchCli
import me.bmax.upatch.util.HanziToPinyin
import me.bmax.upatch.util.PkgConfig
import java.text.Collator
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


class SuperUserViewModel : ViewModel() {
    companion object {
        private const val TAG = "SuperUserViewModel"
        
        // Icon loading still needs a way to find apps, but we should avoid static state if possible.
        // For now, let's keep a cache if needed, but the primary state
        // should be in the ViewModel instance.
        private val appsLock = Any()
        var apps by mutableStateOf<List<AppInfo>>(emptyList())
        var appsMap by mutableStateOf<Map<String, AppInfo>>(emptyMap())

        fun getAppIconDrawable(context: Context, packageName: String): Drawable? {
            val appMap = synchronized(appsLock) { appsMap }
            val appDetail = appMap[packageName]
            return appDetail?.packageInfo?.applicationInfo?.loadIcon(context.packageManager)
        }
    }

    @Parcelize
    data class AppInfo(
        val label: String,
        val packageInfo: PackageInfo,
        val config: PkgConfig.Config,
        val labelPinyin: String,
        val packageNameLower: String
    ) : Parcelable {
        val packageName: String
            get() = packageInfo.packageName
        val uid: Int
            get() = packageInfo.applicationInfo!!.uid
    }

    var search by mutableStateOf("")
    var showSystemApps by mutableStateOf(false)
    var isRefreshing by mutableStateOf(false)
        private set

    private val sortedList by derivedStateOf {
        val comparator = compareBy<AppInfo> {
            when {
                it.config.allow != 0 -> 0
                it.config.exclude == 1 -> 1
                else -> 2
            }
        }.then(compareBy(Collator.getInstance(Locale.getDefault()), AppInfo::label))
        apps.sortedWith(comparator)
    }

    val appList by derivedStateOf {
        val query = search.lowercase()
        sortedList.filter {
            it.label.lowercase().contains(query) || 
            it.packageNameLower.contains(query) || 
            it.labelPinyin.contains(query)
        }.filter {
            it.uid == 2000 // Always show shell
                    || showSystemApps || it.packageInfo.applicationInfo!!.flags.and(ApplicationInfo.FLAG_SYSTEM) == 0
        }
    }

    private suspend inline fun connectRootService(
        crossinline onDisconnect: () -> Unit = {}
    ): Pair<IBinder, ServiceConnection> = suspendCoroutine {
        val connection = object : ServiceConnection {
            override fun onServiceDisconnected(name: ComponentName?) {
                onDisconnect()
            }

            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                it.resume(binder as IBinder to this)
            }
        }
        val intent = Intent(upApp, RootServices::class.java)
        val task = RootServices.bindOrTask(
            intent,
            Shell.EXECUTOR,
            connection,
        )
        val shell = UPatchCli.SHELL
        task?.let { it1 -> shell.execTask(it1) }
    }

    private fun stopRootService() {
        val intent = Intent(upApp, RootServices::class.java)
        RootServices.stop(intent)
    }

    suspend fun fetchAppList() {
        isRefreshing = true

        try {
            withContext(Dispatchers.IO) {
                // bypassed
                val allPackages = upApp.packageManager.getInstalledPackages(0)

                withContext(Dispatchers.Main) {
                    stopRootService()
                }
                val uids = Natives.suUids().toList()
                
                Natives.su()
                val configs = PkgConfig.readConfigs()

                Log.d(TAG, "all configs: $configs")

                val newApps = allPackages.map {
                    val appInfo = it.applicationInfo
                    val uid = appInfo!!.uid
                    val actProfile = if (uids.contains(uid)) Natives.suProfile(uid) else null
                    val config = configs.getOrDefault(
                        uid, PkgConfig.Config(appInfo.packageName, Natives.isUidExcluded(uid), 0, Natives.Profile(uid = uid))
                    )
                    config.allow = 0

                    // from kernel
                    if (actProfile != null) {
                        config.allow = 1
                        config.profile = actProfile
                    }
                    val label = appInfo.loadLabel(upApp.packageManager).toString()
                    AppInfo(
                        label = label,
                        packageInfo = it,
                        config = config,
                        labelPinyin = HanziToPinyin.getInstance().toPinyinString(label).lowercase(),
                        packageNameLower = it.packageName.lowercase()
                    )
                }

                withContext(Dispatchers.Main) {
                    synchronized(appsLock) {
                        apps = newApps
                        appsMap = newApps.associateBy { it.packageName }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch app list", e)
        } finally {
            isRefreshing = false
        }
    }
}
