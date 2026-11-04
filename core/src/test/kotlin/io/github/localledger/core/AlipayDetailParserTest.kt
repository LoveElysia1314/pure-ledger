package io.github.localledger.core
import kotlin.test.*
class AlipayDetailParserTest {
 private val parser=PaymentDetailParser()
 private fun page()=PageSnapshot("com.eg.android.AlipayGphone",listOf("账单详情","合成店铺","支出26.80元","支付成功","银行卡立减金","-0.20","支付时间","2026-10-15 08:09:10","付款方式","合成银行卡","交易详情"),0)
 private fun recognized(p:PageSnapshot)=assertIs<RecognitionResult.Recognized>(parser.parse(p)).candidate
 @Test fun headerSeparatesActualPaymentFromDiscountAndDoesNotInventAnId() {
  val result=recognized(page());assertEquals(2680L,result.amount.minor);assertEquals("合成店铺",result.merchant);assertNull(result.externalId);assertNull(result.reliableKey)
  assertEquals(TransactionType.EXPENSE,result.type);ConfirmedEntry(result)
 }
 @Test fun expandedDetailsUseTheActualOrderNumberAndIgnoreMerchantOrderNumber() {
  val result=recognized(page().copy(texts=page().texts+listOf("订单号","SYNTHETIC-ALIPAY-0001","商家订单号","SYNTHETIC-MERCHANT-0001")))
  assertEquals("SYNTHETIC-ALIPAY-0001",result.externalId)
  assertNotEquals(result.reliableKey,recognized(page().copy(texts=page().texts+listOf("订单号","SYNTHETIC-ALIPAY-0002"))).reliableKey)
 }
 @Test fun missingRequiredFieldsAndFailedTransactionsAreNotBills() {
  for (p in listOf(page().copy(texts=page().texts.filterNot { it=="支付成功" }),page().copy(texts=page().texts.dropLast(5)),page().copy(texts=page().texts+listOf("退款")),page().copy(appPackage="other.app"))) assertIs<RecognitionResult.NoMatch>(parser.parse(p))
 }
 @Test fun multipleHeaderAmountsAndDuplicateOrderIdsAreRejected() {
  for(extra in listOf(listOf("收入2.00元"),listOf("订单号","SYNTHETIC-ALIPAY-0001","交易号","SYNTHETIC-ALIPAY-0002"))) assertIs<RecognitionResult.NoMatch>(parser.parse(page().copy(texts=page().texts+extra)))
 }
 @Test fun similarUnnumberedPaymentsAreSeparateEvidenceAndIncomeKeepsDirection() {
  val a=recognized(page());val b=recognized(page().copy(texts=page().texts.map{if(it=="2026-10-15 08:09:10") "2026-10-15 08:09:11" else it}));assertNotEquals(a.evidenceHash(),b.evidenceHash())
  assertEquals(TransactionType.INCOME,recognized(page().copy(texts=page().texts.map{if(it=="支出26.80元") "收入26.80元" else it})).type)
 }
}
