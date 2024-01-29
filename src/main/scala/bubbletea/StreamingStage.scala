package bubbletea

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.Decoupled
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}

class StreamingStage[T <: Data](config: AcceleratorConfig[T]) extends Module {
  assert(
    config.maxSimultaneousLoadMacroStreams * config.macroStreamDepth == config.maxInitiationInterval * (2 * config.meshRows + 2 * config.meshColumns),
    "Number of macro stream elements must equal number of micro stream elements"
  )
  val io = IO(new Bundle {
    val memory = AXI4Bundle(new AXI4BundleParameters(config.seAddressWidth, config.seAxiDataWidth, 1))
    val meshOut = Decoupled(new MeshData(config))
    val meshIn = Flipped(Decoupled(new MeshData(config)))

    val initiationIntervalMinusOne = Input(UInt(log2Ceil(config.maxInitiationInterval).W))

    val streamingEngineCtrl = Flipped(new StreamingEngineCtrlBundle(config))
    val streamingEngineCfg = Flipped(Decoupled(new StreamingEngineCfgBundle(config)))
    val loadRemaperSwitchesSetup =
      Input(Vec(config.numberOfLoadRemaperSwitchStages, Vec(config.numberOfLoadRemaperSwitchesPerStage, Bool())))
    val storeRemaperSwitchesSetup =
      Input(Vec(config.numberOfStoreRemaperSwitchStages, Vec(config.numberOfStoreRemaperSwitchesPerStage, Bool())))
  })

  // Modulo cycle counter
  val currentModuloCycle = RegInit(0.U(log2Ceil(config.maxInitiationInterval).W))

  when(io.meshOut.fire && io.meshIn.fire && (currentModuloCycle === io.initiationIntervalMinusOne)) {
    currentModuloCycle := 0.U
  }.elsewhen(io.meshOut.fire && io.meshIn.fire) {
    currentModuloCycle := currentModuloCycle + 1.U
  }

  // Register for buffering the micro streams from the multiple cycles
  val microStreamsReg = Reg(Vec(config.maxInitiationInterval, new MeshData(config)))

  when(io.meshIn.fire) {
    microStreamsReg(currentModuloCycle) := io.meshIn.bits
  }

  // The microStreams wire contains the micro streams from previous cycles and the current cycle
  val microStreams = Wire(Vec(config.maxInitiationInterval, new MeshData(config)))
  for (i <- 0 until config.maxInitiationInterval) {
    when(currentModuloCycle === i.U) {
      microStreams(i) := io.meshIn.bits
    } .otherwise {
      microStreams(i) := microStreamsReg(i)
    }
  }

  // Instantiate the load stream remaper
  val loadRemaper = Module(new LoadStreamRemaper(config))

  // Instantiate the store stream remaper
  val storeRemaper = Module(new StoreStreamRemaper(config))

  // Instantiate the streaming engine
  val streamingEngine = Module(new StreamingEngine(config))

  // Connections
  streamingEngine.io.control :<>= io.streamingEngineCtrl
  streamingEngine.io.cfg :<>= io.streamingEngineCfg

  io.memory :<>= streamingEngine.io.memory


  loadRemaper.io.macroStreamsIn :<>= streamingEngine.io.loadStreams

  io.meshOut.bits := loadRemaper.io.microStreamsOut.bits(currentModuloCycle)

  io.meshOut.valid := loadRemaper.io.microStreamsOut.valid

  loadRemaper.io.microStreamsOut.ready := io.meshOut.fire && (currentModuloCycle === io.initiationIntervalMinusOne)

  loadRemaper.io.remaperSwitchesSetup := io.loadRemaperSwitchesSetup

  
  storeRemaper.io.microStreamsIn.bits := microStreams

  storeRemaper.io.microStreamsIn.valid := io.meshIn.valid && (currentModuloCycle === io.initiationIntervalMinusOne)

  // TODO: I think this is wrong.
  io.meshIn.ready := !(currentModuloCycle === io.initiationIntervalMinusOne) || storeRemaper.io.microStreamsIn.fire

  streamingEngine.io.storeStreams :<>= storeRemaper.io.macroStreamsOut

  storeRemaper.io.remaperSwitchesSetup := io.storeRemaperSwitchesSetup

}
