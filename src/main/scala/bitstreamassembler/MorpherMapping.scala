package bitstreamassembler

import bubbletea._
import chisel3._
import upickle.default._
import com.github.tototoshi.csv._
import java.io._
import scala.util.matching.Regex
import scala.collection.mutable.ArrayBuffer
import scala.util.control.Breaks._
import chisel3.util.log2Ceil


case class microStreamsId(
  north: ArrayBuffer[ArrayBuffer[String]],
  south: ArrayBuffer[ArrayBuffer[String]],
  west: ArrayBuffer[ArrayBuffer[String]],
  east: ArrayBuffer[ArrayBuffer[String]]
)

class MorpherMappingParser[T <: Data](params: BubbleteaParams[T], socParams: SocParams) {
 
  case class DfgOutEdge(id: Int, port: String)
  case class DfgNode(
    id: Int,
    op: String,
    const: String,
    inputs: Seq[Int],
    outputs: Seq[DfgOutEdge],
    recParents: Seq[Int],
  )
  def dfg(dfgFile: String) = {
    val xmlString = scala.io.Source.fromFile(dfgFile).getLines().drop(2).mkString
    val correctedXmlString = xmlString.replaceAll("BB=", " BB=").replaceAll("CONST=", " CONST=")
    val xml = scala.xml.XML.loadString(correctedXmlString)

    val nodes = (xml \ "Node").map(n =>
      DfgNode(
        (n \@ "idx").toInt,
        (n \ "OP").text,
        n \@ "CONST",
        (n \ "Inputs" \ "Input").map(_ \@ "idx").map(_.toInt),
        (n \ "Outputs" \ "Output").map(o => DfgOutEdge((o \@ "idx").toInt, o \@ "type")),
        (n \ "RecParents" \ "RecParent").map(_ \@ "idx").map(_.toInt)
      )
    )
    
    nodes
  }
  
  def nameToXYT(name: String) = {
    val pattern = new Regex("_X(\\d+)\\|_Y(\\d+)\\|-T(\\d+)")

    pattern.findFirstMatchIn(name) match {
      case Some(m) =>
        val x = m.group(1)
        val y = m.group(2)
        val t = m.group(3)
        (x.toInt, y.toInt, t.toInt)
      case None =>
        throw new Exception(s"Invalid name format: $name")
    }
  }

