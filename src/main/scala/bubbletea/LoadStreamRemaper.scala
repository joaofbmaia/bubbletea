package bubbletea

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.RegEnable

object LoadStreamRemaper {
  val latency = 1
}

class LoadStreamRemaper[T <: Data](params: BubbleteaParams[T]) extends Module {
  import LoadStreamRemaper._
  assert(params.maxSimultaneousLoadMacroStreams * params.macroStreamDepth == params.maxInitiationInterval * (2 * params.meshRows + 2 * params.meshColumns), "Number of macro stream elements must equal number of micro stream elements")
  val io = IO(new Bundle {
    val macroStreamsIn = Flipped(Decoupled(Vec(params.maxSimultaneousLoadMacroStreams, Vec(params.macroStreamDepth, params.dataType))))
    val microStreamsOut = Decoupled(Vec(params.maxInitiationInterval, new MeshData(params)))
    val remaperSwitchesSetup = Input(Vec(params.numberOfLoadRemaperSwitchStages, Vec(params.numberOfLoadRemaperSwitchesPerStage, Bool())))
  })

  val macroStreams = io.macroStreamsIn.bits
  val microStreams = Wire(Vec(params.maxInitiationInterval, new MeshData(params)))

  val upstreamValid = Wire(Bool())
  val upstreamReady = Wire(Bool())
  val downstreamValid = Wire(Bool())
  val downstreamReady = Wire(Bool())

  val permutationNetwork = Module(new PermutationNetwork(params.dataType, params.numberOfLoadRemaperElements))

  permutationNetwork.io.in := macroStreams.asTypeOf(Vec(params.numberOfLoadRemaperElements, params.dataType))
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