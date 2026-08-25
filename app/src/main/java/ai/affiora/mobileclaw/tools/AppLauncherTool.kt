package ai.affiora.mobileclaw.tools

import android.content.Context
import android.content.Intent
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

class AppLauncherTool(
    private val context: Context,
) : AndroidTool {

    override val name: String = "app"

    override val description: String =
        """
        List installed apps, launch apps, or share text.

        Actions:
        - 'list_apps': list launchable installed apps.
        - 'launch': open an app. Prefer app_name using the visible app name
          exactly as the user says, for example 微信, 小红书, Notion.
          Do NOT guess package names.
        - 'share_text': share text, optionally targeting an app by app_name
          or package_name.
        """.trimIndent()

    override val parameters: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))

        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("action"))
            }
        )

        put("properties", buildJsonObject {

            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("list_apps"))
                    add(JsonPrimitive("launch"))
                    add(JsonPrimitive("share_text"))
                })
                put(
                    "description",
                    JsonPrimitive(
                        "Action to perform: list_apps, launch, or share_text."
                    )
                )
            })

            put("app_name", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive(
                        "Visible app name, such as 微信, 小红书, Notion. " +
                            "Preferred for launch requests."
                    )
                )
            })

            put("package_name", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive(
                        "Android package name. Optional. Do not guess it; " +
                            "normally use app_name instead."
                    )
                )
            })

            put("text", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "description",
                    JsonPrimitive(
                        "Text to share. Required for share_text."
                    )
                )
            })
        })
    }

    private data class LaunchTarget(
        val appName: String,
        val packageName: String,
        val activityName: String,
    )

    private data class NameResolution(
        val target: LaunchTarget? = null,
        val candidates: List<LaunchTarget> = emptyList(),
    )

    override suspend fun execute(
        params: Map<String, JsonElement>
    ): ToolResult {

        val action =
            params["action"]?.jsonPrimitive?.content
                ?: return ToolResult.Error(
                    "Missing required parameter: action"
                )

        return withContext(Dispatchers.IO) {
            when (action) {
                "list_apps" -> executeListApps()
                "launch" -> executeLaunch(params)
                "share_text" -> executeShareText(params)

                else -> ToolResult.Error(
                    "Unknown action: $action. " +
                        "Must be 'list_apps', 'launch', or 'share_text'."
                )
            }
        }
    }

    private fun getLaunchableApps(): List<LaunchTarget> {

        val pm = context.packageManager

        val launcherIntent =
            Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

        return pm
            .queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info ->

                val packageName =
                    info.activityInfo?.packageName
                        ?: return@mapNotNull null

                val activityName =
                    info.activityInfo?.name
                        ?: return@mapNotNull null

                val label =
                    info.loadLabel(pm)
                        ?.toString()
                        ?.trim()
                        .orEmpty()

                LaunchTarget(
                    appName =
                        label.ifBlank {
                            packageName
                        },
                    packageName = packageName,
                    activityName = activityName,
                )
            }
            .distinctBy {
                it.packageName
            }
            .sortedBy {
                it.appName.lowercase(Locale.ROOT)
            }
    }

    private fun normalizeName(
        value: String
    ): String {

        return value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(
                Regex(
                    "[\\s\\p{Punct}，。！？、·（）【】《》“”‘’]+"
                ),
                ""
            )
    }

    private fun resolveByName(
        appName: String,
        apps: List<LaunchTarget>,
    ): NameResolution {

        val query = normalizeName(appName)

        if (query.isBlank()) {
            return NameResolution()
        }

        val exactMatches =
            apps.filter { app ->

                normalizeName(app.appName) == query ||
                    app.packageName.equals(
                        appName,
                        ignoreCase = true
                    )
            }

        if (exactMatches.size == 1) {
            return NameResolution(
                target = exactMatches.first()
            )
        }

        if (exactMatches.size > 1) {
            return NameResolution(
                candidates = exactMatches
            )
        }

        val prefixMatches =
            apps.filter { app ->

                val name =
                    normalizeName(app.appName)

                name.startsWith(query)
            }

        if (prefixMatches.size == 1) {
            return NameResolution(
                target = prefixMatches.first()
            )
        }

        if (prefixMatches.size > 1) {
            return NameResolution(
                candidates = prefixMatches
            )
        }

        val containsMatches =
            apps.filter { app ->

                val name =
                    normalizeName(app.appName)

                name.contains(query) ||
                    (
                        query.length >= 2 &&
                            query.contains(name)
                        )
            }

        if (containsMatches.size == 1) {
            return NameResolution(
                target = containsMatches.first()
            )
        }

        if (containsMatches.size > 1) {
            return NameResolution(
                candidates = containsMatches
            )
        }

        return NameResolution()
    }

    private fun executeListApps(): ToolResult {

        val apps =
            getLaunchableApps()

        val results =
            buildJsonArray {

                for (app in apps) {

                    add(
                        buildJsonObject {

                            put(
                                "appName",
                                JsonPrimitive(
                                    app.appName
                                )
                            )

                            put(
                                "packageName",
                                JsonPrimitive(
                                    app.packageName
                                )
                            )
                        }
                    )
                }
            }

        return ToolResult.Success(
            results.toString()
        )
    }

    private fun executeLaunch(
        params: Map<String, JsonElement>
    ): ToolResult {

        val appName =
            params["app_name"]
                ?.jsonPrimitive
                ?.content
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val packageName =
            params["package_name"]
                ?.jsonPrimitive
                ?.content
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val apps =
            getLaunchableApps()

        if (packageName != null) {

            val target =
                apps.firstOrNull {
                    it.packageName.equals(
                        packageName,
                        ignoreCase = true
                    )
                }

            if (target != null) {
                return launchTarget(target)
            }

            val intent =
                context.packageManager
                    .getLaunchIntentForPackage(
                        packageName
                    )

            if (intent != null) {

                return try {

                    intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )

                    context.startActivity(intent)

                    ToolResult.Success(
                        "Launched $packageName"
                    )

                } catch (e: Exception) {

                    ToolResult.Error(
                        "Failed to launch $packageName: ${e.message}"
                    )
                }
            }
        }

        if (appName == null) {

            return ToolResult.Error(
                "Missing app_name. " +
                    "Use the visible app name, for example 微信, 小红书, or Notion."
            )
        }

        val resolution =
            resolveByName(
                appName,
                apps
            )

        resolution.target?.let {
            return launchTarget(it)
        }

        if (resolution.candidates.isNotEmpty()) {

            val choices =
                resolution.candidates
                    .take(8)
                    .joinToString(", ") {
                        "${it.appName} (${it.packageName})"
                    }

            return ToolResult.Error(
                "Multiple installed apps match '$appName': $choices. " +
                    "Retry using the exact visible app name."
            )
        }

        return ToolResult.Error(
            "No installed launchable app found matching '$appName'."
        )
    }

    private fun launchTarget(
        target: LaunchTarget
    ): ToolResult {

        val pm =
            context.packageManager

        val launchIntent =
            pm.getLaunchIntentForPackage(
                target.packageName
            )
                ?: Intent(
                    Intent.ACTION_MAIN
                ).apply {

                    addCategory(
                        Intent.CATEGORY_LAUNCHER
                    )

                    setClassName(
                        target.packageName,
                        target.activityName
                    )
                }

        return try {

            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )

            context.startActivity(
                launchIntent
            )

            ToolResult.Success(
                "Launched ${target.appName} (${target.packageName})"
            )

        } catch (e: Exception) {

            ToolResult.Error(
                "Failed to launch ${target.appName}: ${e.message}"
            )
        }
    }

    private fun executeShareText(
        params: Map<String, JsonElement>
    ): ToolResult {

        val text =
            params["text"]
                ?.jsonPrimitive
                ?.content
                ?: return ToolResult.Error(
                    "Missing required parameter: text"
                )

        val appName =
            params["app_name"]
                ?.jsonPrimitive
                ?.content
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        var targetPackage =
            params["package_name"]
                ?.jsonPrimitive
                ?.content
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        if (
            targetPackage == null &&
            appName != null
        ) {

            val resolution =
                resolveByName(
                    appName,
                    getLaunchableApps()
                )

            if (resolution.target != null) {

                targetPackage =
                    resolution.target.packageName

            } else if (
                resolution.candidates.isNotEmpty()
            ) {

                val choices =
                    resolution.candidates
                        .take(8)
                        .joinToString(", ") {
                            it.appName
                        }

                return ToolResult.Error(
                    "Multiple apps match '$appName': $choices."
                )

            } else {

                return ToolResult.Error(
                    "No installed app found matching '$appName'."
                )
            }
        }

        val shareIntent =
            Intent(
                Intent.ACTION_SEND
            ).apply {

                type = "text/plain"

                putExtra(
                    Intent.EXTRA_TEXT,
                    text
                )

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )

                if (targetPackage != null) {
                    setPackage(
                        targetPackage
                    )
                }
            }

        return try {

            if (targetPackage != null) {

                context.startActivity(
                    shareIntent
                )

            } else {

                val chooser =
                    Intent.createChooser(
                        shareIntent,
                        "Share via"
                    ).apply {

                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }

                context.startActivity(
                    chooser
                )
            }

            ToolResult.Success(
                "Share intent sent."
            )

        } catch (e: Exception) {

            ToolResult.Error(
                "Failed to share text: ${e.message}"
            )
        }
    }
}A