  def route(routeInfoFile: String, dfg: Seq[DfgNode]) = {
    val routeInfo = scala.io.Source.fromFile(routeInfoFile).getLines().toSeq

    def groupLines(lines: Seq[String], startsWith: String) = {
      lines.foldLeft(Seq.empty[Seq[String]]) { (acc, line) =>
        if (line.startsWith(startsWith)) {
          Seq(line) +: acc
        } else {
          if (acc.isEmpty) Seq(Seq(line))
          else (acc.head :+ line) +: acc.tail
        }
      }.reverse
    }
    
    val x = groupLines(routeInfo, "node")

    case class protoParent(id: Int, route: Seq[String])
    case class protoNode(id: Int, mappedTo: String, parents: Seq[protoParent])

    def parseProtoParent(parent: Seq[String]) = {
      val parsed = parent.head.split(":")
      val id = parsed(1).toInt
      val route = parent.tail.map(s => s.substring(s.indexOf(".") + 1))//.reverse
      protoParent(id, route)
    }

    def parseProtoNode(node: Seq[String]) = {
      val parsed = node.head.split(",").map(_.split("=").last)
      val (id, mappedTo) = (parsed(0).toInt, parsed(1))
      val parents = groupLines(node.tail, "\tparent").map(parseProtoParent)
      protoNode(id, mappedTo, parents)
    }

    val protoNodeList = x.map(parseProtoNode)

    val initiationInterval = protoNodeList.map(n => nameToXYT(n.mappedTo)._3).max + 1

    var mesh = ArrayBuffer.fill(params.maxInitiationInterval, params.meshRows, params.meshColumns)(ProcessingElementConfigData(
      op = FuNop,
      outRegsSel = OutRegsSrcSelData(0, 0, 0, 0),
      outRegsEn = OutRegsEnData(false, false, false, false),
      rfWritePortsSel = RfWritePortsSrcSelData(ArrayBuffer.fill(params.rfWritePorts)(0)),
      fuSrcSel = FuSrcSelData(0, 0),
      rfWriteAddr = ArrayBuffer.fill(params.rfWritePorts)(0),
      rfReadAddr = ArrayBuffer.fill(params.rfReadPorts)(0),
      rfWriteEn = ArrayBuffer.fill(params.rfWritePorts)(false),
      immediate = 0
    ))

    var loads = microStreamsId(
      north = ArrayBuffer.fill(params.maxInitiationInterval, params.meshColumns)(""),
      south = ArrayBuffer.fill(params.maxInitiationInterval, params.meshColumns)(""),
      west = ArrayBuffer.fill(params.maxInitiationInterval, params.meshRows)(""),
      east = ArrayBuffer.fill(params.maxInitiationInterval, params.meshRows)("")
    )

    var stores = microStreamsId(
      north = ArrayBuffer.fill(params.maxInitiationInterval, params.meshColumns)(""),
      south = ArrayBuffer.fill(params.maxInitiationInterval, params.meshColumns)(""),
      west = ArrayBuffer.fill(params.maxInitiationInterval, params.meshRows)(""),
      east = ArrayBuffer.fill(params.maxInitiationInterval, params.meshRows)("")
    )

    // operand set is used to check if only one operand is set in the FU, so that we can fix that later
    // fix later for constants and x^2 kinda cases
    val i1Set = ArrayBuffer.fill(initiationInterval, params.meshRows, params.meshColumns)(false)
    val i2Set = ArrayBuffer.fill(initiationInterval, params.meshRows, params.meshColumns)(false)
    val hasConstant = ArrayBuffer.fill(initiationInterval, params.meshRows, params.meshColumns)(false)

    // Set PE ops/immediate values and loads/stores
    for (node <- protoNodeList) {
      val dfgNode = dfg.find(_.id == node.id).get
      if (node.mappedTo.contains("SE")) {
        // This is a SE node
        val (x1, y1, t) = nameToXYT(node.mappedTo)

        def microStreamsIdSetter(microStreams: microStreamsId) = {
          if (node.mappedTo.contains("NORTH")) {
            microStreams.north(t)(x1 - 1) = dfgNode.id.toString
          } else if (node.mappedTo.contains("SOUTH")) {
            microStreams.south(t)(x1 - 1) = dfgNode.id.toString
          } else if (node.mappedTo.contains("WEST")) {
            microStreams.west(t)(y1 - 1) = dfgNode.id.toString
          } else if (node.mappedTo.contains("EAST")) {
            microStreams.east(t)(y1 - 1) = dfgNode.id.toString
          } else {
            throw new Exception(s"Invalid PE_SE node name: ${node.mappedTo}")
          }
        }
        
        // Fill loads and stores with microstreams IDs
        dfgNode.op match {
          case "LOAD" => microStreamsIdSetter(loads)
          case "STORE" => microStreamsIdSetter(stores)
        }
        
      } else{
        // This is a PE node
        val (x1, y1, t) = nameToXYT(node.mappedTo)
        val x = x1 - 1
        val y = y1 - 1
        //println(s"Node ${node.id} mapped at X: $x, Y: $y, T: $t")
        val fuOp = dfgNode.op match {
          case "NOP" => FuNop
          case "ADD" => FuAdd
          case "SUB" => FuSub
          case "MUL" => FuMul
          case "LS" => FuShl
          case "RS" => FuLshr
          case "ARS" => FuAshr
          case "AND" => FuAnd
          case "OR" => FuOr
          case "XOR" => FuXor
        }

        val immediate = dfgNode.const match {
          case "" => 0
          case _ => dfgNode.const.toInt
        }

        // Set ops and immediate values in the mesh
        mesh(t)(y)(x).op = fuOp
        mesh(t)(y)(x).immediate = immediate

        hasConstant(t)(y)(x) = true
      }
    }

    // Set routes in the mesh (THE MEAT)
    def setRoute(src: String, dst: String): Unit = {
      val fuResultPattern = """.+\.FU0\.DP0_T$""".r
      val fuOperandPattern = """.+\.FU0\.DP0_I(\d+)$""".r
      val rfWpPattern = """.+\.RF0\.WP(\d+)$""".r
      val rfRegWritePattern = """.+\.RF0\.R(\d+)_RO$""".r
      val rfRegReadPattern = """.+\.RF0\.R(\d+)_RI$""".r
      val rfRpPattern = """.+\.RF0\.RP(\d+)$""".r
      val inputDirPattern = """.+\.(.+)_I""".r
      val outputDirPattern = """.+\.(.+)_O""".r
      val outRegWritePattern = """.+\.(.+)R_RO""".r
      val outRegReadPattern = """.+\.(.+)R_RI""".r
      val sePattern = """.*PE_SE.*""".r

      def nodeMatcher(node: String) = node match {
        case sePattern() => {
          ("SE", "")
        }
        case fuResultPattern() => {
          ("FU_RESULT", "")
        }
        case fuOperandPattern(fuOperand) => {
          ("FU", fuOperand)
        }
        case rfWpPattern(rfWp) => {
          ("RF_WP", rfWp)
        }
        case rfRegWritePattern(rfReg) => {
          ("RF_REG_WRITE", rfReg)
        }
        case rfRegReadPattern(rfReg) => {
          ("RF_REG_READ", rfReg)
        }
        case rfRpPattern(rfRp) => {
          ("RF_RP", rfRp)
        }
        case inputDirPattern(dir) => {
          ("INPUT", dir)
        }
        case outputDirPattern(dir) => {
          ("OUTPUT", dir)
        }
        case outRegWritePattern(reg) => {
          ("OUT_REG_WRITE", reg)
        }
        case outRegReadPattern(reg) => {
          ("OUT_REG_READ", reg)
        }
        case _ => {
          throw new Exception(s"Unmatched route: $dst")
        }
      }


      val (dstType, dstValue) = nodeMatcher(dst)
      val (srcType, srcValue) = nodeMatcher(src)

      def outRegSetter(value: String) = {
        // this is the case where the any valid source is written to an output register
        // we must set the output register select to the input, and enable the output register
        assert(nameToXYT(src) == nameToXYT(dst))
        val (x1, y1, t) = nameToXYT(src)
        dstValue match {
          case "N" => {
            mesh(t)(y1 - 1)(x1 - 1).outRegsSel.north = ConfigurationData.outRegsSrcSelDataStringMatcher(value)
            mesh(t)(y1 - 1)(x1 - 1).outRegsEn.north = true
          }
          case "S" => {
            mesh(t)(y1 - 1)(x1 - 1).outRegsSel.south = ConfigurationData.outRegsSrcSelDataStringMatcher(value)
            mesh(t)(y1 - 1)(x1 - 1).outRegsEn.south = true
          }
          case "W" => {
            mesh(t)(y1 - 1)(x1 - 1).outRegsSel.west = ConfigurationData.outRegsSrcSelDataStringMatcher(value)
            mesh(t)(y1 - 1)(x1 - 1).outRegsEn.west = true
          }
          case "E" => {
            mesh(t)(y1 - 1)(x1 - 1).outRegsSel.east = ConfigurationData.outRegsSrcSelDataStringMatcher(value)
            mesh(t)(y1 - 1)(x1 - 1).outRegsEn.east = true
          }
          case _ => {
            throw new Exception(s"Unknown direction: $dstValue")
          }
        }
      }

      def fuOperandSetter(value: String) = {
        assert(nameToXYT(src) == nameToXYT(dst))
        val (x1, y1, t) = nameToXYT(src)
        dstValue match {
          case "1" => {
            mesh(t)(y1 - 1)(x1 - 1).fuSrcSel.a = ConfigurationData.fuSrcSelDataStringMatcher(value)
            i1Set(t)(y1 - 1)(x1 - 1) = true
          }
          case "2" => {
            mesh(t)(y1 - 1)(x1 - 1).fuSrcSel.b = ConfigurationData.fuSrcSelDataStringMatcher(value)
            i2Set(t)(y1 - 1)(x1 - 1) = true
          }
          case _ => {
            throw new Exception(s"Unknown operand: $dstValue")
          }
        }
      }

      def rfWpSetter(value: String) = {
        assert(nameToXYT(src) == nameToXYT(dst))
        val (x1, y1, t) = nameToXYT(src)
        mesh(t)(y1 - 1)(x1 - 1).rfWritePortsSel.ports(dstValue.toInt) = ConfigurationData.rfWritePortsSrcSelDataStringMatcher(value)
      }

      if (false) {}
      else if (srcType == "SE") {
        // do nothing for SEs
      }
      else if (dstType == "INPUT") {
        // nothing to do here
      }
      else if (dstType == "OUT_REG_WRITE" && srcType == "INPUT") {
        outRegSetter(srcValue.toLowerCase())
      }
      else if (dstType == "OUT_REG_WRITE" && srcType == "FU_RESULT") {
        outRegSetter("result")
      }
      else if (dstType == "OUT_REG_WRITE" && srcType == "RF_RP") {
        outRegSetter(s"rfPort${srcValue}")
      }
      else if (dstType == "OUT_REG_WRITE" && srcType == "OUT_REG_READ") {
        // this is simply the case where the data stays in the output register
        // keep enable at false (default)
      }
      else if (dstType == "OUT_REG_READ" && srcType == "OUT_REG_WRITE") {
        // this is a register connection in time
        assert(nameToXYT(src)._1 == nameToXYT(dst)._1)
        assert(nameToXYT(src)._2 == nameToXYT(dst)._2)
        assert((nameToXYT(src)._3.toInt + 1) % initiationInterval == nameToXYT(dst)._3.toInt)
        // do nothing
      }
      else if (dstType == "OUTPUT" && srcType == "OUT_REG_READ") {
        // connection from output register to output
        assert(srcValue == dstValue.head.toString())
        // do nothing
      }
      else if (dstType == "FU" && srcType == "INPUT") {
        fuOperandSetter(srcValue.toLowerCase())
      }
      else if (dstType == "FU" && srcType == "RF_RP") {
        fuOperandSetter(s"rfPort${srcValue}")
      }
      else if (dstType == "RF_WP" && srcType == "INPUT") {
        rfWpSetter(srcValue.toLowerCase())
      }
      else if (dstType == "RF_WP" && srcType == "FU_RESULT") {
        rfWpSetter("result")
      }
      else if (dstType == "RF_REG_WRITE" && srcType == "RF_WP") {
        // this is the case where the data is written to a register
        // we must set the register write address to the write port, and enable the write port
        assert(nameToXYT(src) == nameToXYT(dst))
        val (x1, y1, t) = nameToXYT(src)
        mesh(t)(y1 - 1)(x1 - 1).rfWriteAddr(srcValue.toInt) = dstValue.toInt
        mesh(t)(y1 - 1)(x1 - 1).rfWriteEn(srcValue.toInt) = true
      }
      else if (dstType == "RF_REG_WRITE" && srcType == "RF_REG_READ") {
        // this is simply the case where the data stays in the register
      }
      else if (dstType == "RF_REG_READ" && srcType == "RF_REG_WRITE") {
        // this is a register connection in time
        assert(nameToXYT(src)._1 == nameToXYT(dst)._1)
        assert(nameToXYT(src)._2 == nameToXYT(dst)._2)
        assert((nameToXYT(src)._3.toInt + 1) % initiationInterval == nameToXYT(dst)._3.toInt)
        // do nothing
      }
      else if (dstType == "RF_RP" && srcType == "RF_REG_READ") {
        // this is the case where the data is read from a register
        // we must set the register read address to the read port
        assert(nameToXYT(src) == nameToXYT(dst))
        val (x1, y1, t) = nameToXYT(src)
        mesh(t)(y1 - 1)(x1 - 1).rfReadAddr(dstValue.toInt) = srcValue.toInt
      }
      else if (dstType == "SE" && srcType == "OUTPUT") {
        // do nothing for SEs
      }
      else {
        throw new Exception(s"Unknown route type: $srcType -> $dstType")
      }
      
      //println(s"$srcType($srcValue) -> $dstType($dstValue)")
    }


    // Clean the protoNodeList of redundant route nodes
    val cleanProtoNodeList = protoNodeList.map(n => n.copy(parents = n.parents.map(p => p.copy(route = p.route.filterNot(r => {
      val dpOperandPattern = """.+\.FU0\.DP0\.I(\d+)$""".r
      val dummyWpPattern = """.+T\d+\.WP(\d+)$""".r
      val dummyDpPattern = """.+T\d+\.DP0_I(\d+)$""".r
        r match {
          case dpOperandPattern(_) => true
          case dummyWpPattern(_) => true
          case dummyDpPattern(_) => true
          case _ => false
        }  
      })))))

    
    // Set routes in the mesh
    for (node <- cleanProtoNodeList) {
      for (parent <- node.parents) {
        for (r <- 1 until parent.route.length) {
          val src = parent.route(r - 1)
          val dst = parent.route(r)
          setRoute(src, dst)
        }
      }
    }

    // Fix for constants and x^2 kinda cases
    for (t <- 0 until initiationInterval) {
      for (y <- 0 until params.meshRows) {
        for (x <- 0 until params.meshColumns) {
          // handle the immediate values
          if (hasConstant(t)(y)(x) && i1Set(t)(y)(x)) {
            mesh(t)(y)(x).fuSrcSel.b = ConfigurationData.fuSrcSelDataStringMatcher("immediate")
          }
          else if (hasConstant(t)(y)(x) && i2Set(t)(y)(x)) {
            mesh(t)(y)(x).fuSrcSel.a = ConfigurationData.fuSrcSelDataStringMatcher("immediate")
          }
          // handle x^2 kinda cases
          else if (i1Set(t)(y)(x) && !i2Set(t)(y)(x)) {
            mesh(t)(y)(x).fuSrcSel.b = mesh(t)(y)(x).fuSrcSel.a
          }
          else if (i2Set(t)(y)(x) && !i1Set(t)(y)(x)) {
            mesh(t)(y)(x).fuSrcSel.a = mesh(t)(y)(x).fuSrcSel.b
          }
        }
      }
    }

    (mesh, loads, stores, initiationInterval)
  }

