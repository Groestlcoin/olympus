package com.lightning.wallet.ln

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Bech32._
import fr.acinq.eclair.crypto.BitStream._
import com.lightning.wallet.ln.PaymentRequest._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.crypto.BitStream
import java.nio.ByteOrder.BIG_ENDIAN
import java.math.BigInteger
import scala.util.Try


// Tags should be outside of PaymentRequest
// object for serialization purposes

sealed trait Tag {
  def toInt5s: Int5Seq
  def encode(ints: Int5Seq, v: Char): Int5Seq =
    Seq(Bech32 map v, (ints.length / 32).toByte,
      (ints.length % 32).toByte) ++ ints
}

case class PaymentHashTag(hash: BinaryData) extends Tag {
  def toInt5s: Int5Seq = encode(Bech32 eight2five hash, 'p')
}

case class DescriptionTag(description: String) extends Tag {
  def toInt5s = encode(Bech32 eight2five description.getBytes("UTF-8"), 'd')
}

case class DescriptionHashTag(hash: BinaryData) extends Tag {
  def toInt5s: Int5Seq = encode(Bech32 eight2five hash, 'h')
}

case class RoutingInfoTag(pubkey: PublicKey, channelId: BinaryData, fee: Long, cltvExpiryDelta: Int) extends Tag {
  private val feeAndDelta = Protocol.writeUInt64(fee, BIG_ENDIAN) ++ Protocol.writeUInt16(cltvExpiryDelta, BIG_ENDIAN)
  def toInt5s: Int5Seq = encode(Bech32.eight2five(pubkey.toBin ++ channelId.data ++ feeAndDelta), 'r')
  def shortChannelId: Long = Protocol.uint64(channelId, BIG_ENDIAN)
}

case class ExpiryTag(seconds: Long) extends Tag {
  override def toInt5s: Int5Seq = Seq(Bech32 map 'x', 0.toByte,
    2.toByte, (seconds / 32).toByte, (seconds % 32).toByte)
}

case class FallbackAddressTag(version: Byte, hash: BinaryData) extends Tag {
  def toInt5s: Int5Seq = encode(version +: Bech32.eight2five(hash), 'f')
}

object FallbackAddressTag {
  def apply(address: String): FallbackAddressTag = {
    // A Bitcoin fallack address MUST always be present
    val try32 = Try apply fromBech32Address(address)
    val try58 = Try apply fromBase58Address(address)
    try32.orElse(try58).get
  }

  def fromBase58Address(address: String) = Base58Check decode address match {
    case (Base58.Prefix.PubkeyAddressTestnet, hash) => FallbackAddressTag(17, hash)
    case (Base58.Prefix.ScriptAddressTestnet, hash) => FallbackAddressTag(18, hash)
    case (Base58.Prefix.PubkeyAddress, hash) => FallbackAddressTag(17, hash)
    case (Base58.Prefix.ScriptAddress, hash) => FallbackAddressTag(18, hash)
  }

  def fromBech32Address(address: String): FallbackAddressTag = {
    val (prefix: Int5, hash) = Bech32 decodeWitnessAddress address
    FallbackAddressTag(prefix, hash)
  }
}

case class PaymentRequest(prefix: String, amount: Option[MilliSatoshi], timestamp: Long,
                          nodeId: PublicKey, tags: Vector[Tag], signature: BinaryData) {

  def isFresh: Boolean = {
    val expiry = tags.collectFirst { case ex: ExpiryTag => ex.seconds }
    timestamp + expiry.getOrElse(3600L) > System.currentTimeMillis / 1000L
  }

  def routingInfo: Vector[RoutingInfoTag] = tags.collect { case t: RoutingInfoTag => t }
  def paymentHash = tags.collectFirst { case p: PaymentHashTag => p.hash }.get

  def description = tags.collectFirst {
    case DescriptionHashTag(hash) => "hash " + hash.toString
    case DescriptionTag(textDescription) => textDescription
  }.get

  def fallbackAddress: Option[String] = tags.collectFirst {
    case FallbackAddressTag(17, hash) if prefix == "lnbc" => Base58Check.encode(Base58.Prefix.PubkeyAddress, hash)
    case FallbackAddressTag(18, hash) if prefix == "lnbc" => Base58Check.encode(Base58.Prefix.ScriptAddress, hash)
    case FallbackAddressTag(17, hash) if prefix == "lntb" => Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, hash)
    case FallbackAddressTag(18, hash) if prefix == "lntb" => Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, hash)
    case FallbackAddressTag(version, hash) if prefix == "lnbc" => Bech32.encodeWitnessAddress("bc", version, hash)
    case FallbackAddressTag(version, hash) if prefix == "lntb" => Bech32.encodeWitnessAddress("tb", version, hash)
  }

  def stream: BitStream = {
    val stream = BitStream.empty
    val int5s = Timestamp.encode(timestamp) ++ tags.flatMap(_.toInt5s)
    val stream1 = int5s.foldLeft(stream)(PaymentRequest.write5)
    stream1
  }

  def hash: BinaryData = {
    val base = prefix + Amount.encode(amount)
    Crypto.sha256(base.getBytes("UTF-8") ++ stream.bytes)
  }

  def sign(priv: PrivateKey) = {
    val (r, s) = Crypto.sign(hash, priv)
    val (pub1, _) = Crypto.recoverPublicKey(r -> s, hash)
    val recid = if (nodeId == pub1) 0.toByte else 1.toByte
    val signature1 = Signature.encode(r, s, recid)
    copy(signature = signature1)
  }
}

