package com.lightning.olympus

import spray.json._
import scala.concurrent.duration._
import com.lightning.olympus.Utils._
import com.lightning.olympus.JsonHttpUtils._
import com.lightning.wallet.lnutils.ImplicitJsonFormats._

import rx.lang.scala.{Observable => Obs}
import com.lightning.wallet.ln.{PaymentRequest, Tools}
import scala.collection.JavaConverters.mapAsJavaMapConverter
import com.github.kevinsawicki.http.HttpRequest
import rx.lang.scala.schedulers.IOScheduler
import scala.language.implicitConversions
import fr.acinq.bitcoin.Crypto.PublicKey
import wf.bitcoin.javabitcoindrpcclient
import fr.acinq.bitcoin.BinaryData
import org.bitcoinj.core.Utils.HEX
import java.math.BigInteger


object Utils {
  var values: Vals = _
  type StringSet = Set[String]
  type StringVec = Vector[String]

  val hex2Ascii: String => String = raw => new String(HEX decode raw, "UTF-8")
  lazy val bitcoin = new javabitcoindrpcclient.BitcoinJSONRPCClient(values.btcApi)
  implicit def string2PublicKey(raw: String): PublicKey = PublicKey(BinaryData apply raw)
  implicit def arg2Apply[T](argument: T): ArgumentRunner[T] = new ArgumentRunner(argument)
  class ArgumentRunner[T](wrap: T) { def >>[V](fs: (T => V)*): Seq[V] = for (fun <- fs) yield fun apply wrap }
  def extract[T](src: Map[String, String], fn: String => T, args: String*): Seq[T] = args.map(src andThen fn)
  def errLog: PartialFunction[Throwable, Unit] = { case err => Tools log err.getMessage }
}

object JsonHttpUtils {
  def initDelay[T](next: Obs[T], startMillis: Long, timeoutMillis: Long) = {
    val adjustedTimeout = startMillis + timeoutMillis - System.currentTimeMillis
    val delayLeft = if (adjustedTimeout < 0L) 0L else adjustedTimeout
    Obs.just(null).delay(delayLeft.millis).flatMap(_ => next)
  }

  def obsOnIO = Obs just null subscribeOn IOScheduler.apply
  def retry[T](obs: Obs[T], pick: (Throwable, Int) => Duration, times: Range) =
    obs.retryWhen(_.zipWith(Obs from times)(pick) flatMap Obs.timer)

  def to[T : JsonFormat](raw: String): T = raw.parseJson.convertTo[T]
  def pickInc(error: Throwable, next: Int) = next.seconds
}

// k is session private key, a source for signerR, tokens is a list of unsigned blind BigInts from client
case class BlindData(paymentHash: BinaryData, id: String, k: BigInteger, tokens: StringVec)

case class CacheItem[T](data: T, stamp: Long)
case class Vals(privKey: String, btcApi: String, zmqApi: String, eclairSockIp: String,
                eclairSockPort: Int, eclairNodeId: String, rewindRange: Int, ip: String,
                paymentProvider: PaymentProvider, minChannels: Int) {

  lazy val bigIntegerPrivKey = new BigInteger(privKey)
  lazy val eclairNodePubKey = PublicKey(eclairNodeId)
}

trait PaymentProvider {
  def isPaid(data: BlindData): Boolean
  def generateInvoice: Charge

  val quantity: Int
  val priceMsat: Long
  val description: String
  val url: String
}

case class Charge(paymentHash: String, id: String,
                  paymentRequest: String, paid: Boolean)

case class StrikeProvider(priceMsat: Long, quantity: Int, description: String,
                          url: String, privKey: String) extends PaymentProvider {

  def generateInvoice =
    to[Charge](HttpRequest.post(url).trustAllCerts.form(Map("amount" -> priceMsat.toString,
      "currency" -> "btc", "description" -> "payment").asJava).connectTimeout(10000).basic(privKey, "").body)

  def isPaid(data: BlindData) =
    to[Charge](HttpRequest.get(url + "/" + data.id).trustAllCerts
      .connectTimeout(10000).basic(privKey, "").body).paid
}

case class EclairProvider(priceMsat: Long, quantity: Int, description: String,
                          url: String, pass: String) extends PaymentProvider {

  def request =
    HttpRequest.post(url).basic("eclair-cli", pass)
      .contentType("application/json").connectTimeout(5000)

  def generateInvoice = {
    val content = s"""{ "params": [$priceMsat, "$description"], "method": "receive" }"""
    val rawPr = request.send(content).body.parseJson.asJsObject.fields("result").convertTo[String]

    val pr = PaymentRequest read rawPr
    val payHash = pr.paymentHash.toString
    Charge(payHash, payHash, rawPr, paid = false)
  }

  def isPaid(data: BlindData) = {
    val paymentHash = data.paymentHash.toString
    val content = s"""{ "params": ["$paymentHash"], "method": "checkpayment" }"""
    request.send(content).body.parseJson.asJsObject.fields("result").convertTo[Boolean]
  }
}