  def latency(latencyFile: String) = {
    val csvFile = CSVReader.open(latencyFile)

    val rawCells = csvFile.all()

    val latency = rawCells.map(x => Map(x.head -> x.last)).flatten.toMap
    latency
  }

  def delay(loads: microStreamsId, stores: microStreamsId, latency: Map[String,String], initiationInterval: Int) = {
    val allStores = stores.north.flatten ++ stores.south.flatten ++ stores.west.flatten ++ stores.east.flatten
    val maxStoreDelay = allStores.filterNot(_.isEmpty()).map(latency(_).toInt).max / initiationInterval
    
    val delayerConfig = DelayerConfigData(
      loads = Seq.tabulate(params.maxInitiationInterval) { t =>
        def delayNode(node: String) = {
          if (node.isEmpty()) 0 else (latency(node).toInt / initiationInterval)
        }
        DelayerBundleData(
          north = Seq.tabulate(params.meshColumns)(x => delayNode(loads.north(t)(x))),
          south = Seq.tabulate(params.meshColumns)(x => delayNode(loads.south(t)(x))),
          west = Seq.tabulate(params.meshRows)(y => delayNode(loads.west(t)(y))),
          east = Seq.tabulate(params.meshRows)(y => delayNode(loads.east(t)(y)))
        )
      },
      stores = Seq.tabulate(params.maxInitiationInterval) { t =>
        def delayNode(node: String) = {
          if (node.isEmpty()) 0 else (maxStoreDelay - (latency(node).toInt / initiationInterval))
        }
        DelayerBundleData(
          north = Seq.tabulate(params.meshColumns)(x => delayNode(stores.north(t)(x))),
          south = Seq.tabulate(params.meshColumns)(x => delayNode(stores.south(t)(x))),
          west = Seq.tabulate(params.meshRows)(y => delayNode(stores.west(t)(y))),
          east = Seq.tabulate(params.meshRows)(y => delayNode(stores.east(t)(y)))
        )
      }
    )

    (delayerConfig, maxStoreDelay)
  }
}

