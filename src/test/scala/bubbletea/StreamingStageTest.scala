package bubbletea

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage

import chisel3.experimental.VecLiterals._
import chisel3.experimental.BundleLiterals._

class StreamingStageTest extends AnyFlatSpec with ChiselScalatestTester {
  "StreamingStage" should "do something" in {
    test(new StreamingStageWithMemory(CommonAcceleratorConfigs.minimalConfig, 10, 2, 2)).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      val memoryDataWidth = CommonAcceleratorConfigs.minimalConfig.seLlbNumBytes * 8
      val testRemaperSetup = PermutationNetworkUtils.generateSwitchSettingsFromDstMask(
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
      // fill first 256 bytes of memory with linear pattern
      dut.io.memWriteEnable.poke(true.B)
      dut.io.memWriteStrb.poke(0xffL.U)
      for (i <- 0 until 256 by memoryDataWidth / 8) {
        // value with every byte equal to the adress
        val x = BigInt((i until i + memoryDataWidth / 8).map(x => f"$x%02x").reverse.reduce(_ ++ _), 16)
        println((i / (memoryDataWidth / 8)).U)
        dut.io.memWriteAddr.poke((i / (memoryDataWidth / 8)).U)
        println(x.toString(16))
        dut.io.memWriteData.poke(x)
        dut.clock.step(1)
      }
      dut.io.memWriteEnable.poke(false.B)
      dut.clock.step(1)

      for (i <- 0 until CommonAcceleratorConfigs.minimalConfig.maxSimultaneousLoadMacroStreams) {
        dut.io.streamingEngineCtrl.loadStreamsConfigured(i).poke(false.B)
      }
      
      dut.io.meshOut.ready.poke(false.B)

      dut.io.streamingEngineCtrl.reset.poke(true.B)
      dut.clock.step(1)
      dut.io.streamingEngineCtrl.reset.poke(false.B)

      // configure streaming engine
      dut.io.streamingEngineCfg.valid.poke(true.B)
      dut.io.streamingEngineCfg.bits.vectorize.poke(false.B)
      dut.io.streamingEngineCfg.bits.start.poke(true.B)
      dut.io.streamingEngineCfg.bits.end.poke(false.B)
      dut.io.streamingEngineCfg.bits.loadStore.poke(true.B)
      dut.io.streamingEngineCfg.bits.elementWidth.poke(0.U)
      dut.io.streamingEngineCfg.bits.stream.poke(0.U)
      dut.io.streamingEngineCfg.bits.mod.poke(false.B)
      dut.io.streamingEngineCfg.bits.dimOffset.poke(0.U)
      dut.io.streamingEngineCfg.bits.dimStride.poke(1.U)
      dut.io.streamingEngineCfg.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.streamingEngineCfg.bits.vectorize.poke(true.B)
      dut.io.streamingEngineCfg.bits.start.poke(false.B)
      dut.clock.step(1)
      dut.io.streamingEngineCfg.bits.vectorize.poke(false.B)
      dut.io.streamingEngineCfg.bits.end.poke(true.B)
      dut.io.streamingEngineCfg.bits.dimOffset.poke(0.U)
      dut.io.streamingEngineCfg.bits.dimStride.poke(8.U)
      dut.io.streamingEngineCfg.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.streamingEngineCfg.valid.poke(false.B)
      dut.io.streamingEngineCtrl.loadStreamsConfigured(0).poke(true.B)
      
      // wait for streaming engine to be ready for configuration again
      while (!dut.io.streamingEngineCfg.ready.peek().litToBoolean) {
        dut.clock.step(1)
      }

      // configure streaming engine (store)
      dut.io.streamingEngineCfg.valid.poke(true.B)
      dut.io.streamingEngineCfg.bits.vectorize.poke(false.B)
      dut.io.streamingEngineCfg.bits.start.poke(true.B)
      dut.io.streamingEngineCfg.bits.end.poke(false.B)
      dut.io.streamingEngineCfg.bits.loadStore.poke(false.B)
      dut.io.streamingEngineCfg.bits.elementWidth.poke(0.U)
      dut.io.streamingEngineCfg.bits.stream.poke(2.U)
      dut.io.streamingEngineCfg.bits.mod.poke(false.B)
      dut.io.streamingEngineCfg.bits.dimOffset.poke(128.U)
      dut.io.streamingEngineCfg.bits.dimStride.poke(1.U)
      dut.io.streamingEngineCfg.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.streamingEngineCfg.bits.vectorize.poke(true.B)
      dut.io.streamingEngineCfg.bits.start.poke(false.B)
      dut.clock.step(1)
      dut.io.streamingEngineCfg.bits.vectorize.poke(false.B)
      dut.io.streamingEngineCfg.bits.end.poke(true.B)
      dut.io.streamingEngineCfg.bits.dimOffset.poke(0.U)
      dut.io.streamingEngineCfg.bits.dimStride.poke(8.U)
      dut.io.streamingEngineCfg.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.streamingEngineCfg.valid.poke(false.B)
      dut.io.streamingEngineCtrl.storeStreamsConfigured(0).poke(true.B)
      dut.io.streamingEngineCtrl.storeStreamsVecLengthMinusOne(0).poke(1.U)

      // wait for streaming engine to be ready for configuration again
      while (!dut.io.streamingEngineCfg.ready.peek().litToBoolean) {
        dut.clock.step(1)
      }
      
      // configure streaming engine (store2)
      dut.io.streamingEngineCfg.valid.poke(true.B)
      dut.io.streamingEngineCfg.bits.vectorize.poke(false.B)
      dut.io.streamingEngineCfg.bits.start.poke(true.B)
      dut.io.streamingEngineCfg.bits.end.poke(false.B)
      dut.io.streamingEngineCfg.bits.loadStore.poke(false.B)
      dut.io.streamingEngineCfg.bits.elementWidth.poke(0.U)
      dut.io.streamingEngineCfg.bits.stream.poke(3.U)
      dut.io.streamingEngineCfg.bits.mod.poke(false.B)
      dut.io.streamingEngineCfg.bits.dimOffset.poke(192.U)
      dut.io.streamingEngineCfg.bits.dimStride.poke(1.U)
      dut.io.streamingEngineCfg.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.streamingEngineCfg.bits.vectorize.poke(true.B)
      dut.io.streamingEngineCfg.bits.start.poke(false.B)
      dut.clock.step(1)
      dut.io.streamingEngineCfg.bits.vectorize.poke(false.B)
      dut.io.streamingEngineCfg.bits.end.poke(true.B)
      dut.io.streamingEngineCfg.bits.dimOffset.poke(0.U)
      dut.io.streamingEngineCfg.bits.dimStride.poke(8.U)
      dut.io.streamingEngineCfg.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.streamingEngineCfg.valid.poke(false.B)
      dut.io.streamingEngineCtrl.storeStreamsConfigured(1).poke(true.B)
      dut.io.streamingEngineCtrl.storeStreamsVecLengthMinusOne(1).poke(1.U)

      // configure remaper
      for (i <- 0 until CommonAcceleratorConfigs.minimalConfig.numberOfLoadRemaperSwitchStages) {
        for (j <- 0 until CommonAcceleratorConfigs.minimalConfig.numberOfLoadRemaperSwitchesPerStage) {
          dut.io.remaperSwitchesSetup(i)(j).poke(testRemaperSetup(i)(j))
        }
      }

      dut.io.initiationIntervalMinusOne.poke(1.U)
      dut.clock.step(1)
      dut.io.meshOut.ready.poke(true.B)
      dut.io.storeStreams.valid.poke(true.B)
      dut.clock.step(20)
      dut.io.meshOut.ready.poke(false.B)
      dut.clock.step(20)
      dut.io.meshOut.ready.poke(true.B)
      dut.clock.step(10)
      dut.io.meshOut.ready.poke(false.B)
      dut.clock.step(10)
      dut.io.meshOut.ready.poke(true.B)

      dut.clock.step(300)
    }
  }

  it should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new StreamingStage(CommonAcceleratorConfigs.minimalConfig),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
}
