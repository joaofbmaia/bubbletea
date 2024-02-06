package bubbletea

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage

class SeConfiguratorTest extends AnyFlatSpec with ChiselScalatestTester {
  "SeConfigurator" should "do something" in {
    test(new SeConfigurator(CommonBubbleteaParams.minimalConfig)).withAnnotations(Seq()) { dut =>
    // test body here
    }
  }

  it should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new SeConfigurator(CommonBubbleteaParams.minimalConfig),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
}
