package bubbletea

import chisel3._

class AcceleratorTop[T <: Data](config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {

  })

  val mesh = Module(new Mesh(config))

  
}