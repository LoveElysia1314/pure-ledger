package io.github.localledger.app
import android.content.Context
import io.github.localledger.app.data.*
import io.github.localledger.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[34])
class UnnumberedRecognitionTest {
 private lateinit var store:LedgerStore
 private lateinit var context:Context
 private val name="synthetic-unnumbered.db"
 private val source=TransactionCandidate("com.eg.android.AlipayGphone",TransactionType.EXPENSE,Money(2680),"合成商户",null,"2026-10-15 08:09:10","alipay-bill-v1",java.time.Instant.parse("2026-10-15T00:09:10Z").toEpochMilli())
 @Before fun setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase(name);store=LedgerStore(context,name)}
 @After fun close(){store.close();context.deleteDatabase(name);context.deleteDatabase("synthetic-unnumbered-copy.db")}
 @Test fun requestsRetryWithoutInventingReceiptsAndRepeatOnlyMarksThePopup(){
  val entry=ConfirmedEntry(source)
  assertFalse(store.prepareCandidate(source).repeated)
  val saved=store.saveCandidate(entry,"request-1");assertEquals(saved,store.saveCandidate(entry,"request-1"));assertEquals(1,store.snapshot().count)
  assertTrue(store.prepareCandidate(source).repeated)
  store.saveCandidate(entry,"request-2");assertEquals(2,store.snapshot().count)
  val data=LedgerExchange.decode(store.exportExchange());assertTrue(data.recognition.isEmpty());assertEquals(2,data.entries.size)
  LedgerStore(context,"synthetic-unnumbered-copy.db").use{copy->copy.commitExchange(data);assertTrue(copy.prepareCandidate(source).repeated);assertEquals(0,copy.commitExchange(data).added)}
  val row=store.snapshot().rows.first();store.deleteEntry(row.id,row.revision);assertTrue(store.prepareCandidate(source).repeated)
 }
 @Test fun receiptFailureRollsBackTheNumberedSaveAndAnUnnumberedRequestCannotChangeEvidence(){
  store.saveCandidate(ConfirmedEntry(source),"request-1")
  try{store.saveCandidate(ConfirmedEntry(source.copy(occurredAtMs=source.occurredAtMs!!+1000)),"request-1");fail()}catch(_:IllegalArgumentException){}
  store.writableDatabase.execSQL("CREATE TRIGGER fail_receipt BEFORE INSERT ON recognition_receipts BEGIN SELECT RAISE(ABORT,'synthetic'); END")
  try{store.saveCandidate(ConfirmedEntry(source.copy(externalId="SYNTHETIC-NUMBER-1")),"request-2");fail()}catch(_:android.database.SQLException){}
  assertEquals(1,store.snapshot().count)
 }
}
