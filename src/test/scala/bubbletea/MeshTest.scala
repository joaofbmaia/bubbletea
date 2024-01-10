package bubbletea

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage

class MeshTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Mesh"

  it should "do something" in {
    test(new Mesh(CommonAcceleratorConfigs.defaultConfig)).withAnnotations(Seq()) { dut =>
    // test body here
    }
  }

  it should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new Mesh(CommonAcceleratorConfigs.defaultConfig),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
}
