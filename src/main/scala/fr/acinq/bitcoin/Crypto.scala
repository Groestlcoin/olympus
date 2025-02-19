package fr.acinq.bitcoin

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.math.BigInteger

import com.hashengineering.crypto.Groestl
import org.bitcoin.{NativeSecp256k1, Secp256k1Context}
import org.slf4j.LoggerFactory
import org.spongycastle.asn1.sec.SECNamedCurves
import org.spongycastle.asn1.{ASN1Integer, DERSequenceGenerator}
import org.spongycastle.crypto.Digest
import org.spongycastle.crypto.digests.{RIPEMD160Digest, SHA1Digest, SHA256Digest, SHA512Digest}
import org.spongycastle.crypto.macs.HMac
import org.spongycastle.crypto.params.{ECDomainParameters, ECPrivateKeyParameters, ECPublicKeyParameters, KeyParameter}
import org.spongycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import org.spongycastle.math.ec.ECPoint
import scodec.bits.ByteVector


object Crypto {
  val params = SECNamedCurves.getByName("secp256k1")
  val curve = new ECDomainParameters(params.getCurve, params.getG, params.getN, params.getH)
  val halfCurveOrder = params.getN().shiftRight(1)
  val zero = BigInteger.valueOf(0)
  val one = BigInteger.valueOf(1)

  private val logger = LoggerFactory.getLogger(classOf[Secp256k1Context])
  if (Secp256k1Context.isEnabled) {
    logger.info("secp256k1 library successfully loaded")
  } else {
    logger.info("couldn't find secp256k1 library, defaulting to spongycastle")
  }

  def fixSize(data: ByteVector): ByteVector = data.padLeft(32)

  /**
    * A scalar is a 256 bit number
    *
    * @param value value to initialize this scalar with
    */
  case class Scalar(value: BigInteger) {
    def add(scalar: Scalar): Scalar = if (Secp256k1Context.isEnabled)
      Scalar(ByteVector.view(NativeSecp256k1.privKeyTweakAdd(toBin.toArray, scalar.toBin.toArray)))
    else
      Scalar(value.add(scalar.value)).mod(Crypto.curve.getN)

    def substract(scalar: Scalar): Scalar = Scalar(value.subtract(scalar.value)).mod(Crypto.curve.getN)

    def multiply(scalar: Scalar): Scalar = if (Secp256k1Context.isEnabled)
      Scalar(ByteVector.view(NativeSecp256k1.privKeyTweakMul(toBin.toArray, scalar.toBin.toArray)))
    else
      Scalar(value.multiply(scalar.value).mod(Crypto.curve.getN))

    def +(that: Scalar): Scalar = add(that)

    def -(that: Scalar): Scalar = substract(that)

    def *(that: Scalar): Scalar = multiply(that)

    def isZero: Boolean = value == BigInteger.ZERO

    /**
      *
      * @return a 32 bytes binary representation of this value
      */
    def toBin: ByteVector = fixSize(ByteVector.view(value.toByteArray.dropWhile(_ == 0)))

    /**
      *
      * @return this * G where G is the curve generator
      */
    def toPoint: Point = Point(params.getG * value)

    override def toString = this.toBin.toHex
  }

  object Scalar {
    def apply(data: ByteVector): Scalar = {
      require(data.length == 32, "scalar must be initialized with a 32 bytes value")
      new Scalar(new BigInteger(1, data.toArray))
    }
  }

  implicit def scalar2biginteger(scalar: Scalar): BigInteger = scalar.value

  implicit def biginteger2scalar(value: BigInteger): Scalar = Scalar(value)

  implicit def bin2scalar(value: ByteVector): Scalar = Scalar(value)

  implicit def scalar2bin(scalar: Scalar): ByteVector = scalar.toBin

  object PrivateKey {
    def apply(data: ByteVector): PrivateKey = data.length match {
      case 32 => new PrivateKey(Scalar(data), compressed = false)
      case 33 if data.last == 1 => new PrivateKey(Scalar(data.take(32)), compressed = true)
    }

    def apply(data: ByteVector, compressed: Boolean): PrivateKey = new PrivateKey(Scalar(data.take(32)), compressed)

    def fromBase58(value: String, prefix: Byte): PrivateKey = {
      require(Set(Base58.Prefix.SecretKey, Base58.Prefix.SecretKeyTestnet, Base58.Prefix.SecretKeySegnet).contains(prefix), "invalid base 58 prefix for a private key")
      val (`prefix`, data) = Base58Check.decode(value)
      PrivateKey(data)
    }
  }

