package bitstreamassembler

import bubbletea._
import upickle.default._

case class StreamingEngineStaticConfigurationData(
  loadStreamsConfigured: Seq[Boolean],
  storeStreamsConfigured: Seq[Boolean],
  storeStreamsVecLengthMinusOne: Seq[Int],
)

// case class RemaperMaskData(
//   used: Boolean,
//   moduloCycle: Int,
//   side: Side.Side,
//   index: Int
// )

case class StreamingStageStaticConfigurationData(
  streamingEngine: StreamingEngineStaticConfigurationData,
  initiationIntervalMinusOne: Int,
  storeStreamsFixedDelay: Int,
  loadRemaperSwitchesSetup: Seq[Seq[RemaperMask]],
  storeRemaperSwitchesSetup: Seq[Seq[RemaperMask]],
)

case class OutRegsSrcSelData(
  north: Int,
  south: Int,
  west: Int,
  east: Int,
)

case class OutRegsEnData(
  north: Boolean,
  south: Boolean,
  west: Boolean,
  east: Boolean,
)

case class RfWritePortsSrcSelData(
  ports: Seq[Int],
)

case class FuSrcSelData(
  a: Int,
  b: Int,
)

sealed trait FuOpData { def toFuSel: FUSel.Type }
case object FuNop extends FuOpData { def toFuSel = FUSel.nop}
case object FuAdd extends FuOpData { def toFuSel = FUSel.add }
case object FuSub extends FuOpData { def toFuSel = FUSel.sub }
case object FuMul extends FuOpData { def toFuSel = FUSel.mul }
case object FuShl extends FuOpData { def toFuSel = FUSel.shl }
case object FuLshr extends FuOpData { def toFuSel = FUSel.lshr }
case object FuAshr extends FuOpData { def toFuSel = FUSel.ashr }
case object FuAnd extends FuOpData { def toFuSel = FUSel.and }
case object FuOr extends FuOpData { def toFuSel = FUSel.or }
case object FuXor extends FuOpData { def toFuSel = FUSel.xor }
case object FuRes0 extends FuOpData { def toFuSel = FUSel.res0 }
case object FuRes1 extends FuOpData { def toFuSel = FUSel.res1 }
case object FuRes2 extends FuOpData { def toFuSel = FUSel.res2 }
case object FuRes3 extends FuOpData { def toFuSel = FUSel.res3 }
case object FuRes4 extends FuOpData { def toFuSel = FUSel.res4 }
case object FuRes5 extends FuOpData { def toFuSel = FUSel.res5 }

case class ProcessingElementConfigData(
  op: FuOpData,
  outRegsSel: OutRegsSrcSelData,
  outRegsEn: OutRegsEnData,
  rfWritePortsSel: RfWritePortsSrcSelData,
  fuSrcSel: FuSrcSelData,
  rfWriteAddr: Seq[Int],
  rfReadAddr: Seq[Int],
  rfWriteEn: Seq[Boolean],
)

case class DelayerBundleData(
  north: Seq[Int],
  south: Seq[Int],
  west: Seq[Int],
  east: Seq[Int],
)

case class DelayerConfigData(
  loads: DelayerBundleData,
  stores: DelayerBundleData,
)

case class AcceleratorStaticConfigurationData(
  streamingStage: StreamingStageStaticConfigurationData,
  mesh: Seq[Seq[Seq[ProcessingElementConfigData]]],
  delayer: DelayerConfigData,
)

case class StreamingEngineCompressedConfigurationChannelData(
  isValid: Boolean,
  stream: Int,
  elementWidth: Int,
  loadStoreOrMod: Boolean,
  dimOffsetOrModSize: BigInt,
  dimSizeOrModTargetAndModBehaviour: BigInt,
  end: Boolean,
  start: Boolean,
  dimStrideOrModDisplacement: BigInt,
  vectorize: Boolean
)

case class ConfigurationData(
  static: AcceleratorStaticConfigurationData,
  streamingEngineInstructions: Seq[StreamingEngineCompressedConfigurationChannelData]
)

object ConfigurationData {
  implicit val streamingEngineStaticConfigurationDataRw: ReadWriter[StreamingEngineStaticConfigurationData] = macroRW
  
  implicit val remaperMaskRw: ReadWriter[RemaperMask] = readwriter[ujson.Value].bimap[RemaperMask](
    x => ujson.Obj(
      "used" -> x.used,
      "moduloCycle" -> x.moduloCycle,
      "side" -> (x.side match {
        case Side.North => "north"
        case Side.South => "south"
        case Side.West => "west"
        case Side.East => "east"
      }),
      "index" -> x.index
    ),
    json => {
      val obj = json.obj
      RemaperMask(
        obj("used").bool,
        obj("moduloCycle").num.toInt,
        obj("side").str match {
          case "north" => Side.North
          case "south" => Side.South
          case "west" => Side.West
          case "east" => Side.East
        },
        obj("index").num.toInt
      )
    }
  )
  
  implicit val streamingStageStaticConfigurationDataRw: ReadWriter[StreamingStageStaticConfigurationData] = macroRW
  
