package bubbletea

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage

class BenesPermutationNetworkTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "BenesPermutationNetwork"

  it should "do something" in {
    test(new BenesPermutationNetwork(UInt(32.W), 16)).withAnnotations(Seq()) { dut =>
    // test body here
    }
  }

  it should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new BenesPermutationNetwork(UInt(32.W), 16),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
}