  /**
    *
    * @param value      value of this private key (a number)
    * @param compressed flags which specifies if the associated public key will be compressed or uncompressed.
    */
  case class PrivateKey(value: Scalar, compressed: Boolean = true) {
    /**
      *
      * @return the public key for this private key
      */
    def publicKey: PublicKey = PublicKey(value.toPoint, compressed)

    /**
      *
      * @return the binary representation of this private key. It is either 32 bytes, or 33 bytes with final 0x1 if the
      *         key is compressed
      */
    def toBin: ByteVector = if (compressed) value.toBin :+ 1.toByte else value.toBin

    override def toString = toBin.toHex
  }

  implicit def privatekey2scalar(priv: PrivateKey): Scalar = priv.value

  /**
    * Curve point
    *
    * @param value ecPoint to initialize this point with
    */
  case class Point(value: ECPoint) {
    def add(point: Point): Point = Point(value.add(point.value))

    def substract(point: Point): Point = Point(value.subtract(point.value))

    def multiply(scalar: Scalar): Point = Point(value.multiply(scalar.value))

    def normalize = Point(value.normalize())

    def +(that: Point): Point = add(that)

    def -(that: Point): Point = substract(that)

    def *(that: Scalar): Point = multiply(that)

    /**
      *
      * @return a binary representation of this point in DER format
      */
    def toBin(compressed: Boolean): ByteVector = ByteVector.view(value.getEncoded(compressed))

    // because ECPoint is not serializable
    protected def writeReplace: Object = PointProxy(toBin(true))

    override def toString = toBin(true).toHex

  }

  case class PointProxy(bin: ByteVector) {
    def readResolve: Object = Point(bin)
  }

  object Point {
    def apply(data: ByteVector): Point = Point(curve.getCurve.decodePoint(data.toArray))
  }

  implicit def point2ecpoint(point: Point): ECPoint = point.value

  implicit def ecpoint2point(value: ECPoint): Point = Point(value)

  object PublicKey {
    def apply(point: Point) = new PublicKey(point.toBin(true))

    def apply(point: Point, compressed: Boolean) = new PublicKey(point.toBin(compressed))

    def fromValidHex(hex: String) = PublicKey(ByteVector.fromValidHex(hex))
  }

  /**
    *
    * @param raw        serialized value of this public key (a point)
    */
  case class PublicKey(raw: ByteVector) {
    // we always make this very basic check
    require(isPubKeyValid(raw))

    lazy val compressed = isPubKeyCompressed(raw)

    lazy val value: Point = Point(raw)

    def toBin: ByteVector = raw

    /**
      *
      * @return the hash160 of the binary representation of this point. This can be used to generated addresses (the address
      *         of a public key is he base58 encoding of its hash)
      */
    def hash160: ByteVector = Crypto.hash160(raw)

    override def toString = toBin.toHex
  }

  implicit def publickey2point(pub: PublicKey): Point = pub.value

  implicit def publickey2bin(pub: PublicKey): ByteVector = pub.toBin

  /**
    * Computes ecdh using secp256k1's variant: sha256(priv * pub serialized in compressed format)
    *
    * @param priv private value
    * @param pub  public value
    * @return ecdh(priv, pub) as computed by libsecp256k1
    */
  def ecdh(priv: Scalar, pub: Point): ByteVector = {
    Crypto.sha256(ByteVector.view(pub.multiply(priv).getEncoded(true)))
  }

  def hmac512(key: ByteVector, data: ByteVector): ByteVector = {
    val mac = new HMac(new SHA512Digest())
    mac.init(new KeyParameter(key.toArray))
    mac.update(data.toArray, 0, data.length.toInt)
    val out = new Array[Byte](64)
    mac.doFinal(out, 0)
    ByteVector.view(out)
  }

  def hash(digest: Digest)(input: ByteVector): ByteVector = {
    digest.update(input.toArray, 0, input.length.toInt)
    val out = new Array[Byte](digest.getDigestSize)
    digest.doFinal(out, 0)
    ByteVector.view(out)
  }

  def hash2(digest: Groestl)(input: ByteVector): ByteVector = {
    val out = new Array[Byte](64)
    (Groestl.digest(input.toArray)).copyToArray(out)
    ByteVector.view(out)
  }

  def sha1 = hash(new SHA1Digest) _

  def sha256 = (x: ByteVector) => hash(new SHA256Digest)(x)

  def ripemd160 = hash(new RIPEMD160Digest) _

