package bubbletea

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.Decoupled
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}

class AcceleratorConfigBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  val initiationIntervalMinusOne = UInt(log2Ceil(config.maxInitiationInterval).W)
  val mesh = Vec(config.meshRows, Vec(config.meshColumns, new ProcessingElementConfigBundle(config)))
  val delayer = new DelayerConfigBundle(config)
  //val streamingEngine = ??? //TODO
  val loadRemaperSwitchesSetup = Vec(config.numberOfLoadRemaperSwitchStages, Vec(config.numberOfLoadRemaperSwitchesPerStage, Bool()))
  val storeRemaperSwitchesSetup = Vec(config.numberOfStoreRemaperSwitchStages, Vec(config.numberOfStoreRemaperSwitchesPerStage, Bool()))
}

class AcceleratorTop[T <: Data: Arithmetic](config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {
    val configuration = Input(new AcceleratorConfigBundle(config))

    // TODO: add streaming engine configuration interface
    val streamingEngineCtrl = Flipped(new StreamingEngineCtrlBundle(config))
    val streamingEngineCfg = Flipped(Decoupled(new StreamingEngineCfgBundle(config)))

    val run = Input(Bool())

    val memory = AXI4Bundle(new AXI4BundleParameters(config.seAddressWidth, config.seAxiDataWidth, 1))
  })

  val meshWithDelays = Module(new MeshWithDelays(config))

  val streamingStage = Module(new StreamingStage(config))

  io.memory :<>= streamingStage.io.memory

  streamingStage.io.meshRun := io.run
  meshWithDelays.io.fire := streamingStage.io.meshFire

  meshWithDelays.io.in := streamingStage.io.meshDataOut
  streamingStage.io.meshDataIn := meshWithDelays.io.out

  // Configuration

  streamingStage.io.initiationIntervalMinusOne := io.configuration.initiationIntervalMinusOne
  streamingStage.io.streamingEngineCtrl :<>= io.streamingEngineCtrl
  streamingStage.io.streamingEngineCfg :<>= io.streamingEngineCfg
  streamingStage.io.loadRemaperSwitchesSetup := io.configuration.loadRemaperSwitchesSetup
  streamingStage.io.storeRemaperSwitchesSetup := io.configuration.storeRemaperSwitchesSetup

  meshWithDelays.io.meshConfiguration := io.configuration.mesh
  meshWithDelays.io.delayerConfiguration := io.configuration.delayer
}
