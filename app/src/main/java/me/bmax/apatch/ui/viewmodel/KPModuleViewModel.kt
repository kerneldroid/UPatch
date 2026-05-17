package me.bmax.apatch.ui.viewmodel

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.bmax.apatch.Natives
import me.bmax.apatch.util.HanziToPinyin
import java.text.Collator
import java.util.Locale

class KPModuleViewModel : ViewModel() {
    companion object {
        private const val TAG = "KPModuleViewModel"
    }

    var modules by mutableStateOf<List<KPModel.KPMInfo>>(emptyList())
    var search by mutableStateOf("")
    var isRefreshing by mutableStateOf(false)
        private set


    val moduleList by derivedStateOf {
        val query = search.lowercase()
        val comparator = compareBy(
            comparator = Collator.getInstance(Locale.getDefault()),
            selector = KPModel.KPMInfo::name
        )

        modules.filter {
            it.name.contains(query, true) || 
            it.description.contains(query, true) ||
            it.author.contains(query, true) ||
            HanziToPinyin.getInstance().toPinyinString(it.name)?.contains(query, true) == true
        }.sortedWith(comparator)
    }

    var isNeedRefresh by mutableStateOf(false)
        private set

    fun markNeedRefresh() {
        isNeedRefresh = true
    }

    fun fetchModuleList() {
        viewModelScope.launch(Dispatchers.IO) {
            isRefreshing = true
            val oldModuleList = modules
            val start = SystemClock.elapsedRealtime()

            kotlin.runCatching {
                var names = Natives.kernelPatchModuleList()
                if (Natives.kernelPatchModuleNum() <= 0)
                    names = ""
                val nameList = names.split('\n').toList()
                Log.d(TAG, "kpm list: $nameList")
                modules = nameList.filter { it.isNotEmpty() }.map {
                    val infoline = Natives.kernelPatchModuleInfo(it)
                    var name = ""
                    var version = ""
                    var license = ""
                    var author = ""
                    var description = ""
                    var args = ""
                    for (line in infoline.split('\n')) {
                        when {
                            line.startsWith("name=") -> name = line.substring(5)
                            line.startsWith("version=") -> version = line.substring(8)
                            line.startsWith("license=") -> license = line.substring(8)
                            line.startsWith("author=") -> author = line.substring(7)
                            line.startsWith("description=") -> description = line.substring(12)
                            line.startsWith("args=") -> args = line.substring(5)
                        }
                    }
                    val info = KPModel.KPMInfo(
                        KPModel.ExtraType.KPM,
                        name,
                        "",
                        args,
                        version,
                        license,
                        author,
                        description
                    )
                    info
                }
                isNeedRefresh = false
            }.onFailure { e ->
                Log.e(TAG, "fetchModuleList: ", e)
                isRefreshing = false
            }

            // when both old and new is kotlin.collections.EmptyList
            // moduleList update will don't trigger
            if (oldModuleList === modules) {
                isRefreshing = false
            }

            Log.i(TAG, "load cost: ${SystemClock.elapsedRealtime() - start}, modules: $modules")
        }
    }


}
