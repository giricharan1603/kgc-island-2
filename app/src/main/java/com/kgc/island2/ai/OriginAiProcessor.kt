package com.kgc.island2.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import com.google.mlkit.nl.entityextraction.*
import com.kgc.island2.engine.IslandStateEngine
import com.kgc.island2.model.IslandPayload
import com.kgc.island2.model.TargetAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object OriginAiProcessor {

    private val PHONE_REGEX = Regex("""^\+?[0-9\-\s()]{7,15}$""")
    private val URL_REGEX = Regex("""^(https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)

    /**
     * Queries real installed apps on the device that can accept the dragged/copied payload
     */
    fun resolveInstalledTargetApps(context: Context, baseIntent: Intent, maxCount: Int = 5): List<TargetAppInfo> {
        val pm = context.packageManager
        val resolveInfos = pm.queryIntentActivities(baseIntent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfos
            .filter { it.activityInfo.packageName != context.packageName }
            .take(maxCount)
            .map { info ->
                val pkg = info.activityInfo.packageName
                val cls = info.activityInfo.name
                val specificIntent = Intent(baseIntent).apply {
                    component = ComponentName(pkg, cls)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                TargetAppInfo(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    icon = info.loadIcon(pm),
                    launchIntent = specificIntent
                )
            }
    }

    /**
     * Real-time Copy & Go and Drag & Go Intent Routing
     */
    suspend fun processCopiedText(context: Context, text: String) = withContext(Dispatchers.Default) {
        val clean = text.trim()
        if (clean.length !in 3..400) return@withContext

        IslandStateEngine.addClipboardEntry(clean)

        // 1. Direct URL Routing -> Queries real installed browsers/apps
        if (URL_REGEX.matches(clean)) {
            val url = if (clean.startsWith("http", ignoreCase = true)) clean else "https://$clean"
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val apps = resolveInstalledTargetApps(context, viewIntent)
            val appName = apps.firstOrNull()?.label ?: url.hostFromUrl()

            IslandStateEngine.submitPayload(
                IslandPayload.OriginAiSuggestion(
                    id = "origin_ai_url",
                    extractedText = clean,
                    actionTitle = appName,
                    primaryIntent = apps.firstOrNull()?.launchIntent ?: viewIntent,
                    targetApps = apps
                )
            )
            return@withContext
        }

        // 2. Direct Phone Number Routing -> Queries real installed Dialer
        if (PHONE_REGEX.matches(clean)) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val apps = resolveInstalledTargetApps(context, dialIntent)
            val appName = apps.firstOrNull()?.label ?: clean

            IslandStateEngine.submitPayload(
                IslandPayload.OriginAiSuggestion(
                    id = "origin_ai_phone",
                    extractedText = clean,
                    actionTitle = appName,
                    primaryIntent = apps.firstOrNull()?.launchIntent ?: dialIntent,
                    targetApps = apps
                )
            )
            return@withContext
        }

        // 3. On-Device ML Kit Entity Extraction for real street addresses & dates
        val extractor = EntityExtraction.getClient(
            EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build()
        )
        try {
            if (extractor.downloadModelIfNeeded().await() != null) {
                val annotations = extractor.annotate(clean).await()
                for (annotation in annotations) {
                    for (entity in annotation.entities) {
                        when (entity.type) {
                            Entity.TYPE_ADDRESS -> {
                                val mapIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("geo:0,0?q=${Uri.encode(annotation.annotatedText)}")
                                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                                val apps = resolveInstalledTargetApps(context, mapIntent)
                                IslandStateEngine.submitPayload(
                                    IslandPayload.OriginAiSuggestion(
                                        id = "origin_ai_address",
                                        extractedText = annotation.annotatedText,
                                        actionTitle = apps.firstOrNull()?.label ?: annotation.annotatedText,
                                        primaryIntent = apps.firstOrNull()?.launchIntent ?: mapIntent,
                                        targetApps = apps
                                    )
                                )
                                return@withContext
                            }

                            Entity.TYPE_DATE_TIME -> {
                                val calIntent = Intent(Intent.ACTION_INSERT).apply {
                                    data = CalendarContract.Events.CONTENT_URI
                                    putExtra(CalendarContract.Events.TITLE, clean.take(50))
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                val apps = resolveInstalledTargetApps(context, calIntent)
                                IslandStateEngine.submitPayload(
                                    IslandPayload.OriginAiSuggestion(
                                        id = "origin_ai_date",
                                        extractedText = annotation.annotatedText,
                                        actionTitle = apps.firstOrNull()?.label ?: annotation.annotatedText,
                                        primaryIntent = apps.firstOrNull()?.launchIntent ?: calIntent,
                                        targetApps = apps
                                    )
                                )
                                return@withContext
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            extractor.close()
        }

        // 4. General Copied Text -> Queries real sharing targets (WhatsApp, Gmail, Notes, etc.)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, clean)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val apps = resolveInstalledTargetApps(context, shareIntent)
        if (apps.isNotEmpty()) {
            IslandStateEngine.submitPayload(
                IslandPayload.OriginAiSuggestion(
                    id = "origin_ai_share",
                    extractedText = clean,
                    actionTitle = apps.first().label,
                    primaryIntent = apps.first().launchIntent,
                    targetApps = apps
                )
            )
        }
    }

    private fun String.hostFromUrl(): String {
        return try {
            Uri.parse(this).host ?: this
        } catch (_: Exception) {
            this
        }
    }
}