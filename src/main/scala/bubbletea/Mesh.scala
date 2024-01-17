package bubbletea

import chisel3._

class MeshData[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  val north = Vec(config.meshColumns, config.dataType)
  val south = Vec(config.meshColumns, config.dataType)
  val west = Vec(config.meshRows, config.dataType)
  val east = Vec(config.meshRows, config.dataType)
}

class Mesh[T <: Data](config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {
    // sequencer IO
    val cycleFire = Input(Bool())
    val sequencerReset = Input(Bool())

    // mesh IO
    val in = Input(new MeshData(config))
    val out = Output(new MeshData(config))

    // reconfiguration
    val rcfgStart = Input(Bool())
    val rcfgDone = Output(Bool())
  })

  io.out.north := io.in.north
  io.out.south := io.in.south
  io.out.west := io.in.west
  io.out.east := io.in.east

  io.rcfgDone := true.B
}