class StreamsConfigurationGenerator[T <: Data](params: BubbleteaParams[T], socParams: SocParams) {
  def streams(streamsFile: String, loads: microStreamsId, stores: microStreamsId) = {
    val streamsJson = scala.io.Source.fromFile(streamsFile).getLines().mkString
    val streamData = read[StreamsData](streamsJson)

    val numLoadStreams = streamData.loads.size
    val loadStreamsConfigured = Seq.fill(numLoadStreams)(true) ++ Seq.fill(params.maxSimultaneousLoadMacroStreams - numLoadStreams)(false)

    val numStoreStreams = streamData.stores.size
    val storeStreamsConfigured = Seq.fill(numStoreStreams)(true) ++ Seq.fill(params.maxSimultaneousStoreMacroStreams - numStoreStreams)(false)

    val storeStreamsVecLengthMinusOne = streamData.stores.map(_.micro_pattern.length - 1) ++ Seq.fill(params.maxSimultaneousStoreMacroStreams - numStoreStreams)(0)

    val streamingEngineStaticConfig = StreamingEngineStaticConfigurationData(loadStreamsConfigured, storeStreamsConfigured, storeStreamsVecLengthMinusOne)
  
    
    def compressedInstructionGenerator(descriptor: MacroDescriptionElement, streamIdx: Int, loadStore: Boolean, first: Boolean, last: Boolean) = {
      val width = params.dataType.getWidth
      val elementWidth = {
        def isPowerOfTwo(x: Int): Boolean = (x & (x - 1)) == 0
        if (width % 8 != 0) {
          throw new Exception("Width is not divisible by 8.")
        }
        if (!isPowerOfTwo(width / 8)) {
          throw new Exception("Width in bytes is not a power of 2.")
        }
        log2Ceil(width / 8)
      }

      descriptor match {
        case VectorizeInstruction(vectorize) => StreamingEngineCompressedConfigurationChannelData(
          isValid = true,
          stream = streamIdx,
          elementWidth = elementWidth,
          loadStoreOrMod = false,
          dimOffsetOrModSize = 0,
          dimSizeOrModTargetAndModBehaviour = 0,
          end = last,
          start = first,
          dimStrideOrModDisplacement = 0,
          vectorize = vectorize
        )
        case DimensionDescriptor(offset, size, stride) => StreamingEngineCompressedConfigurationChannelData(
          isValid = true,
          stream = streamIdx,
          elementWidth = elementWidth,
          loadStoreOrMod = if (first) loadStore else false,
          dimOffsetOrModSize = offset,
          dimSizeOrModTargetAndModBehaviour = size,
          end = last,
          start = first,
          dimStrideOrModDisplacement = stride,
          vectorize = false
        )
        case ModifierDescriptor(target, behavior, displacement, size) => {
          val targetBits = target match {
            case "SIZE" => 0
            case "STRIDE" => 1
            case "OFFSET" => 2
            case _ => throw new Exception(s"Invalid target: $target")
          }

          val behaviorBit = behavior match {
            case "INC" => 0
            case "DEC" => 1
            case _ => throw new Exception(s"Invalid behavior: $behavior")
          }

          val behaviorAndTarget = (behaviorBit << 2) | targetBits
          StreamingEngineCompressedConfigurationChannelData(
            isValid = true,
            stream = streamIdx,
            elementWidth = elementWidth,
            loadStoreOrMod = true,
            dimOffsetOrModSize = size,
            dimSizeOrModTargetAndModBehaviour = behaviorAndTarget,
            end = last,
            start = first,
            dimStrideOrModDisplacement = displacement,
            vectorize = false
          )
        }
      }
    }

    val loadStreamsInstructions = streamData.loads.zipWithIndex.flatMap { case (stream, streamIdx) =>
      stream.macro_description.zipWithIndex.map { case (descriptor, idx) =>
        val first = idx == 0
        val last = idx == stream.macro_description.length - 1
        compressedInstructionGenerator(descriptor, streamIdx, loadStore = true, first, last)
      }
    }

    val storeStreamsInstructions = streamData.stores.zipWithIndex.flatMap { case (stream, streamIdx) =>
      stream.macro_description.zipWithIndex.map { case (descriptor, idx) =>
        val first = idx == 0
        val last = idx == stream.macro_description.length - 1
        compressedInstructionGenerator(descriptor, streamIdx + params.maxSimultaneousLoadMacroStreams, loadStore = false, first, last)
      }
    }

    val streamingEngineInstructions = loadStreamsInstructions ++ storeStreamsInstructions ++ Seq.fill(params.maxConfigurationInstructions - (loadStreamsInstructions.length + storeStreamsInstructions.length))(StreamingEngineCompressedConfigurationChannelData(
      isValid = false,
      stream = 0,
      elementWidth = 0,
      loadStoreOrMod = false,
      dimOffsetOrModSize = 0,
      dimSizeOrModTargetAndModBehaviour = 0,
      end = false,
      start = false,
      dimStrideOrModDisplacement = 0,
      vectorize = false
    ))

    def findInLoadsOrStores(id: Int) = {
      val idString = id.toString
      def findInMicroStreamsId(microStreamsId: microStreamsId) = {
        val sides = Seq((Side.North, microStreamsId.north), (Side.South, microStreamsId.south), (Side.East, microStreamsId.east), (Side.West, microStreamsId.west))

        sides.flatMap { case (side, array2d) =>
          array2d.zipWithIndex.flatMap { case (array, moduloCycle) =>
            array.filter(_ == idString).map(_ => RemaperMask(true, moduloCycle, side, array.indexOf(idString)))
          }
        }.headOption
      }
      
      findInMicroStreamsId(loads).orElse(findInMicroStreamsId(stores)).getOrElse(throw new Exception("Value not found in loads or stores"))
    }

    def singleStreamRemapperMask(micro_pattern: Seq[Option[Int]]) = {
      micro_pattern.map {
        case Some(x) => findInLoadsOrStores(x)
        case None => RemaperMask(false, 0, Side.North, 0)
      }
    }

    val loadStreamsRemapperMasks = streamData.loads.map(s => singleStreamRemapperMask(s.micro_pattern))
    val storeStreamsRemapperMasks = streamData.stores.map(s => singleStreamRemapperMask(s.micro_pattern))

    val loadStreamsRemapperMasksPadded = loadStreamsRemapperMasks.map { streamMask => {
      if (streamMask.length > params.macroStreamDepth) throw new Exception("Stream mask length is greater than macro stream depth")
      streamMask ++ Seq.fill(params.macroStreamDepth - streamMask.length)(RemaperMask(false, 0, Side.North, 0))
      }
    }
    val storeStreamsRemapperMasksPadded = storeStreamsRemapperMasks.map { streamMask => {
      if (streamMask.length > params.macroStreamDepth) throw new Exception("Stream mask length is greater than macro stream depth")
      streamMask ++ Seq.fill(params.macroStreamDepth - streamMask.length)(RemaperMask(false, 0, Side.North, 0))
      }
    }

    if (loadStreamsRemapperMasksPadded.length > params.maxSimultaneousLoadMacroStreams) throw new Exception("Number of load streams is greater than maxSimultaneousLoadMacroStreams")
    if (storeStreamsRemapperMasksPadded.length > params.maxSimultaneousStoreMacroStreams) throw new Exception("Number of store streams is greater than maxSimultaneousStoreMacroStreams")

    val loadRemaperSwitchesSetup = loadStreamsRemapperMasksPadded ++ Seq.fill(params.maxSimultaneousLoadMacroStreams - loadStreamsRemapperMasksPadded.length)(Seq.fill(params.macroStreamDepth)(RemaperMask(false, 0, Side.North, 0)))
    val storeRemaperSwitchesSetup = storeStreamsRemapperMasksPadded ++ Seq.fill(params.maxSimultaneousStoreMacroStreams - storeStreamsRemapperMasksPadded.length)(Seq.fill(params.macroStreamDepth)(RemaperMask(false, 0, Side.North, 0)))

    (streamingEngineStaticConfig, streamingEngineInstructions, loadRemaperSwitchesSetup, storeRemaperSwitchesSetup)
  }
}

