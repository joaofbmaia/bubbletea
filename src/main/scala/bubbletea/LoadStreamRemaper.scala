package bubbletea

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.RegEnable

object LoadStreamRemaper {
  val latency = 1
}

class LoadStreamRemaper[T <: Data](config: AcceleratorConfig[T]) extends Module {
  import LoadStreamRemaper._
  assert(config.maxSimultaneousLoadMacroStreams * config.macroStreamDepth == config.maxInitiationInterval * (2 * config.meshRows + 2 * config.meshColumns), "Number of macro stream elements must equal number of micro stream elements")
  val io = IO(new Bundle {
    val macroStreamsIn = Flipped(Decoupled(Vec(config.maxSimultaneousLoadMacroStreams, Vec(config.macroStreamDepth, config.dataType))))
    val microStreamsOut = Decoupled(Vec(config.maxInitiationInterval, new MeshData(config)))
    val remaperSwitchesSetup = Input(Vec(config.numberOfLoadRemaperSwitchStages, Vec(config.numberOfLoadRemaperSwitchesPerStage, Bool())))
  })

  val macroStreams = io.macroStreamsIn.bits
  val microStreams = Wire(Vec(config.maxInitiationInterval, new MeshData(config)))

  val upstreamValid = Wire(Bool())
  val upstreamReady = Wire(Bool())
  val downstreamValid = Wire(Bool())
  val downstreamReady = Wire(Bool())

  val permutationNetwork = Module(new PermutationNetwork(config.dataType, config.numberOfLoadRemaperElements))

  permutationNetwork.io.in := macroStreams.asTypeOf(Vec(config.numberOfLoadRemaperElements, config.dataType))
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