plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
}

project.ext.set("kernelPatchVersion", "0.13.1")

fun Project.stringProperty(name: String, defaultValue: String): String {
    return providers.gradleProperty(name).orNull?.trim()?.takeIf { it.isNotEmpty() } ?: defaultValue
}

fun Project.intProperty(name: String, defaultValue: Int): Int {
    return providers.gradleProperty(name).orNull?.trim()?.toIntOrNull() ?: defaultValue
}

val managerAppNameValue = stringProperty("fork.appName", "UPatch")
val managerArtifactNameValue = stringProperty("fork.artifactName", managerAppNameValue)

val androidMinSdkVersion by extra(intProperty("android.minSdk", 26))
val androidTargetSdkVersion by extra(intProperty("android.targetSdk", 36))
val androidCompileSdkVersion by extra(intProperty("android.compileSdk", 36))
val androidTargetSdkPreview by extra(stringProperty("android.targetSdkPreview", ""))
val androidCompileSdkPreview by extra(stringProperty("android.compileSdkPreview", ""))
val androidBuildToolsVersion by extra(stringProperty("android.buildTools", "36.1.0"))
val androidCompileNdkVersion by extra(stringProperty("android.ndkVersion", "29.0.14206865"))

val managerAppName by extra(managerAppNameValue)
val managerApplicationId by extra(stringProperty("fork.applicationId", "dev.upatch.manager"))
val managerArtifactName by extra(managerArtifactNameValue)
val managerSourceRepoUrl by extra(stringProperty("fork.sourceRepoUrl", "https://github.com/bmax121/UPatch"))
val managerIssuesUrl by extra(stringProperty("fork.issuesUrl", "https://github.com/bmax121/UPatch/issues/new/choose"))
val managerDocsUrl by extra(stringProperty("fork.docsUrl", "https://apatch.dev"))
val managerChannelUrl by extra(stringProperty("fork.channelUrl", "https://t.me/UPatchChannel"))
val managerDiscussionUrl by extra(stringProperty("fork.discussionUrl", "https://t.me/Apatch_discuss"))
val managerWeblateUrl by extra(stringProperty("fork.weblateUrl", "https://hosted.weblate.org/engage/UPatch"))
val managerUpdateApiUrl by extra(stringProperty("fork.updateApiUrl", "https://api.github.com/repos/bmax121/UPatch/releases/latest"))
val managerExpectedSignatureSha256 by extra(stringProperty("fork.expectedSignatureSha256", ""))
val managerEnforceSignatureVerification by extra(stringProperty("fork.enforceSignatureVerification", "false").equals("true", ignoreCase = true))

val managerVersionCode by extra(getVersionCode())
val managerVersionName by extra(getVersionName())
val branchName by extra(getBranch())

fun tryExec(command: String): String? = runCatching {
    project.providers.exec {
        commandLine(command.split(" "))
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
}.getOrNull()

fun getGitCommitCount(): Int {
    return tryExec("git rev-list --count HEAD")?.toIntOrNull()
        ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        ?: 0
}

fun getGitDescribe(): String {
    return tryExec("git rev-parse --verify --short HEAD")
        ?: System.getenv("GITHUB_SHA")?.take(7)
        ?: "local"
}

fun getVersionCode(): Int {
    val commitCount = getGitCommitCount()
    val major = 1
    return major * 10000 + commitCount + 200
}

fun getBranch(): String {
    return tryExec("git rev-parse --abbrev-ref HEAD")
        ?: System.getenv("GITHUB_REF_NAME")
        ?: "local"
}

fun getVersionName(): String {
    return getGitDescribe()
}

tasks.register("printVersion") {
    doLast {
        println("Version code: $managerVersionCode")
        println("Version name: $managerVersionName")
    }
}