object MorpherMapping extends App {
  val baseDirectory = args(0)
  val latencyFile = s"$baseDirectory/latency.txt"
  val routeInfoFile = s"$baseDirectory/routeInfo.log"
  val dfgFile = s"$baseDirectory/dfg.xml"
  val streamsFile = s"$baseDirectory/streams.json"
  val outputFile = s"$baseDirectory/configuration.json"
  val params = CommonBubbleteaParams.float4x4
  val socParams = SocParams(
    cacheLineBytes = 64,
    frontBusAddressBits = 32,
    frontBusDataBits = 64,
    xLen = 64
  )
  val parser = new MorpherMappingParser(params, socParams)

  val dfg = parser.dfg(dfgFile)
  val (mesh, loads, stores, initiationInterval) = parser.route(routeInfoFile, dfg)
  val latency = parser.latency(latencyFile)
  val (delayer, maxStoreDelay) = parser.delay(loads, stores, latency, initiationInterval)

  val streamsConfigGenerator = new StreamsConfigurationGenerator(params, socParams)
  val (streamingEngine, streamingEngineInstructions, loadRemaperSwitchesSetup, storeRemaperSwitchesSetup) = streamsConfigGenerator.streams(streamsFile, loads, stores)

  val streamingStage = StreamingStageStaticConfigurationData(
    streamingEngine = streamingEngine,
    initiationIntervalMinusOne = initiationInterval - 1,
    storeStreamsFixedDelay = maxStoreDelay,
    loadRemaperSwitchesSetup = loadRemaperSwitchesSetup,
    storeRemaperSwitchesSetup = storeRemaperSwitchesSetup
  )

  val static = AcceleratorStaticConfigurationData(
    streamingStage = streamingStage,
    mesh = mesh.map(_.map(_.toSeq).toSeq).toSeq,
    delayer = delayer
  )

  val configuration = ConfigurationData(static, streamingEngineInstructions)

  val json = write(configuration, indent = 2)
  //println(json)

  val fileWriter = new FileWriter(outputFile)
  fileWriter.write(json)
  fileWriter.close()
  
  println(s"Configuration file generated successfully: $outputFile")
}
