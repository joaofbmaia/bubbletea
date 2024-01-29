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

    // testing only
    val storeStreams = Flipped(Decoupled((Vec(config.maxSimultaneousStoreMacroStreams, Vec(config.macroStreamDepth, config.dataType)))))

    val initiationIntervalMinusOne = Input(UInt(log2Ceil(config.maxInitiationInterval).W))

    val streamingEngineCtrl = Flipped(new StreamingEngineCtrlBundle(config))
    val streamingEngineCfg = Flipped(Decoupled(new StreamingEngineCfgBundle(config)))
    val remaperSwitchesSetup =
      Input(Vec(config.numberOfLoadRemaperSwitchStages, Vec(config.numberOfLoadRemaperSwitchesPerStage, Bool())))
  })

  // Modulo cycle counter
  val currentModuloCycle = RegInit(0.U(log2Ceil(config.maxInitiationInterval).W))

  when(io.meshOut.fire && (currentModuloCycle === io.initiationIntervalMinusOne)) {
    currentModuloCycle := 0.U
  }.elsewhen(io.meshOut.fire) {
    currentModuloCycle := currentModuloCycle + 1.U
  }

  // Instantiate the load stream remaper
  val loadRemaper = Module(new LoadStreamRemaper(config))

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

  loadRemaper.io.remaperSwitchesSetup := io.remaperSwitchesSetup

  // store not implemented yet
  // streamingEngine.io.storeStreams.bits := 0.U.asTypeOf(streamingEngine.io.storeStreams.bits)
  // streamingEngine.io.storeStreams.valid := true.B
  streamingEngine.io.storeStreams :<>= io.storeStreams

}