  def groestl = hash2(new Groestl) _

  //def groestl32 = (x: ByteVector) => ByteVector32(groestl256(x))
  /**
    * 160 bits bitcoin hash, used mostly for address encoding
    * hash160(input) = RIPEMD160(SHA256(input))
    *
    * @param input array of byte
    * @return the 160 bits BTC hash of input
    */
  def hash160(input: ByteVector): ByteVector = ripemd160(sha256(input))

  /**
    * 256 bits bitcoin hash
    * hash256(input) = SHA256(SHA256(input))
    *
    * @param input array of byte
    * @return the 256 bits BTC hash of input
    */
  def hash256(input: ByteVector): ByteVector = sha256(sha256(input))

  /**
    * 256 bits groestl hash
    * hash256(input) = groestl(groestl(input))
    *
    * @param input array of byte
    * @return the 256 bits BTC hash of input
    */
  def groestl256(input: ByteVector):ByteVector = {
    var out = new Array[Byte](32)
    (Groestl.digest(input.toArray)).copyToArray(out)//groestl(input)
    ByteVector.view(out)
  }

  /**
    * An ECDSA signature is a (r, s) pair. Bitcoin uses DER encoded signatures
    *
    * @param r first value
    * @param s second value
    * @return (r, s) in DER format
    */
  def encodeSignature(r: BigInteger, s: BigInteger): ByteVector = {
    // Usually 70-72 bytes
    val bos = new ByteArrayOutputStream(72)
    val seq = new DERSequenceGenerator(bos)
    seq.addObject(new ASN1Integer(r))
    seq.addObject(new ASN1Integer(s))
    seq.close()
    ByteVector.view(bos.toByteArray)
  }

  def encodeSignature(t: (BigInteger, BigInteger)): ByteVector = encodeSignature(t._1, t._2)

  def isDERSignature(sig: ByteVector): Boolean = {
    // Format: 0x30 [total-length] 0x02 [R-length] [R] 0x02 [S-length] [S] [sighash]
    // * total-length: 1-byte length descriptor of everything that follows,
    //   excluding the sighash byte.
    // * R-length: 1-byte length descriptor of the R value that follows.
    // * R: arbitrary-length big-endian encoded R value. It must use the shortest
    //   possible encoding for a positive integers (which means no null bytes at
    //   the start, except a single one when the next byte has its highest bit set).
    // * S-length: 1-byte length descriptor of the S value that follows.
    // * S: arbitrary-length big-endian encoded S value. The same rules apply.
    // * sighash: 1-byte value indicating what data is hashed (not part of the DER
    //   signature)

    // Minimum and maximum size constraints.
    if (sig.size < 9) return false
    if (sig.size > 73) return false

    // A signature is of type 0x30 (compound).
    if (sig(0) != 0x30.toByte) return false

    // Make sure the length covers the entire signature.
    if (sig(1) != sig.size - 3) return false

    // Extract the length of the R element.
    val lenR = sig(3)

    // Make sure the length of the S element is still inside the signature.
    if (5 + lenR >= sig.size) return false

    // Extract the length of the S element.
    val lenS = sig(5 + lenR)

    // Verify that the length of the signature matches the sum of the length
    // of the elements.
    if (lenR + lenS + 7 != sig.size) return false

    // Check whether the R element is an integer.
    if (sig(2) != 0x02) return false

    // Zero-length integers are not allowed for R.
    if (lenR == 0) return false

    // Negative numbers are not allowed for R.
    if ((sig(4) & 0x80.toByte) != 0) return false

    // Null bytes at the start of R are not allowed, unless R would
    // otherwise be interpreted as a negative number.
    if (lenR > 1 && (sig(4) == 0x00) && (sig(5) & 0x80) == 0) return false

    // Check whether the S element is an integer.
    if (sig(lenR + 4) != 0x02.toByte) return false

    // Zero-length integers are not allowed for S.
    if (lenS == 0) return false

    // Negative numbers are not allowed for S.
    if ((sig(lenR + 6) & 0x80) != 0) return false

    // Null bytes at the start of S are not allowed, unless S would otherwise be
    // interpreted as a negative number.
    if (lenS > 1 && (sig(lenR + 6) == 0x00) && (sig(lenR + 7) & 0x80) == 0) return false

    return true
  }

  def isLowDERSignature(sig: ByteVector): Boolean = isDERSignature(sig) && {
    val (_, s) = decodeSignature(sig)
    s.compareTo(halfCurveOrder) <= 0
  }