  def outRegsSrcSelDataIntMatcher(int: Int) = int match {
    case 0 => "result"
    case 1 => "north"
    case 2 => "south"
    case 3 => "west"
    case 4 => "east"
    case x if x >= 5 => s"rfPort${x - 5}"
  }
  def outRegsSrcSelDataStringMatcher(str: String) = str match {
    case "result" => 0
    case "north" => 1
    case "south" => 2
    case "west" => 3
    case "east" => 4
    case x if x.startsWith("rfPort") => x.drop(6).toInt + 5
  }
  implicit val outRegsSrcSelDataRw: ReadWriter[OutRegsSrcSelData] = readwriter[ujson.Value].bimap[OutRegsSrcSelData](
    x => ujson.Obj(
      "north" -> outRegsSrcSelDataIntMatcher(x.north),
      "south" -> outRegsSrcSelDataIntMatcher(x.south),
      "west" -> outRegsSrcSelDataIntMatcher(x.west),
      "east" -> outRegsSrcSelDataIntMatcher(x.east)
    ),
    json => {
      val obj = json.obj
      OutRegsSrcSelData(
        outRegsSrcSelDataStringMatcher(obj("north").str),
        outRegsSrcSelDataStringMatcher(obj("south").str),
        outRegsSrcSelDataStringMatcher(obj("west").str),
        outRegsSrcSelDataStringMatcher(obj("east").str)
      )
    }
  )

  implicit val OutRegsEnDataRw: ReadWriter[OutRegsEnData] = macroRW
  
  def rfWritePortsSrcSelDataIntMatcher(int: Int) = int match {
    case 0 => "result"
    case 1 => "north"
    case 2 => "south"
    case 3 => "west"
    case 4 => "east"
  }
  def rfWritePortsSrcSelDataStringMatcher(str: String) = str match {
    case "result" => 0
    case "north" => 1
    case "south" => 2
    case "west" => 3
    case "east" => 4
  }
  implicit val rfWritePortsSrcSelDataRw: ReadWriter[RfWritePortsSrcSelData] = readwriter[ujson.Value].bimap[RfWritePortsSrcSelData](
    x => ujson.Obj(
      "ports" -> ujson.Arr.from(x.ports.map(rfWritePortsSrcSelDataIntMatcher))
    ),
    json => {
      val obj = json.obj
      RfWritePortsSrcSelData(
        obj("ports").arr.map(_.str).map(rfWritePortsSrcSelDataStringMatcher).toSeq
      )
    }
  )
  
  def fuSrcSelDataIntMatcher(int: Int) = int match {
    case 0 => "north"
    case 1 => "south"
    case 2 => "west"
    case 3 => "east"
    case x if x >= 4 => s"rfPort${x - 4}"
  }
  def fuSrcSelDataStringMatcher(str: String) = str match {
    case "north" => 0
    case "south" => 1
    case "west" => 2
    case "east" => 3
    case x if x.startsWith("rfPort") => x.drop(6).toInt + 4
  }
  implicit val fuSrcSelDataRw: ReadWriter[FuSrcSelData] = readwriter[ujson.Value].bimap[FuSrcSelData](
    x => ujson.Obj(
      "a" -> fuSrcSelDataIntMatcher(x.a),
      "b" -> fuSrcSelDataIntMatcher(x.b)
    ),
    json => {
      val obj = json.obj
      FuSrcSelData(
        fuSrcSelDataStringMatcher(obj("a").str),
        fuSrcSelDataStringMatcher(obj("b").str)
      )
    }
  )

  implicit val fuOpDataRw: ReadWriter[FuOpData] = readwriter[ujson.Value].bimap[FuOpData](
    {
      case FuNop => "nop"
      case FuAdd => "add"
      case FuSub => "sub"
      case FuMul => "mul"
      case FuShl => "shl"
      case FuLshr => "lshr"
      case FuAshr => "ashr"
      case FuAnd => "and"
      case FuOr => "or"
      case FuXor => "xor"
      case FuRes0 => "res0"
      case FuRes1 => "res1"
      case FuRes2 => "res2"
      case FuRes3 => "res3"
      case FuRes4 => "res4"
      case FuRes5 => "res5"
    },
    json => json.str match {
      case "nop" => FuNop
      case "add" => FuAdd
      case "sub" => FuSub
      case "mul" => FuMul
      case "shl" => FuShl
      case "lshr" => FuLshr
      case "ashr" => FuAshr
      case "and" => FuAnd
      case "or" => FuOr
      case "xor" => FuXor
      case "res0" => FuRes0
      case "res1" => FuRes1
      case "res2" => FuRes2
      case "res3" => FuRes3
      case "res4" => FuRes4
      case "res5" => FuRes5
    }
  )
  
  implicit val processingElementConfigDataRw: ReadWriter[ProcessingElementConfigData] = macroRW
  
  implicit val delayerBundleDataRw: ReadWriter[DelayerBundleData] = macroRW
  
  implicit val delayerConfigDataRw: ReadWriter[DelayerConfigData] = macroRW
  
  implicit val acceleratorStaticConfigurationDataRw: ReadWriter[AcceleratorStaticConfigurationData] = macroRW
  
  implicit val streamingEngineCompressedConfigurationChannelDataRw: ReadWriter[StreamingEngineCompressedConfigurationChannelData] = macroRW
  
  implicit val configurationDataRw: ReadWriter[ConfigurationData] = macroRW
}