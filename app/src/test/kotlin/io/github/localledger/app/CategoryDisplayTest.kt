package io.github.localledger.app

import androidx.compose.ui.unit.dp
import io.github.localledger.app.ui.categoryColumns
import org.junit.Assert.*
import org.junit.Test

class CategoryDisplayTest {
    @Test fun automaticUsesLanguageWhileExplicitChoiceSurvivesLanguageChanges() {
        assertEquals(CategoryDisplay.LIST,CategoryDisplay.AUTO.resolve("en"))
        for(language in listOf("zh","ja"))assertEquals(CategoryDisplay.GRID,CategoryDisplay.AUTO.resolve(language))
        for(language in listOf("en","zh","ja")) {
            assertEquals(CategoryDisplay.GRID,CategoryDisplay.GRID.resolve(language))
            assertEquals(CategoryDisplay.LIST,CategoryDisplay.LIST.resolve(language))
        }
        assertEquals(CategoryDisplay.AUTO,CategoryDisplay.read("obsolete"))
    }
    @Test fun columnAllocationAdaptsToVocabularyAndWidthWithoutForcingTwoTinyTargets() {
        assertEquals(1,categoryColumns(96.dp,false,listOf("Food")))
        assertTrue(categoryColumns(360.dp,false,listOf("Food"),CategoryDisplay.LIST)<categoryColumns(360.dp,false,listOf("三餐")))
        assertEquals(6,categoryColumns(1200.dp,false,listOf("Food"),CategoryDisplay.LIST))
        assertTrue(categoryColumns(360.dp,true,listOf("Communication"),CategoryDisplay.LIST)<categoryColumns(360.dp,false,listOf("Communication"),CategoryDisplay.LIST))
    }
}
