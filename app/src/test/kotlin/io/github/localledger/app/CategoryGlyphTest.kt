package io.github.localledger.app

import androidx.compose.ui.graphics.vector.VectorPath
import io.github.localledger.app.ui.CategoryGlyphs
import io.github.localledger.core.CategoryIcons
import org.junit.Assert.*
import org.junit.Test

class CategoryGlyphTest {
    @Test fun everyPersistedIconHasDistinctNonemptyGeometryAndUnknownKeysUseTheFallback() {
        assertEquals(CategoryIcons.keys, CategoryGlyphs.keys)
        val shapes = CategoryGlyphs.keys.map { key ->
            val paths = CategoryGlyphs[key].root.map { (it as VectorPath).pathData }
            assertTrue(paths.isNotEmpty() && paths.all { it.isNotEmpty() })
            paths
        }
        assertEquals(CategoryIcons.keys.size, shapes.distinct().size)
        assertSame(CategoryGlyphs["grid"], CategoryGlyphs["unknown-future-icon"])
    }
}