object PaymentRequest {
  type Int5Seq = Seq[Int5]

  def apply(chain: BinaryData, amount: Option[MilliSatoshi], paymentHash: BinaryData, privateKey: PrivateKey,
            description: String, fallbackAddress: Option[String], expirySeconds: Long): PaymentRequest = {

    val tags = PaymentHashTag(paymentHash) :: DescriptionTag(description) :: ExpiryTag(expirySeconds) :: Nil
    PaymentRequest(getPrefix(chain), amount, System.currentTimeMillis / 1000L, privateKey.publicKey,
      tags.toVector, BinaryData.empty) sign privateKey
  }

  def getPrefix(chain: BinaryData) = chain match {
    case Block.RegtestGenesisBlock.blockId => "lntb"
    case Block.TestnetGenesisBlock.blockId => "lntb"
    case Block.LivenetGenesisBlock.blockId => "lnbc"
  }

  object Amount {
    // Shortest representation possible
    def unit(sum: MilliSatoshi): Char = sum match {
      case MilliSatoshi(pico) if pico * 10 % 1000 > 0 => 'p'
      case MilliSatoshi(pico) if pico * 10 % 1000000 > 0 => 'n'
      case MilliSatoshi(pico) if pico * 10 % 1000000000 > 0 => 'u'
      case _ => 'm'
    }

    type AmountOption = Option[MilliSatoshi]
    def decode(input: String): AmountOption = input.lastOption match {
      case Some('p') => Some(MilliSatoshi apply input.dropRight(1).toLong / 10)
      case Some('n') => Some(MilliSatoshi apply input.dropRight(1).toLong * 100)
      case Some('u') => Some(MilliSatoshi apply input.dropRight(1).toLong * 100000)
      case Some('m') => Some(MilliSatoshi apply input.dropRight(1).toLong * 100000000)
      case _ => None
    }

    def encode(amt: AmountOption): String = amt match {
      case Some(sum) if unit(sum) == 'p' => s"${sum.amount * 10}p"
      case Some(sum) if unit(sum) == 'n' => s"${sum.amount / 100}n"
      case Some(sum) if unit(sum) == 'u' => s"${sum.amount / 100000}u"
      case Some(sum) if unit(sum) == 'm' => s"${sum.amount / 100000000}m"
      case _ => ""
    }
  }

  object Timestamp {
    def decode(data: Int5Seq): Long = data.take(7).foldLeft(0L) { case (a, b) => a * 32 + b }
    def encode(timestamp: Long, acc: Int5Seq = Nil): Int5Seq = if (acc.length == 7) acc else
      encode(timestamp / 32, (timestamp % 32).toByte +: acc)
  }

  object Signature {
    def decode(signature: BinaryData) = {
      require(signature.length == 65, "Invalid signature length")
      val s = new BigInteger(1, signature.slice(32, 64).toArray)
      val r = new BigInteger(1, signature.take(32).toArray)
      val recid = signature.last
      (r, s, recid)
    }

    def encode(r: BigInteger, s: BigInteger, recid: Byte) = {
      val rEncoded = Crypto fixSize r.toByteArray.dropWhile(0.==)
      val sEncoded = Crypto fixSize s.toByteArray.dropWhile(0.==)
      rEncoded.data ++ sEncoded.data :+ recid
    }
  }

