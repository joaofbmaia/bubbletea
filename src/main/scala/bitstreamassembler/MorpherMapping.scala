package bitstreamassembler

import bubbletea._
import chisel3._
import upickle.default._
import com.github.tototoshi.csv._
import java.io._


case class microStreamsId(
  north: Seq[Seq[String]],
  south: Seq[Seq[String]],
  west: Seq[Seq[String]],
  east: Seq[Seq[String]]
)

class MorpherMappingParser[T <: Data](params: BubbleteaParams[T], socParams: SocParams) {
  def csv(mappingFile: String) = {
    // Parse CSV file
    val csvFile = CSVReader.open(mappingFile)

    val rawCells = csvFile.all()
    val removeTimestamps = rawCells.map(_.drop(1))
    val replaceDp0 = removeTimestamps.zipWithIndex.map { case (x, i) => if (i == 2) x.zipWithIndex.map { case (y, j) => if (y == "DP0") removeTimestamps(1)(j) else y } else x }

    val peIndices = replaceDp0(0).zipWithIndex.filter(!_._1.isEmpty()).map(_._2)
    
    val peCells = peIndices.zipWithIndex.map { case (x, i) => 
      val from = x
      val until = if (i == peIndices.length - 1) replaceDp0(0).length else peIndices(i + 1)
      replaceDp0.transpose.slice(from, until).transpose.map(_.map(_.replace("---", "")))
    }

    val peCellsMap = peCells.map(x => Map(x.head.head -> x.drop(2)))

    val peMaps = peCellsMap.map(x => x.map { case (k, v) => (k, v.transpose.map(y => Map(y.head -> y.tail)).flatten.toMap) }).flatten.toMap

    val initiationInterval = peMaps.values.head.values.head.length

    // Parse PEs
    val pes = Seq.tabulate(initiationInterval, params.meshRows, params.meshColumns) { (t, y, x) =>
      val peName = s"PE_X${x+1}|_Y${y+1}|-T0"
      val pe = peMaps(peName).map { case (k, v) => (k, v(t))}
      pe
    }

    // Parse loads and stores
    def microStreamsIdParser(key: String) = {
      def lsStringToNode(ls: String) = {
        val lsNodePattern = "(\\d+):".r // Regex to find one or more digits followed by ':'
        lsNodePattern.findFirstMatchIn(ls).map(_.group(1)).getOrElse("")
      }
      
      microStreamsId(
        north = Seq.tabulate(initiationInterval, params.meshColumns) { (t, x) =>
          val sePeName = s"PE_SE_NORTH_X${x+1}|_Y0|-T0"
          val lu = lsStringToNode(peMaps(sePeName)(key)(t))
          lu
        },
        south = Seq.tabulate(initiationInterval, params.meshColumns) { (t, x) =>
          val sePeName = s"PE_SE_SOUTH_X${x+1}|_Y${params.meshRows + 1}|-T0"
          val lu = lsStringToNode(peMaps(sePeName)(key)(t))
          lu
        },
        west = Seq.tabulate(initiationInterval, params.meshRows) { (t, y) =>
          val sePeName = s"PE_SE_WEST_X0|_Y${y+1}|-T0"
          val lu = lsStringToNode(peMaps(sePeName)(key)(t))
          lu
        },
        east = Seq.tabulate(initiationInterval, params.meshRows) { (t, y) =>
          val sePeName = s"PE_SE_EAST_X${params.meshColumns + 1}|_Y${y+1}|-T0"
          val lu = lsStringToNode(peMaps(sePeName)(key)(t))
          lu
        }
      )
    }

    val loads = microStreamsIdParser("LU0")
    val stores = microStreamsIdParser("SU0")

    (pes, loads, stores)
  }


