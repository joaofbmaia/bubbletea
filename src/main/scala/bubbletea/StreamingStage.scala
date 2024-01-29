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

    val meshDataOut = Output(new MeshData(config))
    val meshDataIn = Input(new MeshData(config))

    val meshRun = Input(Bool())
    val meshFire = Output(Bool())

    val initiationIntervalMinusOne = Input(UInt(log2Ceil(config.maxInitiationInterval).W))
    
    val streamingEngineCtrl = Flipped(new StreamingEngineCtrlBundle(config))
    val streamingEngineCfg = Flipped(Decoupled(new StreamingEngineCfgBundle(config)))
    val loadRemaperSwitchesSetup =
      Input(Vec(config.numberOfLoadRemaperSwitchStages, Vec(config.numberOfLoadRemaperSwitchesPerStage, Bool())))
    val storeRemaperSwitchesSetup =
      Input(Vec(config.numberOfStoreRemaperSwitchStages, Vec(config.numberOfStoreRemaperSwitchesPerStage, Bool())))
  })

  val meshFire = Wire(Bool())

  // Modulo cycle counter
  val currentModuloCycle = RegInit(0.U(log2Ceil(config.maxInitiationInterval).W))

  val lastCycle = currentModuloCycle === io.initiationIntervalMinusOne

  when(meshFire && lastCycle) {
    currentModuloCycle := 0.U
  }.elsewhen(meshFire) {
    currentModuloCycle := currentModuloCycle + 1.U
  }

  // Register for buffering the micro streams from the multiple cycles
  val microStreamsReg = Reg(Vec(config.maxInitiationInterval, new MeshData(config)))

  when(meshFire) {
    microStreamsReg(currentModuloCycle) := io.meshDataIn
  }

  // The microStreams wire contains the micro streams from previous cycles and the current cycle
  val microStreams = Wire(Vec(config.maxInitiationInterval, new MeshData(config)))
  for (i <- 0 until config.maxInitiationInterval) {
    when(currentModuloCycle === i.U) {
      microStreams(i) := io.meshDataIn
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

  // Connect control signals
  streamingEngine.io.control :<>= io.streamingEngineCtrl
  streamingEngine.io.cfg :<>= io.streamingEngineCfg
  loadRemaper.io.remaperSwitchesSetup := io.loadRemaperSwitchesSetup
  storeRemaper.io.remaperSwitchesSetup := io.storeRemaperSwitchesSetup

  // Connect memory interface
  io.memory :<>= streamingEngine.io.memory

  // Connect Dataflow
  loadRemaper.io.macroStreamsIn :<>= streamingEngine.io.loadStreams
  io.meshDataOut := loadRemaper.io.microStreamsOut.bits(currentModuloCycle)
  storeRemaper.io.microStreamsIn.bits := microStreams
  streamingEngine.io.storeStreams :<>= storeRemaper.io.macroStreamsOut

  // Control
  val microStreamsRegReady = Wire(Bool())
  microStreamsRegReady := !lastCycle || storeRemaper.io.microStreamsIn.ready //if (lastCycle) storeRemaper.io.microStreamsIn.ready else true.B
  meshFire := io.meshRun && loadRemaper.io.microStreamsOut.valid && microStreamsRegReady
  loadRemaper.io.microStreamsOut.ready := lastCycle && meshFire //lastCycle && storeRemaper.io.microStreamsIn.ready
  storeRemaper.io.microStreamsIn.valid := meshFire
  io.meshFire := meshFire
}
