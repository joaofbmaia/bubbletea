package bubbletea

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.Decoupled

class StreamingStage[T <: Data](config: AcceleratorConfig[T]) extends Module {
  assert(config.maxMacroStreams * config.macroStreamDepth == config.maxInitiationInterval * (2 * config.meshRows + 2 * config.meshColumns), "Number of macro stream elements must equal number of micro stream elements")
  val io = IO(new Bundle {
    val macroStreamBuffer = Flipped(Decoupled((Vec(config.maxMacroStreams, Vec(config.macroStreamDepth, config.dataType)))))
    val meshOut = Decoupled(new MeshData(config))

    val initiationIntervalMinusOne = Input(UInt(log2Ceil(config.maxInitiationInterval).W))
    val remaperSwitchesSetup = Input(Vec(config.numberOfRempaerSwitchStages, Vec(config.numberOfRemaperSwitchesPerStage, Bool())))
  })

  val currentModuloCycle = RegInit(0.U(log2Ceil(config.maxInitiationInterval).W))

  when (io.meshOut.fire && (currentModuloCycle === io.initiationIntervalMinusOne)) {
    currentModuloCycle := 0.U
  } .elsewhen (io.meshOut.fire) {
    currentModuloCycle := currentModuloCycle + 1.U
  }

  val remaper = Module(new StreamRemaper(config))

  remaper.io.macroStreamsIn :<>= io.macroStreamBuffer

  io.meshOut.bits := remaper.io.microStreamsOut.bits(currentModuloCycle)
  
  io.meshOut.valid := remaper.io.microStreamsOut.valid
  
  remaper.io.microStreamsOut.ready := io.meshOut.fire && (currentModuloCycle === io.initiationIntervalMinusOne)

  remaper.io.remaperSwitchesSetup := io.remaperSwitchesSetup

}