  def pe(pes: Seq[Seq[Seq[Map[String,String]]]], t: Int, y: Int, x: Int) = {
    val opPattern = ":([^:]+)\\(".r // Regex to find any characters between ':' and '('
    val fuOp = opPattern.findFirstMatchIn(pes(t)(y)(x)("FU0")) match {
      case Some(matched) => matched.group(1) match {
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
      case None => FuNop
    }
   
    var rfReadPorts = Seq[Int]()
    def findSource(dstType: String, node: String, pe: Map[String, String]): String = {
      val fuNodePattern = "(\\d+):".r // Regex to find one or more digits followed by ':'
      val fuResultFound = fuNodePattern.findFirstMatchIn(pe("FU0")) match {
        case Some(matched) => matched.group(1) == node
        case None => false
      }

      // FU result source only allowed for out regs and RF
      if (dstType == "OUT_REG" || dstType == "RF") {
        if(fuResultFound) return "result"
      }
      if(pe("NORTH_I") == node) return "north"
      if(pe("SOUTH_I") == node) return "south"
      if(pe("WEST_I") == node) return "west"
      if(pe("EAST_I") == node) return "east"
      // RF source only allowed for out regs and FU
      if (dstType == "OUT_REG" || dstType == "FU") {
        for (i <- 0 until params.rfSize) {
          if(pe(s"R${i}_RI") == node) {
            if (rfReadPorts.contains(i)) {
              return s"rfPort${rfReadPorts.indexOf(i)}"
            } else {
              rfReadPorts = rfReadPorts :+ i
              return s"rfPort${rfReadPorts.length - 1}"
            }
          }
        }
      }

      throw new Exception(s"Source not found for node $node at PE $x, $y, $t")
      ""
    }

    var outRegsEnSeq = Seq.fill(4)(false)
    def outRegSelector(dstReg: String) = {
      val key = s"${dstReg}_RO"
      var en = false
      var ret = ""
      if (pes(t)(y)(x)(key).isEmpty()) {
        // default
        ret = "result"
      } else if (pes(t)(y)(x)(key) == pes(t)(y)(x)(s"${dstReg}_RI")) {
        // source is the register itslef from the previous cycle
        ret = "result"
      } else {
        ret = findSource("OUT_REG", pes(t)(y)(x)(key), pes(t)(y)(x))
        en = true
      }
      outRegsEnSeq = outRegsEnSeq.updated(dstReg match {
        case "NR" => 0
        case "SR" => 1
        case "WR" => 2
        case "ER" => 3
      }, en)
      ret
    }

    def rfWpSelector(dstReg: Int) = {
      val key = s"R${dstReg}_RO"
      if (pes(t)(y)(x)(key).isEmpty()) {
        // default
        ""
      } else if (pes(t)(y)(x)(key) == pes(t)(y)(x)(s"R${dstReg}_RI")) {
        // source is the register itslef from the previous cycle
        ""
      } else {
        findSource("RF", pes(t)(y)(x)(key), pes(t)(y)(x))
      }
    }

    def fuSrcSelector(operand: String) = { 
      if (pes(t)(y)(x)("FU0").isEmpty()) {
        // default
        "north"
      } else {
        val aOperandNodePattern = "\\((\\d+)-".r // Regex to find one or more digits between '(' and '-'
        val bOperandNodePattern = "-(\\d+)-\\|".r // Regex to find one or more digits between '-' and '-|'
        val aNode = aOperandNodePattern.findFirstMatchIn(pes(t)(y)(x)("FU0")).get.group(1)
        val bNode = bOperandNodePattern.findFirstMatchIn(pes(t)(y)(x)("FU0")).get.group(1)
        if (operand == "a") {
          findSource("FU", aNode, pes(t)(y)(x))
        } else {
          findSource("FU", bNode, pes(t)(y)(x))
        }
      }
    }
   
    val rfWritePortsSource = Seq.tabulate(params.rfSize)(x => x).map(i => rfWpSelector(i)).filter(_ != "").padTo(params.rfWritePorts, "result").map(ConfigurationData.rfWritePortsSrcSelDataStringMatcher)
    val rfWritePorts = Seq.tabulate(params.rfSize)(i => pes(t)(y)(x)(s"R${i}_RO")).zipWithIndex.filter(!_._1.isEmpty()).map(_._2)
    val rfWriteEn = Seq.tabulate(params.rfSize)(i => rfWritePorts.contains(i))
    val rfWritePortsPadded = rfWritePorts.padTo(params.rfWritePorts, 0)

    val outRegsSel = OutRegsSrcSelData(
      north = ConfigurationData.outRegsSrcSelDataStringMatcher(outRegSelector("NR")),
      south = ConfigurationData.outRegsSrcSelDataStringMatcher(outRegSelector("SR")),
      west = ConfigurationData.outRegsSrcSelDataStringMatcher(outRegSelector("WR")),
      east = ConfigurationData.outRegsSrcSelDataStringMatcher(outRegSelector("ER"))
    )

    val outRegsEn = OutRegsEnData(outRegsEnSeq(0), outRegsEnSeq(1), outRegsEnSeq(2), outRegsEnSeq(3))

    val rfWritePortsSel = RfWritePortsSrcSelData(
      ports = rfWritePortsSource
    )

    val fuSrcSel = FuSrcSelData(
      a = ConfigurationData.fuSrcSelDataStringMatcher(fuSrcSelector("a")),
      b = ConfigurationData.fuSrcSelDataStringMatcher(fuSrcSelector("b"))
    )

    val rfReadPortsPadded = rfReadPorts.padTo(params.rfReadPorts, 0)

    ProcessingElementConfigData(
      op = fuOp,
      outRegsSel = outRegsSel,
      outRegsEn = outRegsEn,
      rfWritePortsSel = rfWritePortsSel,
      fuSrcSel = fuSrcSel,
      rfWriteAddr = rfWritePortsPadded,
      rfReadAddr = rfReadPortsPadded,
      rfWriteEn = rfWriteEn
    )
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

object MorpherMapping extends App {
  val mappingFile = "mapping.csv"
  val latencyFile = "latency.txt"
  val params = CommonBubbleteaParams.mini2x2
  val socParams = SocParams(
    cacheLineBytes = 64,
    frontBusAddressBits = 32,
    frontBusDataBits = 64,
    xLen = 64
  )
  val parser = new MorpherMappingParser(params, socParams)

  val (pes, loads, stores) = parser.csv(mappingFile)
  val initiationInterval = pes.length
  val latency = parser.latency(latencyFile)

  // Placeholder
  val streamingEngine = StreamingEngineStaticConfigurationData(
    loadStreamsConfigured = Seq.fill(params.maxSimultaneousLoadMacroStreams)(false),
    storeStreamsConfigured = Seq.fill(params.maxSimultaneousStoreMacroStreams)(false),
    storeStreamsVecLengthMinusOne = Seq.fill(params.maxSimultaneousStoreMacroStreams)(0)
  )

  val (delayer, maxStoreDelay) = parser.delay(loads, stores, latency, initiationInterval)

  val streamingStage = StreamingStageStaticConfigurationData(
    streamingEngine = streamingEngine,
    initiationIntervalMinusOne = initiationInterval - 1,
    storeStreamsFixedDelay = maxStoreDelay,
    // Placeholders
    loadRemaperSwitchesSetup = Seq.fill(params.maxSimultaneousLoadMacroStreams, params.macroStreamDepth)(RemaperMask(false, 0, Side.North, 0)),
    storeRemaperSwitchesSetup = Seq.fill(params.maxSimultaneousStoreMacroStreams, params.macroStreamDepth)(RemaperMask(false, 0, Side.North, 0))
  )

  val mesh = Seq.tabulate(initiationInterval, params.meshRows, params.meshColumns) { (t, y, x) =>
    parser.pe(pes, t, y, x)
  }

  val static = AcceleratorStaticConfigurationData(
    streamingStage = streamingStage,
    mesh = mesh,
    delayer = delayer
  )

  val configuration = ConfigurationData(
    static = static,
    // Placeholder
    streamingEngineInstructions = Seq.fill(params.maxConfigurationInstructions)(StreamingEngineCompressedConfigurationChannelData(
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
  )

  val json = write(configuration, indent = 2)
  //println(json)

  val fileWriter = new FileWriter("configuration.json")
  fileWriter.write(json)
  fileWriter.close()
}
