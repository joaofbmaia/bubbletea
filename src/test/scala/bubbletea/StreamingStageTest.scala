package bubbletea

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage

import chisel3.experimental.VecLiterals._
import chisel3.experimental.BundleLiterals._

class StreamingStageTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "StreamingStage"

  it should "do something" in {
    test(new StreamingStage(CommonAcceleratorConfigs.minimalConfig)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      val testRemaperSetup = PermutationNetwork.generateSwitchSettingsFromDstMask(
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
        rows = CommonAcceleratorConfigs.minimalConfig.meshRows,
        columns = CommonAcceleratorConfigs.minimalConfig.meshColumns
      )

      val testMacroStreams = Vec.Lit(
        Vec.Lit(1.U(8.W), 2.U(8.W), 3.U(8.W), 4.U(8.W), 5.U(8.W), 6.U(8.W), 7.U(8.W), 8.U(8.W)),
        Vec.Lit(9.U(8.W), 10.U(8.W), 11.U(8.W), 12.U(8.W), 13.U(8.W), 14.U(8.W), 15.U(8.W), 16.U(8.W))
      )

      val testMacroStreams2 = Vec.Lit(
        Vec.Lit(17.U(8.W), 18.U(8.W), 19.U(8.W), 20.U(8.W), 21.U(8.W), 22.U(8.W), 23.U(8.W), 24.U(8.W)),
        Vec.Lit(25.U(8.W), 26.U(8.W), 27.U(8.W), 28.U(8.W), 29.U(8.W), 30.U(8.W), 31.U(8.W), 32.U(8.W))
      )

      // test body here
      for (i <- 0 until CommonAcceleratorConfigs.minimalConfig.numberOfRempaerSwitchStages) {
        for (j <- 0 until CommonAcceleratorConfigs.minimalConfig.numberOfRemaperSwitchesPerStage) {
          dut.io.remaperSwitchesSetup(i)(j).poke(testRemaperSetup(i)(j))
        }
      }
      dut.io.initiationIntervalMinusOne.poke(1.U)
      dut.io.macroStreamBuffer.valid.poke(true.B)
      dut.io.macroStreamBuffer.bits.poke(testMacroStreams)
      dut.io.meshOut.ready.poke(true.B)
      // dut.clock.step(1)
      // dut.io.meshOut.ready.poke(false.B)
      // dut.clock.step(1)
      // dut.io.macroStreamBuffer.bits.poke(testMacroStreams2)
      // dut.clock.step(3)
      // dut.io.meshOut.ready.poke(true.B)
      dut.clock.step(10)
    }
  }

  it should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new StreamingStage(CommonAcceleratorConfigs.minimalConfig),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
}