  def normalizeSignature(r: BigInteger, s: BigInteger): (BigInteger, BigInteger) = {
    val s1 = if (s.compareTo(halfCurveOrder) > 0) curve.getN().subtract(s) else s
    (r, s1)
  }

  def normalizeSignature(sig: ByteVector): ByteVector = {
    val (r, s) = decodeSignature(sig)
    encodeSignature(normalizeSignature(r, s))
  }

  def checkSignatureEncoding(sig: ByteVector, flags: Int): Boolean = {
    import ScriptFlags._
    // Empty signature. Not strictly DER encoded, but allowed to provide a
    // compact way to provide an invalid signature for use with CHECK(MULTI)SIG
    if (sig.isEmpty) true
    else if ((flags & (SCRIPT_VERIFY_DERSIG | SCRIPT_VERIFY_LOW_S | SCRIPT_VERIFY_STRICTENC)) != 0 && !isDERSignature(sig)) false
    else if ((flags & SCRIPT_VERIFY_LOW_S) != 0 && !isLowDERSignature(sig)) false
    else if ((flags & SCRIPT_VERIFY_STRICTENC) != 0 && !isDefinedHashtypeSignature(sig)) false
    else true
  }

  def checkPubKeyEncoding(key: ByteVector, flags: Int, sigVersion: Int): Boolean = {
    if ((flags & ScriptFlags.SCRIPT_VERIFY_STRICTENC) != 0) require(isPubKeyCompressedOrUncompressed(key), "invalid public key")
    // Only compressed keys are accepted in segwit
    if ((flags & ScriptFlags.SCRIPT_VERIFY_WITNESS_PUBKEYTYPE) != 0 && sigVersion == SigVersion.SIGVERSION_WITNESS_V0) require(isPubKeyCompressed(key), "public key must be compressed in segwit")
    true
  }

  /**
    *
    * @param key serialized public key
    * @return true if the key is valid. Please not that this performs very basic tests and does not check that the
    *         point represented by this key is actually valid.
    */
  def isPubKeyValid(key: ByteVector): Boolean = key.length match {
    case 65 if key(0) == 4 || key(0) == 6 || key(0) == 7 => true
    case 33 if key(0) == 2 || key(0) == 3 => true
    case _ => false
  }

  def isPubKeyCompressedOrUncompressed(key: ByteVector): Boolean = key.length match {
    case 65 if key(0) == 4 => true
    case 33 if key(0) == 2 || key(0) == 3 => true
    case _ => false
  }

  def isPubKeyCompressed(key: ByteVector): Boolean = key.length match {
    case 33 if key(0) == 2 || key(0) == 3 => true
    case _ => false
  }

  def isPrivateKeyCompressed(key: PrivateKey): Boolean = key.compressed

  def isDefinedHashtypeSignature(sig: ByteVector): Boolean = if (sig.isEmpty) false
  else {
    val hashType = (sig.last & 0xff) & (~(SIGHASH_ANYONECANPAY))
    if (hashType < SIGHASH_ALL || hashType > SIGHASH_SINGLE) false else true
  }

  /**
    * An ECDSA signature is a (r, s) pair. Bitcoin uses DER encoded signatures
    *
    * @param blob sigbyte data
    * @return the decoded (r, s) signature
    */
  def decodeSignature(blob: ByteVector): (BigInteger, BigInteger) = {
    decodeSignatureLax(blob)
  }

  def decodeSignatureLax(input: ByteArrayInputStream): (BigInteger, BigInteger) = {
    require(input.read() == 0x30)

    def readLength: Int = {
      val len = input.read()
      if ((len & 0x80) == 0) len else {
        var n = len - 0x80
        var len1 = 0
        while (n > 0) {
          len1 = (len1 << 8) + input.read()
          n = n - 1
        }
        len1
      }
    }

    readLength
    require(input.read() == 0x02)
    val lenR = readLength
    val r = new Array[Byte](lenR)
    input.read(r)
    require(input.read() == 0x02)
    val lenS = readLength
    val s = new Array[Byte](lenS)
    input.read(s)
    (new BigInteger(1, r), new BigInteger(1, s))
  }

  def decodeSignatureLax(input: ByteVector): (BigInteger, BigInteger) = decodeSignatureLax(new ByteArrayInputStream(input.toArray))

  def verifySignature(data: ByteVector, signature: (BigInteger, BigInteger), publicKey: PublicKey): Boolean =
    verifySignature(data, encodeSignature(signature), publicKey)

