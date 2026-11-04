package io.github.localledger.app

enum class CategoryDisplay(private val titleRes: Int) {
    AUTO(R.string.ui_layout_auto), GRID(R.string.ui_layout_grid), LIST(R.string.ui_layout_list);
    val title: String get()=appString(titleRes)
    fun resolve(language: String): CategoryDisplay = if(this!=AUTO)this else if(language=="en")LIST else GRID
    companion object {
        fun read(value: String?)=entries.find { it.name==value } ?: AUTO
    }
}
