package com.btcontract.lncloud

import org.json4s.jackson.JsonMethods._
import com.btcontract.lncloud.ln.wire._

import org.slf4j.{Logger, LoggerFactory}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import rx.lang.scala.{Scheduler, Observable => Obs}
import com.btcontract.lncloud.Utils.{ListStr, OptString}
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

import wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient
import com.btcontract.lncloud.crypto.RandomGenerator
import language.implicitConversions
import org.bitcoinj.core.Utils.HEX
import java.math.BigInteger


object Utils {
  type Bytes = Array[Byte]
  type ListStr = List[String]
  type OptString = Option[String]
  type BinaryDataList = List[BinaryData]
  type LightningMessages = List[LightningMessage]

  var values: Vals = _
  implicit val formats = org.json4s.DefaultFormats
  lazy val bitcoin = new BitcoinJSONRPCClient(values.rpcUrl)
  val hex2Json: String => String = raw => new String(HEX decode raw, "UTF-8")
  val logger: Logger = LoggerFactory getLogger "LNCloud"
  val random = new RandomGenerator
  val twoHours = 7200000

  implicit def str2BigInteger(bigInt: String): BigInteger = new BigInteger(bigInt)
  implicit def arg2Apply[T](argument: T): ArgumentRunner[T] = new ArgumentRunner(argument)
  class ArgumentRunner[T](wrap: T) { def >>[V](fs: (T => V)*): Seq[V] = for (fun <- fs) yield fun apply wrap }
  def extract[T](src: Map[String, String], fn: String => T, args: String*): Seq[T] = args.map(src andThen fn)
  def errLog: PartialFunction[Throwable, Unit] = { case err: Throwable => logger info err.getMessage }
  def toClass[T : Manifest](raw: String): T = parse(raw, useBigDecimalForDouble = true).extract[T]
  def none: PartialFunction[Any, Unit] = { case _ => }

  def fromShortId(id: Long): (Int, Int, Int) = {
    val blockNumber = id.>>(40).&(0xFFFFFF).toInt
    val txOrd = id.>>(16).&(0xFFFFFF).toInt
    val outOrd = id.&(0xFFFF).toInt
    (blockNumber, txOrd, outOrd)
  }

  def toShortId(blockHeight: Int, txIndex: Int, outputIndex: Int): Long =
    blockHeight.&(0xFFFFFFL).<<(40) | txIndex.&(0xFFFFFFL).<<(16) | outputIndex.&(0xFFFFL)
}

object JsonHttpUtils {
  def obsOn[T](provider: => T, scheduler: Scheduler): Obs[T] =
    Obs.just(null).subscribeOn(scheduler).map(_ => provider)

  type IntervalPicker = (Throwable, Int) => Duration
  def pickInc(err: Throwable, next: Int): FiniteDuration = next.seconds
  def retry[T](obs: Obs[T], pick: IntervalPicker, times: Range): Obs[T] =
    obs.retryWhen(_.zipWith(Obs from times)(pick) flatMap Obs.timer)
}

object Features {
  sealed trait FeatureFlag
  case object Unset extends FeatureFlag
  case object Mandatory extends FeatureFlag
  case object Optional extends FeatureFlag

  def readFeature(features: BinaryData, mandatory: Int): FeatureFlag = {
    require(mandatory % 2 == 0, "Mandatory feature index bit must be even")
    val bitset = java.util.BitSet.valueOf(features)
    val optional = bitset.get(mandatory + 1)
    val obligatory = bitset.get(mandatory)

    val notBothAtOnce = !(optional & obligatory)
    require(notBothAtOnce, s"Both feature bits set at index=$mandatory")
    if (obligatory) Mandatory else if (optional) Optional else Unset
  }

  def channelPublic(features: BinaryData): FeatureFlag = readFeature(features, 0)
  def initialRoutingSync(features: BinaryData): FeatureFlag = readFeature(features, 2)
  def areFeaturesCompatible(localLocalFeatures: BinaryData, remoteLocalFeatures: BinaryData): Boolean = {
    val (localPublic, remotePublic) = (Features channelPublic localLocalFeatures, Features channelPublic remoteLocalFeatures)
    val incompatible = (localPublic == Mandatory && remotePublic == Unset) || (localPublic == Unset && remotePublic == Mandatory)
    !incompatible
  }
}

// k is session private key, a source for signerR
// tokens is a list of yet unsigned blind BigInts from client
case class BlindData(tokens: ListStr, k: String)
case class CacheItem[T](data: T, stamp: Long)

case class Invoice(message: Option[String], nodeId: BinaryData, sum: MilliSatoshi, paymentHash: BinaryData)
case class Vals(privKey: BigInt, price: MilliSatoshi, quantity: Int, rpcUrl: String, zmqPoint: String, rewindRange: Int)

object Invoice {
  def serialize(inv: Invoice): String = {
    val hash = inv.paymentHash.toString
    val node = inv.nodeId.toString
    val sum = inv.sum.amount
    s"$node:$sum:$hash"
  }

  def parse(raw: String): Invoice = {
    val Array(node, sum, hash) = raw.split(':')
    Invoice(None, node, MilliSatoshi(sum.toLong), hash)
  }
}