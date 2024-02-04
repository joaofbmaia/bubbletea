package bubbletea

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage

class SeConfiguratorTest extends AnyFlatSpec with ChiselScalatestTester {
  "SeConfigurator" should "do something" in {
    test(new SeConfigurator(CommonAcceleratorConfigs.minimalConfig)).withAnnotations(Seq()) { dut =>
    // test body here
    }
  }

  it should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new SeConfigurator(CommonAcceleratorConfigs.minimalConfig),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
}
