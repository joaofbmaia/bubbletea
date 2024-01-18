package bubbletea

import chisel3._
import chisel3.util.log2Ceil
import scala.math.max
import chisel3.util.Decoupled
import chisel3.util.RegEnable

object StreamRemaper {
  val latency = 1
}

class StreamRemaper[T <: Data](config: AcceleratorConfig[T]) extends Module {
  import StreamRemaper._
  assert(config.maxMacroStreams * config.macroStreamDepth == config.maxInitiationInterval * (2 * config.meshRows + 2 * config.meshColumns), "Number of macro stream elements must equal number of micro stream elements")
  val io = IO(new Bundle {
    val macroStreamsIn = Flipped(Decoupled(Vec(config.maxMacroStreams, Vec(config.macroStreamDepth, config.dataType))))
    val microStreamsOut = Decoupled(Vec(config.maxInitiationInterval, new MeshData(config)))
    val remaperSwitchesSetup = Input(Vec(config.numberOfRempaerSwitchStages, Vec(config.numberOfRemaperSwitchesPerStage, Bool())))
  })

  val macroStreams = io.macroStreamsIn.bits
  val microStreams = Wire(Vec(config.maxInitiationInterval, new MeshData(config)))

  val upstreamValid = Wire(Bool())
  val upstreamReady = Wire(Bool())
  val downstreamValid = Wire(Bool())
  val downstreamReady = Wire(Bool())

  val permutationNetwork = Module(new BenesPermutationNetwork(config.dataType, config.numberOfRemaperElements))

  permutationNetwork.io.in := macroStreams.asTypeOf(Vec(config.numberOfRemaperElements, config.dataType))
  permutationNetwork.io.select := io.remaperSwitchesSetup
  microStreams := permutationNetwork.io.out.asTypeOf(microStreams)

  // Registered Hadshake Protocol
  // https://www.itdev.co.uk/blog/pipelining-axi-buses-registered-ready-signals
  upstreamValid := io.macroStreamsIn.valid
  downstreamValid := RegEnable(upstreamValid, false.B, upstreamReady)
  upstreamReady := downstreamReady || !downstreamValid
  downstreamReady := io.microStreamsOut.ready

  io.macroStreamsIn.ready := upstreamReady
  io.microStreamsOut.valid := downstreamValid
  io.microStreamsOut.bits := RegEnable(microStreams, upstreamReady)
}


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
