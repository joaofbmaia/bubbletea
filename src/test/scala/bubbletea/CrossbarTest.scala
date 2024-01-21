package bubbletea

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage

class CrossbarTest extends AnyFlatSpec with ChiselScalatestTester {
  val n = 8
  val m = 8
  val t = SInt(32.W)

  "Crossbar" should "work :')" in {
    test(new Crossbar(n, m, t)) { dut =>
      for(i <- 0 until n) {
        dut.io.in(i).poke((i*10).S)
        dut.io.sel(i).poke(i.U)
      }
      dut.clock.step(1)
      for(i <- 0 until m) {
        dut.io.out(i).expect((i*10).S)
      }
    }
  }

  it should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new Crossbar(n, m, t),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
}