package bubbletea

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage

class PermutationNetworkTest extends AnyFlatSpec with ChiselScalatestTester {
  "PermutationNetwork" should "do something" in {
    test(new PermutationNetwork(UInt(32.W), 16)).withAnnotations(Seq()) { dut =>
    // test body here
    }
  }

  it should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new PermutationNetwork(UInt(32.W), 16),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }

  "PermutationNetwork helper object" should "calculate switch settings" in {
    // println(PermutationNetwork.generateSwitchSettings(Seq(3, 5, 2, 4, 0, 7, 1, 6)))
    // println(PermutationNetwork.generateSwitchSettings(Seq(0, 1, 2, 3, 4, 5, 6, 7)))
    // println(PermutationNetwork.generateSwitchSettings(Seq(7, 6, 5, 4, 3, 2, 1, 0)))

    println(PermutationNetworkUtils.generateSwitchSettingsFromDstMask(
      Seq(
        Seq(
          DstMask(used = true, side = Side.North, index = 0, moduloCycle = 0),
          DstMask(used = true, side = Side.North, index = 1, moduloCycle = 0),
          DstMask(used = true, side = Side.South, index = 0, moduloCycle = 0),
          DstMask(used = true, side = Side.South, index = 1, moduloCycle = 0),
          DstMask(used = true, side = Side.North, index = 0, moduloCycle = 1),
          DstMask(used = true, side = Side.North, index = 1, moduloCycle = 1),
          DstMask(used = true, side = Side.South, index = 0, moduloCycle = 1),
          DstMask(used = true, side = Side.South, index = 1, moduloCycle = 1)
        ),
        Seq(
          DstMask(used = true, side = Side.West, index = 0, moduloCycle = 0),
          DstMask(used = true, side = Side.West, index = 1, moduloCycle = 1),
          DstMask(used = true, side = Side.East, index = 0, moduloCycle = 0),
          DstMask(used = true, side = Side.East, index = 1, moduloCycle = 1),
          DstMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
          DstMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
          DstMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
          DstMask(used = false, side = Side.East, index = 0, moduloCycle = 0)
        )
      ),
      rows = 2,
      columns = 2
    ))
  }
}
