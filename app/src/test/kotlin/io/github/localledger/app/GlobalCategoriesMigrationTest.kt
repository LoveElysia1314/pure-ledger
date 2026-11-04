package io.github.localledger.app

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.zip.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class GlobalCategoriesMigrationTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val name="synthetic-global-categories.db"
    private val target="synthetic-global-categories-copy.db"
    private var store:LedgerStore?=null
    @Before fun setup(){context.deleteDatabase(name);context.deleteDatabase(target)}
    @After fun close(){store?.close();context.deleteDatabase(name);context.deleteDatabase(target)}
    private fun legacy(fail:Boolean=false) {
        object:SQLiteOpenHelper(context,name,null,8) {
            override fun onCreate(db:SQLiteDatabase) {
                db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY NOT NULL,name TEXT NOT NULL UNIQUE)")
                db.execSQL("INSERT INTO books VALUES(?, '日常'),('travel','旅行')",arrayOf(DEFAULT_BOOK))
                GlobalAccounts.create(db)
                db.execSQL("INSERT INTO accounts VALUES('cash','现金','10000',1,0)")
                db.execSQL("""CREATE TABLE categories(id TEXT PRIMARY KEY NOT NULL,book_id TEXT NOT NULL REFERENCES books(id),type TEXT NOT NULL,
                    name TEXT NOT NULL,icon TEXT NOT NULL,position INTEGER NOT NULL,hidden INTEGER NOT NULL,preset_key TEXT,UNIQUE(book_id,type,name))""")
                db.execSQL("INSERT INTO categories VALUES('a',?,'EXPENSE','三餐','meal',0,0,'EXPENSE:三餐')",arrayOf(DEFAULT_BOOK))
                db.execSQL("INSERT INTO categories VALUES('b','travel','EXPENSE','三餐','meal',0,1,'EXPENSE:三餐')")
                db.execSQL("INSERT INTO categories VALUES('renamed','travel','EXPENSE','午饭','cup',2,1,'EXPENSE:三餐')")
                db.execSQL("INSERT INTO categories VALUES('custom',?,'EXPENSE','午饭','gift',3,0,NULL)",arrayOf(DEFAULT_BOOK))
                db.execSQL("INSERT INTO categories VALUES('wine',?,'EXPENSE','烟酒（内置）','cup',4,0,'EXPENSE:烟酒')",arrayOf(DEFAULT_BOOK))
                LedgerSchema.entries(db,"entries");LedgerSchema.receipts(db,"recognition_receipts","entries");LedgerSchema.imports(db);SelectionSchema.create(db)
                db.execSQL("INSERT INTO entries VALUES(1,'first','EXPENSE',100,'合成一',1000,'备注一',100,200,?,'a','cash',NULL,0,0,1,'MANUAL','{}',7,0)",arrayOf(DEFAULT_BOOK))
                db.execSQL("INSERT INTO entries VALUES(2,'second','EXPENSE',200,'合成二',2000,'备注二',300,400,'travel','b','cash',NULL,0,0,1,'MANUAL','{}',9,0)")
                db.execSQL("INSERT INTO entries VALUES(3,'deleted','EXPENSE',300,'合成三',3000,'备注三',500,600,'travel','renamed',NULL,NULL,0,0,0,'MANUAL','{}',11,1)")
                db.execSQL("INSERT INTO selection_recent VALUES('travel','EXPENSE','b','cash',NULL,1,3)")
                db.execSQL("INSERT INTO merchant_choices VALUES('com.tencent.mm','合成二','travel','EXPENSE','b','cash',NULL,1)")
                val hash="a".repeat(64)
                db.execSQL("INSERT INTO import_receipts VALUES(?, ?, ?, ?, 2, '合成', 2)",arrayOf("$hash:0",hash,"b".repeat(64),"c".repeat(64)))
                if(fail)db.execSQL("CREATE TRIGGER fail_remap BEFORE UPDATE OF category_id ON entries BEGIN SELECT RAISE(ABORT,'synthetic remap failure'); END")
            }
            override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
        }.use { it.writableDatabase }
    }
    @Test fun realV8UpgradeMergesIdenticalCategoriesAndRetainsFinancialIdentityAndCustomSettings() {
        legacy();val db=LedgerStore(context,name).also { store=it }
        val snapshot=db.snapshot();val data=LedgerExchange.decode(db.exportExchange())
        assertEquals(9,db.readableDatabase.version);assertEquals(5,data.schemaVersion)
        assertEquals(2,snapshot.count);assertEquals(BigInteger("9700"),snapshot.catalog.accounts.single().balanceMinor)
        assertEquals(4,snapshot.catalog.categories.size)
        assertFalse(snapshot.catalog.categories.single { it.id=="a" }.hidden)
        assertEquals(listOf("a","a","renamed"),data.entries.sortedBy { it.id }.let { rows -> listOf("first","second","deleted").map { uid->rows.single{it.uid==uid}.categoryId } })
        assertEquals(listOf(7L,9L,11L),listOf("first","second","deleted").map { uid->data.entries.single{it.uid==uid}.revision })
        assertEquals(300L,data.entries.single{it.uid=="second"}.createdAtMs)
        assertEquals(400L,data.entries.single{it.uid=="second"}.updatedAtMs)
        assertTrue(data.entries.single{it.uid=="deleted"}.deleted);assertNull(data.entries.single{it.uid=="deleted"}.accountId)
        val renamed=data.categories.single{it.id=="renamed"};assertEquals("午饭",renamed.name);assertEquals("cup",renamed.icon);assertTrue(renamed.hidden)
        assertEquals("gift",data.categories.single{it.id=="custom"}.icon)
        assertEquals("烟酒",data.categories.single{it.id=="wine"}.name);assertEquals("wine",data.categories.single{it.id=="wine"}.icon)
        assertEquals("a",snapshot.selection.recent.single().choice.categoryId)
        assertEquals("a",db.merchantChoices().single().choice.categoryId)
        assertEquals("second",data.imports.single().entryUid)
        db.readableDatabase.rawQuery("PRAGMA table_info(categories)",null).use { c->while(c.moveToNext())assertNotEquals("book_id",c.getString(1)) }
        db.readableDatabase.rawQuery("PRAGMA foreign_key_check",null).use { assertEquals(0,it.count) }
    }
    @Test fun failedReferenceRemapRollsBackTheWholeV8Upgrade() {
        legacy(true);store=LedgerStore(context,name)
        try{store!!.snapshot();fail()}catch(_:android.database.SQLException){}
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY).use { db->
            assertEquals(8,db.version)
            db.rawQuery("SELECT category_id,amount_minor,revision FROM entries WHERE id=2",null).use { c->assertTrue(c.moveToFirst());assertEquals("b",c.getString(0));assertEquals(200L,c.getLong(1));assertEquals(9L,c.getLong(2)) }
            db.rawQuery("SELECT COUNT(*) FROM categories",null).use { it.moveToFirst();assertEquals(5,it.getInt(0)) }
            db.rawQuery("SELECT category_id FROM selection_recent",null).use { it.moveToFirst();assertEquals("b",it.getString(0)) }
        }
    }
    private fun v4(bytes:ByteArray,invalidBook:Boolean=false):ByteArray {
        val members=mutableMapOf<String,ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip->while(true){val e=zip.nextEntry ?: break;members[e.name]=zip.readBytes()} }
        val root=JSONObject(members.getValue("records.json").toString(Charsets.UTF_8))
        val categories=root.getJSONArray("categories")
        repeat(categories.length()){index->categories.getJSONObject(index).put("bookId",if(categories.getJSONObject(index).getString("id")=="renamed")"travel" else DEFAULT_BOOK)}
        val copy=JSONObject(categories.getJSONObject(0).toString())
        val original=(0 until categories.length()).map { categories.getJSONObject(it) }.single { it.getString("id")=="a" }
        copy.keys().asSequence().toList().forEach { copy.remove(it) };original.keys().asSequence().forEach { copy.put(it,original.get(it)) }
        copy.put("id","b").put("bookId",if(invalidBook)"missing" else "travel");categories.put(copy)
        root.getJSONArray("entries").let { rows->repeat(rows.length()){i->rows.getJSONObject(i).let { if(it.getString("uid")=="second")it.put("categoryId","b") }} }
        val records=root.toString().toByteArray(Charsets.UTF_8)
        val manifest=JSONObject(members.getValue("manifest.json").toString(Charsets.UTF_8)).put("schemaVersion",4).put("bytes",records.size)
            .put("sha256",MessageDigest.getInstance("SHA-256").digest(records).joinToString(""){"%02x".format(it)})
        return ByteArrayOutputStream().also { out->ZipOutputStream(out).use { zip->listOf("manifest.json" to manifest.toString().toByteArray(Charsets.UTF_8),"records.json" to records).forEach { (name,content)->zip.putNextEntry(ZipEntry(name));zip.write(content);zip.closeEntry() } } }.toByteArray()
    }
    @Test fun v4ExchangeUsesTheSameGlobalMappingAndCanBeImportedTwiceWithoutChangingMoney() {
        legacy();val source=LedgerStore(context,name).also { store=it };val bytes=source.exportExchange()
        val old=LedgerExchange.decode(v4(bytes))
        assertEquals(4,old.schemaVersion);assertEquals(4,old.categories.size)
        LedgerStore(context,target).use { destination->
            assertTrue(destination.previewExchange(old).conflicts.isEmpty());assertEquals(3,destination.commitExchange(old).added)
            val before=LedgerExchange.decode(destination.exportExchange())
            assertEquals(0,destination.commitExchange(old).added);assertEquals(before,LedgerExchange.decode(destination.exportExchange()))
            assertEquals(old.entries.map{it.copy(id=0)}.toSet(),before.entries.map{it.copy(id=0)}.toSet())
            assertEquals(source.snapshot().catalog.accounts,destination.snapshot().catalog.accounts)
        }
        try{LedgerExchange.decode(v4(bytes,true));fail()}catch(_:IllegalArgumentException){}
    }
}
