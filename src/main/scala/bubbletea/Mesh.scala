package bubbletea

import chisel3._

class Mesh[T <: Data](config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {
    // sequencer IO
    val cycleFire = Input(Bool())
    val sequencerReset = Input(Bool())

    // mesh IO
    val inN = Input(Vec(config.meshColumns, config.dataType))
    val inS = Input(Vec(config.meshColumns, config.dataType))
    val inW = Input(Vec(config.meshRows, config.dataType))
    val inE = Input(Vec(config.meshRows, config.dataType))

    val outN = Output(Vec(config.meshColumns, config.dataType))
    val outS = Output(Vec(config.meshColumns, config.dataType))
    val outW = Output(Vec(config.meshRows, config.dataType))
    val outE = Output(Vec(config.meshRows, config.dataType))

    // reconfiguration
    val rcfgStart = Input(Bool())
    val rcfgDone = Output(Bool())
  })

  io.outN := io.inN
  io.outS := io.inS
  io.outW := io.inW
  io.outE := io.inE

  io.rcfgDone := true.B
}
