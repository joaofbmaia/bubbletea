package bubbletea

import chisel3._
import chisel3.util._

class Crossbar[T <: Data](n: Int, m: Int, t: T) extends Module {
  val io = IO(new Bundle {
    val in = Input(Vec(n, t))
    val sel = Input(Vec(n, UInt(log2Ceil(m).W)))
    val out = Output(Vec(m, t))
  })

  io.out := VecInit(Seq.fill(m)(0.U.asTypeOf(t))) // Initialize outputs to 0

  for(i <- 0 until n) {
    when(io.sel(i) < m.U) {
      io.out(io.sel(i)) := io.in(i)
    }
  }
}