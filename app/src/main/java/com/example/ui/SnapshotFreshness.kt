package com.example.ui

/**
 * The line describing how fresh the analytics figures on screen are.
 *
 * A successful network response can still be a cached backend answer. The Apps
 * Script response cache preserves the payload's original
 * `generatedAtEpochMillis` on a cache hit and overwrites only
 * `serverDurationMs`, so this is the true age of the figures regardless of
 * which side served them. The screen previously showed an age only for its own
 * Room cache, which meant a backend cache hit rendered no age at all.
 *
 * Returns null when there is nothing truthful to say.
 */
internal fun snapshotFreshnessText(
    fromCache: Boolean,
    stale: Boolean,
    formattedUpdatedAt: String?,
): String? = when {
    fromCache || stale ->
        if (formattedUpdatedAt == null) "Showing saved data"
        else "Showing saved data from $formattedUpdatedAt"
    formattedUpdatedAt != null -> "Updated $formattedUpdatedAt"
    else -> null
}
