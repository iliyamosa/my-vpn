package app.vpn.common

import app.vpn.BuildConfig

/** Where the public app looks for remote/config.json. */
object ConfigSource {
    const val CONFIG_PATH = "remote/config.json"

    val repo: String get() = BuildConfig.CONFIG_REPO
    val branch: String get() = BuildConfig.CONFIG_BRANCH.ifBlank { "main" }

    /** Built-in mirrors. More can be added from the admin app ("mirrors"). */
    fun defaultUrls(): List<String> {
        if (repo.isBlank()) return emptyList()
        return listOf(
            "https://raw.githubusercontent.com/$repo/$branch/$CONFIG_PATH",
            "https://cdn.jsdelivr.net/gh/$repo@$branch/$CONFIG_PATH",
            "https://fastly.jsdelivr.net/gh/$repo@$branch/$CONFIG_PATH",
            "https://gcore.jsdelivr.net/gh/$repo@$branch/$CONFIG_PATH",
        )
    }

    /** jsDelivr caches files; the admin app asks it to refresh after every save. */
    fun purgeUrls(): List<String> {
        if (repo.isBlank()) return emptyList()
        return listOf("https://purge.jsdelivr.net/gh/$repo@$branch/$CONFIG_PATH")
    }
}
