package io.github.localledger.app

enum class Appearance(private val titleRes: Int) {
    SYSTEM(R.string.ui_system_default), LIGHT(R.string.ui_light), DARK(R.string.ui_dark);
    val title: String get() = appString(titleRes)
    fun isDark(systemDark: Boolean) = when (this) { SYSTEM -> systemDark; LIGHT -> false; DARK -> true }
    companion object {
        fun read(value: String?) = entries.find { it.name == value } ?: SYSTEM
    }
}