  /**
    * @param data      data
    * @param signature signature
    * @param publicKey public key
    * @return true is signature is valid for this data with this public key
    */
  def verifySignature(data: ByteVector, signature: ByteVector, publicKey: PublicKey): Boolean = {
    if (Secp256k1Context.isEnabled) {
      val signature1 = normalizeSignature(signature)
      val native = NativeSecp256k1.verify(data.toArray, signature1.toArray, publicKey.toBin.toArray)
      native
    } else {
      val (r, s) = decodeSignature(signature)
      require(r.compareTo(one) >= 0, "r must be >= 1")
      require(r.compareTo(curve.getN) < 0, "r must be < N")
      require(s.compareTo(one) >= 0, "s must be >= 1")
      require(s.compareTo(curve.getN) < 0, "s must be < N")

      val signer = new ECDSASigner
      val params = new ECPublicKeyParameters(publicKey.value, curve)
      signer.init(false, params)
      signer.verifySignature(data.toArray, r, s)
    }
  }

  /**
    *
    * @param privateKey private key
    * @return the corresponding public key
    */
  def publicKeyFromPrivateKey(privateKey: ByteVector) = PrivateKey(privateKey).publicKey

  /**
    * Sign data with a private key, using RCF6979 deterministic signatures
    *
    * @param data       data to sign
    * @param privateKey private key. If you are using bitcoin "compressed" private keys make sure to only use the first 32 bytes of
    *                   the key (there is an extra "1" appended to the key)
    * @return a (r, s) ECDSA signature pair
    */
  def sign(data: Array[Byte], privateKey: PrivateKey): (BigInteger, BigInteger) = {
    if (Secp256k1Context.isEnabled) {
      val bin = NativeSecp256k1.sign(data, privateKey.value.toBin.toArray)
      Crypto.decodeSignature(ByteVector.view(bin))
    } else {
      val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest))
      val privateKeyParameters = new ECPrivateKeyParameters(privateKey.value, curve)
      signer.init(true, privateKeyParameters)
      val Array(r, s) = signer.generateSignature(data)

      if (s.compareTo(halfCurveOrder) > 0) {
        (r, curve.getN().subtract(s)) // if s > N/2 then s = N - s
      } else {
        (r, s)
      }
    }
  }

  def sign(data: ByteVector, privateKey: PrivateKey): (BigInteger, BigInteger) = sign(data.toArray, privateKey)

  /**
    *
    * @param x x coordinate
    * @return a tuple (p1, p2) where p1 and p2 are points on the curve and p1.x = p2.x = x
    *         p1.y is even, p2.y is odd
    */
  def recoverPoint(x: BigInteger): (Point, Point) = {
    val x1 = Crypto.curve.getCurve.fromBigInteger(x)
    val square = x1.square().add(Crypto.curve.getCurve.getA).multiply(x1).add(Crypto.curve.getCurve.getB)
    val y1 = square.sqrt()
    val y2 = y1.negate()
    val R1 = Crypto.curve.getCurve.createPoint(x1.toBigInteger, y1.toBigInteger).normalize()
    val R2 = Crypto.curve.getCurve.createPoint(x1.toBigInteger, y2.toBigInteger).normalize()
    if (y1.testBitZero()) (R2, R1) else (R1, R2)
  }

  /**
    * Recover public keys from a signature and the message that was signed. This method will return 2 public keys, and the signature
    * can be verified with both, but only one of them matches that private key that was used to generate the signature.
    *
    * @param t       signature
    * @param message message that was signed
    * @return a (pub1, pub2) tuple where pub1 and pub2 are candidates public keys. If you have the recovery id  then use
    *         pub1 if the recovery id is even and pub2 if it is odd
    */
  def recoverPublicKey(t: (BigInteger, BigInteger), message: ByteVector): (PublicKey, PublicKey) = {
    val (r, s) = t
    val m = new BigInteger(1, message.toArray)

    val (p1, p2) = recoverPoint(r)
    val Q1 = (p1.multiply(s).subtract(Crypto.curve.getG.multiply(m))).multiply(r.modInverse(Crypto.curve.getN))
    val Q2 = (p2.multiply(s).subtract(Crypto.curve.getG.multiply(m))).multiply(r.modInverse(Crypto.curve.getN))
    (PublicKey(Q1), PublicKey(Q2))
  }

  def recoverPublicKey(sig: ByteVector, message: ByteVector): (PublicKey, PublicKey) = recoverPublicKey(Crypto.decodeSignature(sig), message)
}
