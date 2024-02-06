package bubbletea

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage

class MeshWithDelaysTest extends AnyFlatSpec with ChiselScalatestTester {
  "MeshWithDelays" should "do something" in {
    test(new MeshWithDelays(CommonBubbleteaParams.minimalConfig)).withAnnotations(Seq()) { dut =>
    // test body here
    }
  }

  it should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new MeshWithDelays(CommonBubbleteaParams.minimalConfig),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
}
