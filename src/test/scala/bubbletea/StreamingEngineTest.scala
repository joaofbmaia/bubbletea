package bubbletea

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage

class StreamingEngineTest extends AnyFlatSpec with ChiselScalatestTester {
  // "StreamingEngine" should "do something" in {
  //   test(new StreamingEngine(CommonAcceleratorConfigs.minimalConfig)).withAnnotations(Seq()) { dut =>
  //   // test body here
  //   }
  // }

  "StreamingEngine" should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new StreamingEngine(CommonAcceleratorConfigs.minimalConfig),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
}
