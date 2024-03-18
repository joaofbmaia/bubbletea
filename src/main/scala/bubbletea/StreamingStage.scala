package bubbletea

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.Decoupled
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}

class StreamingStageStaticConfigurationBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  val streamingEngine = new StreamingEngineStaticConfigurationBundle(params)
  val initiationIntervalMinusOne = UInt(log2Ceil(params.maxInitiationInterval).W)
  val storeStreamsFixedDelay = UInt(log2Ceil(params.maxDelayIntervals + 1).W)
  val loadRemaperSwitchesSetup = Vec(params.numberOfLoadRemaperSwitchStages, Vec(params.numberOfLoadRemaperSwitchesPerStage, Bool()))
  val storeRemaperSwitchesSetup = Vec(params.numberOfStoreRemaperSwitchStages, Vec(params.numberOfStoreRemaperSwitchesPerStage, Bool()))
}

class StreamingStageControlBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  val reset = Output(Bool())
  val meshRun = Output(Bool())
  val meshFire = Input(Bool())
  val currentModuloCycle = Input(UInt(log2Ceil(params.maxInitiationInterval).W))
  val done = Input(Bool())
}

class StreamingStage[T <: Data](params: BubbleteaParams[T], socParams: SocParams) extends Module {
  assert(
    params.maxSimultaneousLoadMacroStreams * params.macroStreamDepth == params.maxInitiationInterval * (2 * params.meshRows + 2 * params.meshColumns),
    "Number of macro stream elements must equal number of micro stream elements"
  )
  val io = IO(new Bundle {
    val memory = AXI4Bundle(new AXI4BundleParameters(socParams.frontBusAddressBits, socParams.frontBusDataBits, 1))

    val meshDataOut = Output(new MeshData(params))
    val meshDataIn = Input(new MeshData(params))

    val control = Flipped(new StreamingStageControlBundle(params))
  
    val staticConfiguration = Input(new StreamingStageStaticConfigurationBundle(params))

    val seConfigurationChannel = Flipped(Decoupled(new StreamingEngineConfigurationChannelBundle(params)))
  })

  val meshFire = Wire(Bool())

  // Modulo cycle counter
  val currentModuloCycle = withReset(reset.asBool || io.control.reset)(RegInit(0.U(log2Ceil(params.maxInitiationInterval).W)))

  val lastCycle = currentModuloCycle === io.staticConfiguration.initiationIntervalMinusOne

  when(meshFire && lastCycle) {
    currentModuloCycle := 0.U
  }.elsewhen(meshFire) {
    currentModuloCycle := currentModuloCycle + 1.U
  }

  // Register for buffering the micro streams from the multiple cycles
  val microStreamsReg = Reg(Vec(params.maxInitiationInterval, new MeshData(params)))

  when(meshFire) {
    microStreamsReg(currentModuloCycle) := io.meshDataIn
  }

  // The microStreams wire contains the micro streams from previous cycles and the current cycle
  val microStreams = Wire(Vec(params.maxInitiationInterval, new MeshData(params)))
  for (i <- 0 until params.maxInitiationInterval) {
    when(currentModuloCycle === i.U) {
      microStreams(i) := io.meshDataIn
    } .otherwise {
      microStreams(i) := microStreamsReg(i)
    }
  }

  // Instantiate the load stream remaper
  val loadRemaper = Module(new LoadStreamRemaper(params))

  // Instantiate the store stream remaper
  val storeRemaper = Module(new StoreStreamRemaper(params))

  // Instantiate the streaming engine
  val streamingEngine = Module(new StreamingEngine(params, socParams))

  // Configuration and reset
  streamingEngine.io.control.reset := io.control.reset
  streamingEngine.io.staticConfiguration := io.staticConfiguration.streamingEngine
  streamingEngine.io.configurationChannel :<>= io.seConfigurationChannel
  loadRemaper.io.remaperSwitchesSetup := io.staticConfiguration.loadRemaperSwitchesSetup
  storeRemaper.io.remaperSwitchesSetup := io.staticConfiguration.storeRemaperSwitchesSetup

  // Connect memory interface
  io.memory :<>= streamingEngine.io.memory

  // Connect Dataflow
  loadRemaper.io.macroStreamsIn :<>= streamingEngine.io.loadStreams
  io.meshDataOut := loadRemaper.io.microStreamsOut.bits(currentModuloCycle)
  storeRemaper.io.microStreamsIn.bits := microStreams
  streamingEngine.io.storeStreams :<>= storeRemaper.io.macroStreamsOut

  // Store steam fixed delay (the register delays in the mesh subtract from this delay on the store side)
  val storeDelayCounter = withReset(reset.asBool || io.control.reset)(RegInit(0.U(log2Ceil(params.maxDelayIntervals + 1).W)))
  val storeDelayDone = WireInit(storeDelayCounter === io.staticConfiguration.storeStreamsFixedDelay)
  when(meshFire && currentModuloCycle === io.staticConfiguration.initiationIntervalMinusOne && !storeDelayDone) {
    storeDelayCounter := storeDelayCounter + 1.U
  }

  // Control
  val microStreamsRegReady = Wire(Bool())
  microStreamsRegReady := !lastCycle || storeRemaper.io.microStreamsIn.ready //if (lastCycle) storeRemaper.io.microStreamsIn.ready else true.B
  meshFire := io.control.meshRun && (loadRemaper.io.microStreamsOut.valid || streamingEngine.io.control.loadStreamsDone) && (!storeDelayDone || microStreamsRegReady)
  loadRemaper.io.microStreamsOut.ready := lastCycle && meshFire //lastCycle && storeRemaper.io.microStreamsIn.ready
  storeRemaper.io.microStreamsIn.valid := lastCycle && meshFire && storeDelayDone
  io.control.meshFire := meshFire
  io.control.currentModuloCycle := currentModuloCycle
  io.control.done := streamingEngine.io.control.storeStreamsDone
}
