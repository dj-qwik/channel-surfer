package com.channelsurf.core.schedule

import com.channelsurf.core.archive.UrlPolicy
import com.channelsurf.core.model.EraPolicy
import com.channelsurf.core.model.MediaAsset
import com.channelsurf.core.model.ReviewPolicy
import com.channelsurf.core.model.RightsStatus

/**
 * Editorial eligibility for linear broadcast. Health is NOT part of eligibility: an unhealthy asset keeps
 * its slot and is substituted at tune time, so one bad file never shifts a channel's timeline.
 */
object Eligibility {
    fun reasons(
        asset: MediaAsset,
        era: EraPolicy,
        allowTestOnly: Boolean = false,
        extraApprovedHosts: Set<String> = emptySet(),
        policy: ReviewPolicy = ReviewPolicy.MANUAL,
    ): List<String> = buildList {
        when (asset.rightsStatus) {
            RightsStatus.CLEARED -> Unit
            RightsStatus.TEST_ONLY -> if (!allowTestOnly) add("Test-only media (debug builds only)")
            RightsStatus.REVIEW_REQUIRED -> if (policy == ReviewPolicy.MANUAL) add("Rights review required")
            RightsStatus.BLOCKED -> add("Blocked / taken down")
        }
        if (policy == ReviewPolicy.AUTOMATIC) {
            // No manual sign-off: only a detected out-of-era year excludes the item.
            asset.broadcastYear?.let { y -> if (!era.contains(y)) add("Broadcast year $y is outside ${era.minYear}–${era.maxYear}") }
        } else if (asset.rightsStatus != RightsStatus.TEST_ONLY) {
            val year = asset.broadcastYear
            when {
                year == null -> add("Broadcast year unknown")
                !asset.yearVerified -> add("Broadcast year not verified")
                !era.contains(year) -> add("Broadcast year $year is outside ${era.minYear}–${era.maxYear}")
            }
        }
        if (!asset.seekable) add("Not seekable (cannot join mid-program)")
        if (asset.playDurationMs <= 0) add("Unknown or zero duration")
        if (!UrlPolicy.isAllowed(asset.url, extraApprovedHosts)) add("Media host is not approved")
    }

    fun isEligible(
        asset: MediaAsset,
        era: EraPolicy,
        allowTestOnly: Boolean = false,
        extraApprovedHosts: Set<String> = emptySet(),
        policy: ReviewPolicy = ReviewPolicy.MANUAL,
    ) = reasons(asset, era, allowTestOnly, extraApprovedHosts, policy).isEmpty()
}
