package com.kgc.island2.ai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.net.toUri
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

    fun resolveInstalledTargetApps(context: Context, baseIntent: Intent, maxCount: Int = 5): List<TargetAppInfo> {
        return try {
            val pm = context.packageManager
            val resolveInfos = pm.queryIntentActivities(baseIntent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfos
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
                        label = info.loadLabel(pm).toString(),
                        packageName = pkg,
                        activityName = cls,
                        icon = info.loadIcon(pm),
                        launchIntent = specificIntent
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun processCopiedText(context: Context, text: String) = withContext(Dispatchers.Default) {
        val clean = text.trim()
        if (clean.length !in 3..400) return@withContext

        IslandStateEngine.recordClipboard(clean)

        // 1. Direct URL Fast-Path
        if (URL_REGEX.matches(clean)) {
            val url = if (clean.startsWith("http", ignoreCase = true)) clean else "https://$clean"
            val viewIntent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val apps = resolveInstalledTargetApps(context, viewIntent)
            val appName = apps.firstOrNull()?.label ?: url.hostFromUrl()

            IslandStateEngine.submitPayload(
                IslandPayload.OriginAiSuggestion(
                    id = "origin_ai_url",
                    actionTitle = appName,
                    extractedText = clean,
                    targetApps = apps,
                    primaryIntent = apps.firstOrNull()?.launchIntent ?: viewIntent
                )
            )
            return@withContext
        }

        // 2. Direct Phone Number Fast-Path
        if (PHONE_REGEX.matches(clean)) {
            val dialIntent = Intent(Intent.ACTION_DIAL, "tel:$clean".toUri()).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val apps = resolveInstalledTargetApps(context, dialIntent)
            val appName = apps.firstOrNull()?.label ?: clean

            IslandStateEngine.submitPayload(
                IslandPayload.OriginAiSuggestion(
                    id = "origin_ai_phone",
                    actionTitle = appName,
                    extractedText = clean,
                    targetApps = apps,
                    primaryIntent = apps.firstOrNull()?.launchIntent ?: dialIntent
                )
            )
            return@withContext
        }

        // 3. Lazy On-Device ML Kit Entity Extraction
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
                                val encoded = android.net.Uri.encode(annotation.annotatedText)
                                val mapIntent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=$encoded".toUri()).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                val apps = resolveInstalledTargetApps(context, mapIntent)
                                IslandStateEngine.submitPayload(
                                    IslandPayload.OriginAiSuggestion(
                                        id = "origin_ai_address",
                                        actionTitle = apps.firstOrNull()?.label ?: annotation.annotatedText,
                                        extractedText = annotation.annotatedText,
                                        targetApps = apps,
                                        primaryIntent = apps.firstOrNull()?.launchIntent ?: mapIntent
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
                                        actionTitle = apps.firstOrNull()?.label ?: annotation.annotatedText,
                                        extractedText = annotation.annotatedText,
                                        targetApps = apps,
                                        primaryIntent = apps.firstOrNull()?.launchIntent ?: calIntent
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

        // 4. General Copied Text Share Targets
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
                    actionTitle = apps.first().label,
                    extractedText = clean,
                    targetApps = apps,
                    primaryIntent = apps.first().launchIntent
                )
            )
        }
    }

    private fun String.hostFromUrl(): String {
        return try {
            this.toUri().host ?: this
        } catch (_: Exception) {
            this
        }
    }
}