  object Tag {
    def parse(input: Int5Seq): Tag = {
      val len = input(1) * 32 + input(2)

      input.head match {
        case pTag if pTag == Bech32.map('p') =>
          val hash = Bech32 five2eight input.slice(3, 52 + 3)
          PaymentHashTag(hash)

        case dTag if dTag == Bech32.map('d') =>
          val description = Bech32 five2eight input.slice(3, len + 3)
          val text = new String(description.toArray, "UTF-8")
          DescriptionTag(text)

        case hTag if hTag == Bech32.map('h') =>
          val hash = Bech32 five2eight input.slice(3, len + 3)
          DescriptionHashTag(hash)

        case fTag if fTag == Bech32.map('f') =>
          val prog = Bech32 five2eight input.slice(4, len - 1 + 4)
          val version = input(3)

          version match {
            case v if v >= 0 && v <= 16 => FallbackAddressTag(version, prog)
            case 17 | 18 => FallbackAddressTag(version, prog)
          }

        case rTag if rTag == Bech32.map('r') =>
          val data = Bech32 five2eight input.slice(3, len + 3)
          val fee = Protocol.uint64(data.drop(33 + 8), BIG_ENDIAN)
          val cltv = Protocol.uint16(data.drop(33 + 8 + 8), BIG_ENDIAN)
          RoutingInfoTag(PublicKey(data take 33), data.slice(33, 33 + 8), fee, cltv)

        case xTag if xTag == Bech32.map('x') =>
          require(len == 2, s"Invalid length for expiry tag")
          val expiry = 32  * input(3) + input(4)
          ExpiryTag(expiry)
      }
    }
  }

  def toBits(value: Int5): Seq[Bit] =
    Seq(elems = (value & 16) != 0, (value & 8) != 0,
      (value & 4) != 0, (value & 2) != 0, (value & 1) != 0)

  def write5(stream: BitStream, value: Int5): BitStream =
    stream writeBits toBits(value)

  def read5(stream: BitStream) = {
    val (stream1, bits) = stream readBits 5

    val b0 = if (bits.head) 1 << 4 else 0
    val b1 = if (bits apply 1) 1 << 3 else 0
    val b2 = if (bits apply 2) 1 << 2 else 0
    val b3 = if (bits apply 3) 1 << 1 else 0
    val b4 = if (bits apply 4) 1 << 0 else 0
    val value = b0 + b1 + b2 + b3 + b4
    (stream1, (value & 0xff).toByte)
  }

  def toInt5s(stream: BitStream, acc: Int5Seq = Nil): Int5Seq =
    if (stream.bitCount == 0) acc else {
      val (stream1, value) = read5(stream)
      toInt5s(stream1, acc :+ value)
    }

  def read(input: String): PaymentRequest = {
    def loop(data: Int5Seq, tags: Seq[Int5Seq] = Nil): Seq[Int5Seq] =

      if (data.isEmpty) tags else {
        // 104 is the size of a signature
        val len = 1 + 2 + 32 * data(1) + data(2)
        val tags1 = tags :+ data.take(len)
        loop(data drop len, tags1)
      }

    val (hrp, data) = Bech32 decode input
    val stream = data.foldLeft(BitStream.empty)(write5)
    require(stream.bitCount >= 65 * 8, "Data is too short")

    val (stream1, sig) = stream.popBytes(65)
    val data0 = toInt5s(stream1)

    val rawtags = loop(data0 drop 7)
    val tags = rawtags map Tag.parse

    val signature = sig.reverse
    val (r, s, recid) = Signature decode signature
    val messageHash = Crypto.sha256(hrp.getBytes ++ stream1.bytes)
    val (pub1, pub2) = Crypto.recoverPublicKey(r -> s, messageHash)
    val pub = if (recid % 2 != 0) pub2 else pub1

    val prefix = hrp take 4
    val amountOpt = Amount decode hrp.drop(4)
    val pr = PaymentRequest(prefix, amountOpt, Timestamp decode data0, pub, tags.toVector, signature)
    require(Crypto.verifySignature(messageHash, r -> s, pub), "Invalid payment request signature")
    pr
  }

  def write(pr: PaymentRequest): String = {
    val hramount = Amount encode pr.amount
    val hrp = pr.prefix + hramount

    val int5s = toInt5s(pr.stream writeBytes pr.signature)
    val checksum = Bech32.checksum(hrp, int5s)

    val body = (int5s ++ checksum) map Bech32.pam
    hrp + "1" + new String(body.toArray)
  }
}