package bubbletea

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage

class AcceleratorTopTest extends AnyFlatSpec with ChiselScalatestTester {
  "AcceleratorTop" should "do something" in {
    test(new AcceleratorTop(CommonBubbleteaParams.minimalConfig)).withAnnotations(Seq()) { dut =>
    // test body here
    }
  }

  it should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new AcceleratorTop(CommonBubbleteaParams.minimalConfig),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
}
