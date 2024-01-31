package bubbletea

import chisel3._
import chisel3.util.Valid
import chisel3.util.log2Ceil
import chisel3.util.UIntToOH
import chisel3.util.RegEnable
import chisel3.util.Mux1H

/* Delays data by latency cycles. completely stalls when input is invalid. output valid = input valid */
// TODO: can we clock gate unused registers, when latency < maxLatency ?
class VariablePipe[T <: Data](t: T, maxLatency: Int) extends Module {
  require(maxLatency >= 1, "VariablePipe max latency must be greater than or equal to one!")
  
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val in = Input(t)
    val out = Output(t)
    val latency = Input(UInt(log2Ceil(maxLatency + 1).W))
  })

  val latencySelWidth = log2Ceil(maxLatency + 1)
  
  val regsNext = Wire(Vec(maxLatency, t))
  val regs = RegEnable(regsNext, io.valid)

  val sel = UIntToOH(io.latency, maxLatency + 1)

  regsNext(0) := io.in

  for (i <- 0 until (maxLatency - 1)) {
    regsNext(i + 1) := regs(i)
  }

  def muxMap(n: Int) = {
    if (n == 0) {
      sel(n) -> io.in
    }
    else sel(n) -> regs(n - 1)
  }

  io.out := Mux1H(Seq.tabulate(maxLatency + 1)(muxMap))
}
