package bubbletea

import chisel3._
import chisel3.util.Decoupled
import chisel3.util.RegEnable

object StoreStreamRemaper {
  val latency = 1
}

class StoreStreamRemaper[T <: Data](params: BubbleteaParams[T]) extends Module {
  import StoreStreamRemaper._
  val io = IO(new Bundle {
    val microStreamsIn = Flipped(Decoupled(Vec(params.maxInitiationInterval, new MeshData(params))))
    val macroStreamsOut = Decoupled(Vec(params.maxSimultaneousStoreMacroStreams, Vec(params.macroStreamDepth, params.dataType)))
    assert(microStreamsIn.bits.getWidth >= macroStreamsOut.bits.getWidth, "Number of macro stream elements must not be larger than number of micro stream elements")
    val remaperSwitchesSetup = Input(Vec(params.numberOfStoreRemaperSwitchStages, Vec(params.numberOfStoreRemaperSwitchesPerStage, Bool())))
  })

  val microStreams = io.microStreamsIn.bits
  val macroStreams = Wire(Vec(params.maxSimultaneousStoreMacroStreams, Vec(params.macroStreamDepth, params.dataType)))

  val upstreamValid = Wire(Bool())
  val upstreamReady = Wire(Bool())
  val downstreamValid = Wire(Bool())
  val downstreamReady = Wire(Bool())

  val permutationNetwork = Module(new PermutationNetwork(params.dataType, params.numberOfStoreRemaperElements))

  permutationNetwork.io.in := microStreams.asTypeOf(Vec(params.numberOfStoreRemaperElements, params.dataType))
  permutationNetwork.io.select := io.remaperSwitchesSetup
  macroStreams := permutationNetwork.io.out.asTypeOf(macroStreams) // this cast will truncate the output if the number of microstream elements is larger than the number of macrostream elements

  // Registered Hadshake Protocol
  // https://www.itdev.co.uk/blog/pipelining-axi-buses-registered-ready-signals
  upstreamValid := io.microStreamsIn.valid
  downstreamValid := RegEnable(upstreamValid, false.B, upstreamReady)
  upstreamReady := downstreamReady || !downstreamValid
  downstreamReady := io.macroStreamsOut.ready

  io.microStreamsIn.ready := upstreamReady
  io.macroStreamsOut.valid := downstreamValid
  io.macroStreamsOut.bits := RegEnable(macroStreams, upstreamReady)
}