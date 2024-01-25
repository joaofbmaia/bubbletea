package bubbletea

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.Decoupled
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}

class StreamingStage[T <: Data](config: AcceleratorConfig[T]) extends Module {
  assert(
    config.maxSimultaneousMacroStreams * config.macroStreamDepth == config.maxInitiationInterval * (2 * config.meshRows + 2 * config.meshColumns),
    "Number of macro stream elements must equal number of micro stream elements"
  )
  val io = IO(new Bundle {
    val macroStreamBuffer =
      Flipped(Decoupled((Vec(config.maxSimultaneousMacroStreams, Vec(config.macroStreamDepth, config.dataType)))))
    val meshOut = Decoupled(new MeshData(config))

    val initiationIntervalMinusOne = Input(UInt(log2Ceil(config.maxInitiationInterval).W))
    val remaperSwitchesSetup =
      Input(Vec(config.numberOfRempaerSwitchStages, Vec(config.numberOfRemaperSwitchesPerStage, Bool())))
  })

  // Modulo cycle counter
  val currentModuloCycle = RegInit(0.U(log2Ceil(config.maxInitiationInterval).W))

  when(io.meshOut.fire && (currentModuloCycle === io.initiationIntervalMinusOne)) {
    currentModuloCycle := 0.U
  }.elsewhen(io.meshOut.fire) {
    currentModuloCycle := currentModuloCycle + 1.U
  }

  // Instantiate the stram remaper
  val remaper = Module(new StreamRemaper(config))

  // Instantiate the streaming engine
  val streamingEngine = Module(new StreamingEngine(config))

  // Connections

  remaper.io.macroStreamsIn :<>= io.macroStreamBuffer

  io.meshOut.bits := remaper.io.microStreamsOut.bits(currentModuloCycle)

  io.meshOut.valid := remaper.io.microStreamsOut.valid

  remaper.io.microStreamsOut.ready := io.meshOut.fire && (currentModuloCycle === io.initiationIntervalMinusOne)

  remaper.io.remaperSwitchesSetup := io.remaperSwitchesSetup

}
