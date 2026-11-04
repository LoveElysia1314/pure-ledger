package io.github.localledger.core

/** Public semantic versions are independent of Android's monotonically increasing installation code. */
data class AppVersion private constructor(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    val sortKey: Int get() = (major.toLong() * 1_000_000 + minor * 10_000 + patch * 100).toInt()
    override fun compareTo(other: AppVersion): Int = sortKey.compareTo(other.sortKey)
    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(tag: String): AppVersion? {
            val match = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]?)\\.(0|[1-9][0-9]?)").matchEntire(tag) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toInt()
            val patch = match.groupValues[3].toInt()
            if (major.toLong() * 1_000_000 + minor * 10_000 + patch * 100 > 2_100_000_000) return null
            return AppVersion(major, minor, patch)
        }
    }
}
