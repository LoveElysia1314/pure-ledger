package io.github.localledger.app

import android.app.LocaleManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.localledger.app.data.LedgerStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeBrandIdentityTest {
    companion object {
        private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
        private lateinit var app:LedgerApplication
        private lateinit var originalLocales:LocaleList
        private lateinit var database:String
        private fun shell(command:String)=instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use{reader->reader.readText().trim()}
        }
        @JvmStatic @BeforeClass fun prepare() {
            check(shell("getprop ro.boot.qemu.avd_name")=="LocalLedger_API35")
            app=instrumentation.targetContext.applicationContext as LedgerApplication
            originalLocales=app.getSystemService(LocaleManager::class.java).applicationLocales
            instrumentation.runOnMainSync { app.getSystemService(LocaleManager::class.java).applicationLocales=LocaleList.forLanguageTags("zh-Hans") }
            val deadline=android.os.SystemClock.uptimeMillis()+10_000
            while(app.resources.configuration.locales[0].script!="Hans") { check(android.os.SystemClock.uptimeMillis()<deadline);android.os.SystemClock.sleep(50) }
            database="brand-${System.nanoTime()}.db"
            instrumentation.runOnMainSync {
                AppStrings.initialize(app)
                app.javaClass.getDeclaredField("graph\$delegate").apply{isAccessible=true}.set(app,lazy{LedgerGraph(app,LedgerStore(app,database))})
            }
        }
        @JvmStatic @AfterClass fun cleanup() {
            if(::app.isInitialized) {
                instrumentation.runOnMainSync { app.graph.close();app.getSystemService(LocaleManager::class.java).applicationLocales=originalLocales;AppStrings.initialize(app) }
                app.deleteDatabase(database)
            }
        }
    }
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun click(text:String) { val node=compose.onNode(hasText(text) and hasClickAction());if(!node.isDisplayed())node.performScrollTo();node.performClick() }
    private fun capture(name:String) {
        compose.waitForIdle();android.os.SystemClock.sleep(300)
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        save(bitmap,name);bitmap.recycle()
    }
    private fun save(bitmap:Bitmap,name:String) {
        File(app.filesDir,"brand-review").apply{mkdirs()}.resolve("$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun systemIdentityAdaptiveAndThemedIconsAndAboutRemainReadableAtDoubleFont() {
        compose.waitUntil(15_000){!app.graph.loading.value && !app.graph.busy.value}
        val packageManager=app.packageManager
        assertEquals("纯净记账",packageManager.getApplicationLabel(app.applicationInfo).toString())
        val service=packageManager.getServiceInfo(ComponentName(app,io.github.localledger.app.accessibility.LedgerAccessibilityService::class.java),PackageManager.GET_META_DATA)
        assertEquals("纯净记账",service.loadLabel(packageManager).toString())
        val icon=app.applicationInfo.loadIcon(packageManager)
        assertTrue(icon is AdaptiveIconDrawable)
        val adaptive=icon as AdaptiveIconDrawable
        assertNotNull(adaptive.monochrome)
        val sheet=Bitmap.createBitmap(960,360,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(sheet);canvas.drawColor(Color.rgb(245,247,243))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(24,40,31);textSize=24f}
        listOf(48,96,144).forEachIndexed { index,size ->
            val left=48+index*170;icon.setBounds(left,72,left+size,72+size);icon.draw(canvas);canvas.drawText("${size}px",left.toFloat(),266f,paint)
        }
        val mono=requireNotNull(adaptive.monochrome).mutate().apply{setTint(Color.rgb(22,59,43))}
        val themed=AdaptiveIconDrawable(ColorDrawable(Color.rgb(205,232,214)),mono)
        themed.setBounds(620,72,764,216);themed.draw(canvas);canvas.drawText("Themed",620f,266f,paint)
        save(sheet,"system-icons");sheet.recycle()
        val font=shell("settings get system font_scale")
        try {
            compose.runOnIdle { app.graph.setAppearance(Appearance.DARK) }
            click("设置");click("关于")
            compose.onNodeWithText("纯净记账").assertIsDisplayed();compose.onNodeWithText("GPL-3.0-only").assertIsDisplayed()
            capture("about-dark")
            shell("settings put system font_scale 2.0")
            compose.waitUntil(15_000){app.resources.configuration.fontScale==2f}
            compose.onNodeWithText("纯净记账").assertIsDisplayed();compose.onNode(hasText("关闭") and hasClickAction()).assertIsDisplayed()
            capture("about-dark-font200");click("开源许可")
            compose.onNodeWithText("应用源码").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回").performClick();click("关闭")
        } finally { shell("settings put system font_scale $font") }
    }
}
