package bubbletea

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage

import chisel3.experimental.VecLiterals._
import chisel3.experimental.BundleLiterals._

class StreamingStageTest extends AnyFlatSpec with ChiselScalatestTester {
  "StreamingStage" should "do something" in {
    test(new StreamingStageWithMemory(CommonBubbleteaParams.minimalConfig, 10, 2, 2)).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      val memoryDataWidth = 32 * 8
      val loadRemaperSetup = PermutationNetworkUtils.generateSwitchSettingsFromDstMask(
        Seq(
          Seq(
            RemaperMask(used = true, side = Side.North, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.North, index = 1, moduloCycle = 0),
            RemaperMask(used = true, side = Side.South, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.South, index = 1, moduloCycle = 0),
            RemaperMask(used = true, side = Side.North, index = 0, moduloCycle = 1),
            RemaperMask(used = true, side = Side.North, index = 1, moduloCycle = 1),
            RemaperMask(used = true, side = Side.South, index = 0, moduloCycle = 1),
            RemaperMask(used = true, side = Side.South, index = 1, moduloCycle = 1)
          ),
          Seq(
            RemaperMask(used = true, side = Side.West, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.West, index = 1, moduloCycle = 1),
            RemaperMask(used = true, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.East, index = 1, moduloCycle = 1),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0)
          )
        ),
        rows = CommonBubbleteaParams.minimalConfig.meshRows,
        columns = CommonBubbleteaParams.minimalConfig.meshColumns
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

      for (i <- 0 until CommonBubbleteaParams.minimalConfig.maxSimultaneousLoadMacroStreams) {
        dut.io.staticConfiguration.streamingEngine.loadStreamsConfigured(i).poke(false.B)
      }
      
      dut.io.control.meshRun.poke(false.B)

      dut.io.control.reset.poke(true.B)
      dut.clock.step(1)
      dut.io.control.reset.poke(false.B)

      // configure streaming engine
      dut.io.seConfigurationChannel.valid.poke(true.B)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.start.poke(true.B)
      dut.io.seConfigurationChannel.bits.end.poke(false.B)
      dut.io.seConfigurationChannel.bits.loadStore.poke(true.B)
      dut.io.seConfigurationChannel.bits.elementWidth.poke(0.U)
      dut.io.seConfigurationChannel.bits.stream.poke(0.U)
      dut.io.seConfigurationChannel.bits.mod.poke(false.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(0.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(1.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(true.B)
      dut.io.seConfigurationChannel.bits.start.poke(false.B)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.end.poke(true.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(0.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(8.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.valid.poke(false.B)
      dut.io.staticConfiguration.streamingEngine.loadStreamsConfigured(0).poke(true.B)
      
      // wait for streaming engine to be ready for configuration again
      while (!dut.io.seConfigurationChannel.ready.peek().litToBoolean) {
        dut.clock.step(1)
      }

      // configure streaming engine (store)
      dut.io.seConfigurationChannel.valid.poke(true.B)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.start.poke(true.B)
      dut.io.seConfigurationChannel.bits.end.poke(false.B)
      dut.io.seConfigurationChannel.bits.loadStore.poke(false.B)
      dut.io.seConfigurationChannel.bits.elementWidth.poke(0.U)
      dut.io.seConfigurationChannel.bits.stream.poke(2.U)
      dut.io.seConfigurationChannel.bits.mod.poke(false.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(128.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(1.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(true.B)
      dut.io.seConfigurationChannel.bits.start.poke(false.B)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.end.poke(true.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(0.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(8.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.valid.poke(false.B)
      dut.io.staticConfiguration.streamingEngine.storeStreamsConfigured(0).poke(true.B)
      dut.io.staticConfiguration.streamingEngine.storeStreamsVecLengthMinusOne(0).poke(1.U)

      // wait for streaming engine to be ready for configuration again
      while (!dut.io.seConfigurationChannel.ready.peek().litToBoolean) {
        dut.clock.step(1)
      }
      
      // configure streaming engine (store2)
      dut.io.seConfigurationChannel.valid.poke(true.B)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.start.poke(true.B)
      dut.io.seConfigurationChannel.bits.end.poke(false.B)
      dut.io.seConfigurationChannel.bits.loadStore.poke(false.B)
      dut.io.seConfigurationChannel.bits.elementWidth.poke(0.U)
      dut.io.seConfigurationChannel.bits.stream.poke(3.U)
      dut.io.seConfigurationChannel.bits.mod.poke(false.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(192.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(1.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(true.B)
      dut.io.seConfigurationChannel.bits.start.poke(false.B)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.end.poke(true.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(0.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(8.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.valid.poke(false.B)
      dut.io.staticConfiguration.streamingEngine.storeStreamsConfigured(1).poke(true.B)
      dut.io.staticConfiguration.streamingEngine.storeStreamsVecLengthMinusOne(1).poke(1.U)

      // configure remaper
      for (i <- 0 until CommonBubbleteaParams.minimalConfig.numberOfLoadRemaperSwitchStages) {
        for (j <- 0 until CommonBubbleteaParams.minimalConfig.numberOfLoadRemaperSwitchesPerStage) {
          dut.io.staticConfiguration.loadRemaperSwitchesSetup(i)(j).poke(loadRemaperSetup(i)(j))
        }
      }

      dut.io.staticConfiguration.initiationIntervalMinusOne.poke(1.U)
      dut.clock.step(1)
      dut.io.control.meshRun.poke(true.B)

      dut.clock.step(800)
    }
  }

  it should "do something2" in {
    test(new StreamingStageWithMemory(CommonBubbleteaParams.minimalConfig, 10, 2, 2)).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      val memoryDataWidth = 32 * 8
      val loadRemaperSetup = PermutationNetworkUtils.generateSwitchSettingsFromDstMask(
        Seq(
          Seq(
            RemaperMask(used = true, side = Side.North, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.North, index = 1, moduloCycle = 0),
            RemaperMask(used = true, side = Side.South, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.South, index = 1, moduloCycle = 0),
            RemaperMask(used = true, side = Side.North, index = 0, moduloCycle = 1),
            RemaperMask(used = true, side = Side.North, index = 1, moduloCycle = 1),
            RemaperMask(used = true, side = Side.South, index = 0, moduloCycle = 1),
            RemaperMask(used = true, side = Side.South, index = 1, moduloCycle = 1)
          ),
          Seq(
            RemaperMask(used = true, side = Side.West, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.West, index = 1, moduloCycle = 1),
            RemaperMask(used = true, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.East, index = 1, moduloCycle = 1),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0)
          )
        ),
        rows = CommonBubbleteaParams.minimalConfig.meshRows,
        columns = CommonBubbleteaParams.minimalConfig.meshColumns
      )
      val storeRemaperSetup = PermutationNetworkUtils.generateSwitchSettingsFromSrcMask(
        Seq(
          Seq(
            RemaperMask(used = true, side = Side.North, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.North, index = 1, moduloCycle = 0),
            RemaperMask(used = true, side = Side.South, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.South, index = 1, moduloCycle = 0),
            RemaperMask(used = true, side = Side.North, index = 0, moduloCycle = 1),
            RemaperMask(used = true, side = Side.North, index = 1, moduloCycle = 1),
            RemaperMask(used = true, side = Side.South, index = 0, moduloCycle = 1),
            RemaperMask(used = true, side = Side.South, index = 1, moduloCycle = 1)
          ),
          Seq(
            RemaperMask(used = true, side = Side.West, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.West, index = 1, moduloCycle = 1),
            RemaperMask(used = true, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.East, index = 1, moduloCycle = 1),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0)
          )
        ),
        rows = CommonBubbleteaParams.minimalConfig.meshRows,
        columns = CommonBubbleteaParams.minimalConfig.meshColumns
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

      for (i <- 0 until CommonBubbleteaParams.minimalConfig.maxSimultaneousLoadMacroStreams) {
        dut.io.staticConfiguration.streamingEngine.loadStreamsConfigured(i).poke(false.B)
      }
      
      dut.io.control.meshRun.poke(false.B)

      dut.io.control.reset.poke(true.B)
      dut.clock.step(1)
      dut.io.control.reset.poke(false.B)

      // configure streaming engine
      dut.io.seConfigurationChannel.valid.poke(true.B)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.start.poke(true.B)
      dut.io.seConfigurationChannel.bits.end.poke(false.B)
      dut.io.seConfigurationChannel.bits.loadStore.poke(true.B)
      dut.io.seConfigurationChannel.bits.elementWidth.poke(0.U)
      dut.io.seConfigurationChannel.bits.stream.poke(0.U)
      dut.io.seConfigurationChannel.bits.mod.poke(false.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(0.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(1.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(true.B)
      dut.io.seConfigurationChannel.bits.start.poke(false.B)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.end.poke(true.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(0.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(8.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.valid.poke(false.B)
      dut.io.staticConfiguration.streamingEngine.loadStreamsConfigured(0).poke(true.B)
      
      // wait for streaming engine to be ready for configuration again
      while (!dut.io.seConfigurationChannel.ready.peek().litToBoolean) {
        dut.clock.step(1)
      }

      // configure streaming engine (store)
      dut.io.seConfigurationChannel.valid.poke(true.B)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.start.poke(true.B)
      dut.io.seConfigurationChannel.bits.end.poke(false.B)
      dut.io.seConfigurationChannel.bits.loadStore.poke(false.B)
      dut.io.seConfigurationChannel.bits.elementWidth.poke(0.U)
      dut.io.seConfigurationChannel.bits.stream.poke(2.U)
      dut.io.seConfigurationChannel.bits.mod.poke(false.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(128.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(1.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(true.B)
      dut.io.seConfigurationChannel.bits.start.poke(false.B)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.end.poke(true.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(0.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(8.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.valid.poke(false.B)
      dut.io.staticConfiguration.streamingEngine.storeStreamsConfigured(0).poke(true.B)
      dut.io.staticConfiguration.streamingEngine.storeStreamsVecLengthMinusOne(0).poke(7.U)

      // // wait for streaming engine to be ready for configuration again
      // while (!dut.io.seConfigurationChannel.ready.peek().litToBoolean) {
      //   dut.clock.step(1)
      // }
      
      // // configure streaming engine (store2)
      // dut.io.seConfigurationChannel.valid.poke(true.B)
      // dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      // dut.io.seConfigurationChannel.bits.start.poke(true.B)
      // dut.io.seConfigurationChannel.bits.end.poke(false.B)
      // dut.io.seConfigurationChannel.bits.loadStore.poke(false.B)
      // dut.io.seConfigurationChannel.bits.elementWidth.poke(0.U)
      // dut.io.seConfigurationChannel.bits.stream.poke(3.U)
      // dut.io.seConfigurationChannel.bits.mod.poke(false.B)
      // dut.io.seConfigurationChannel.bits.dimOffset.poke(192.U)
      // dut.io.seConfigurationChannel.bits.dimStride.poke(1.U)
      // dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      // dut.clock.step(1)
      // dut.io.seConfigurationChannel.bits.vectorize.poke(true.B)
      // dut.io.seConfigurationChannel.bits.start.poke(false.B)
      // dut.clock.step(1)
      // dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      // dut.io.seConfigurationChannel.bits.end.poke(true.B)
      // dut.io.seConfigurationChannel.bits.dimOffset.poke(0.U)
      // dut.io.seConfigurationChannel.bits.dimStride.poke(8.U)
      // dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      // dut.clock.step(1)
      // dut.io.seConfigurationChannel.valid.poke(false.B)
      // dut.io.staticConfiguration.streamingEngine.storeStreamsConfigured(1).poke(true.B)
      // dut.io.staticConfiguration.streamingEngine.storeStreamsVecLengthMinusOne(1).poke(7.U)

      // configure load remaper
      for (i <- 0 until CommonBubbleteaParams.minimalConfig.numberOfLoadRemaperSwitchStages) {
        for (j <- 0 until CommonBubbleteaParams.minimalConfig.numberOfLoadRemaperSwitchesPerStage) {
          dut.io.staticConfiguration.loadRemaperSwitchesSetup(i)(j).poke(loadRemaperSetup(i)(j))
        }
      }

      // configure store remaper
      for (i <- 0 until CommonBubbleteaParams.minimalConfig.numberOfStoreRemaperSwitchStages) {
        for (j <- 0 until CommonBubbleteaParams.minimalConfig.numberOfStoreRemaperSwitchesPerStage) {
          dut.io.staticConfiguration.storeRemaperSwitchesSetup(i)(j).poke(storeRemaperSetup(i)(j))
        }
      }

      dut.io.staticConfiguration.initiationIntervalMinusOne.poke(1.U)
      dut.clock.step(1)
      dut.io.control.meshRun.poke(true.B)
      for (i <- 0 until 300) {
        dut.io.meshDataIn.poke(dut.io.meshDataOut.peek())
        dut.clock.step(1)
      }

      //dut.clock.step(300)
    }
  }

  it should "do something2, with vecLen 2" in {
    test(new StreamingStageWithMemory(CommonBubbleteaParams.minimalConfig, 10, 2, 2)).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      val memoryDataWidth = 32 * 8
      val loadRemaperSetup = PermutationNetworkUtils.generateSwitchSettingsFromDstMask(
        Seq(
          Seq(
            RemaperMask(used = true, side = Side.North, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.North, index = 1, moduloCycle = 0),
            RemaperMask(used = true, side = Side.South, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.South, index = 1, moduloCycle = 0),
            RemaperMask(used = true, side = Side.North, index = 0, moduloCycle = 1),
            RemaperMask(used = true, side = Side.North, index = 1, moduloCycle = 1),
            RemaperMask(used = true, side = Side.South, index = 0, moduloCycle = 1),
            RemaperMask(used = true, side = Side.South, index = 1, moduloCycle = 1)
          ),
          Seq(
            RemaperMask(used = true, side = Side.West, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.West, index = 1, moduloCycle = 1),
            RemaperMask(used = true, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.East, index = 1, moduloCycle = 1),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0)
          )
        ),
        rows = CommonBubbleteaParams.minimalConfig.meshRows,
        columns = CommonBubbleteaParams.minimalConfig.meshColumns
      )
      val storeRemaperSetup = PermutationNetworkUtils.generateSwitchSettingsFromSrcMask(
        Seq(
          Seq(
            RemaperMask(used = true, side = Side.North, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.North, index = 1, moduloCycle = 0),
            RemaperMask(used = true, side = Side.South, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.South, index = 1, moduloCycle = 0),
            RemaperMask(used = true, side = Side.North, index = 0, moduloCycle = 1),
            RemaperMask(used = true, side = Side.North, index = 1, moduloCycle = 1),
            RemaperMask(used = true, side = Side.South, index = 0, moduloCycle = 1),
            RemaperMask(used = true, side = Side.South, index = 1, moduloCycle = 1)
          ),
          Seq(
            RemaperMask(used = true, side = Side.West, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.West, index = 1, moduloCycle = 1),
            RemaperMask(used = true, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = true, side = Side.East, index = 1, moduloCycle = 1),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0),
            RemaperMask(used = false, side = Side.East, index = 0, moduloCycle = 0)
          )
        ),
        rows = CommonBubbleteaParams.minimalConfig.meshRows,
        columns = CommonBubbleteaParams.minimalConfig.meshColumns
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

      for (i <- 0 until CommonBubbleteaParams.minimalConfig.maxSimultaneousLoadMacroStreams) {
        dut.io.staticConfiguration.streamingEngine.loadStreamsConfigured(i).poke(false.B)
      }
      
      dut.io.control.meshRun.poke(false.B)

      dut.io.control.reset.poke(true.B)
      dut.clock.step(1)
      dut.io.control.reset.poke(false.B)

      // configure streaming engine
      dut.io.seConfigurationChannel.valid.poke(true.B)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.start.poke(true.B)
      dut.io.seConfigurationChannel.bits.end.poke(false.B)
      dut.io.seConfigurationChannel.bits.loadStore.poke(true.B)
      dut.io.seConfigurationChannel.bits.elementWidth.poke(0.U)
      dut.io.seConfigurationChannel.bits.stream.poke(0.U)
      dut.io.seConfigurationChannel.bits.mod.poke(false.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(0.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(1.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(2.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(true.B)
      dut.io.seConfigurationChannel.bits.start.poke(false.B)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.end.poke(true.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(0.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(2.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(32.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.valid.poke(false.B)
      dut.io.staticConfiguration.streamingEngine.loadStreamsConfigured(0).poke(true.B)
      
      // wait for streaming engine to be ready for configuration again
      while (!dut.io.seConfigurationChannel.ready.peek().litToBoolean) {
        dut.clock.step(1)
      }

      // configure streaming engine (store)
      dut.io.seConfigurationChannel.valid.poke(true.B)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.start.poke(true.B)
      dut.io.seConfigurationChannel.bits.end.poke(false.B)
      dut.io.seConfigurationChannel.bits.loadStore.poke(false.B)
      dut.io.seConfigurationChannel.bits.elementWidth.poke(0.U)
      dut.io.seConfigurationChannel.bits.stream.poke(2.U)
      dut.io.seConfigurationChannel.bits.mod.poke(false.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(128.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(1.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(2.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(true.B)
      dut.io.seConfigurationChannel.bits.start.poke(false.B)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      dut.io.seConfigurationChannel.bits.end.poke(true.B)
      dut.io.seConfigurationChannel.bits.dimOffset.poke(0.U)
      dut.io.seConfigurationChannel.bits.dimStride.poke(2.U)
      dut.io.seConfigurationChannel.bits.dimSize.poke(32.U)
      dut.clock.step(1)
      dut.io.seConfigurationChannel.valid.poke(false.B)
      dut.io.staticConfiguration.streamingEngine.storeStreamsConfigured(0).poke(true.B)
      dut.io.staticConfiguration.streamingEngine.storeStreamsVecLengthMinusOne(0).poke(1.U)

      // // wait for streaming engine to be ready for configuration again
      // while (!dut.io.seConfigurationChannel.ready.peek().litToBoolean) {
      //   dut.clock.step(1)
      // }
      
      // // configure streaming engine (store2)
      // dut.io.seConfigurationChannel.valid.poke(true.B)
      // dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      // dut.io.seConfigurationChannel.bits.start.poke(true.B)
      // dut.io.seConfigurationChannel.bits.end.poke(false.B)
      // dut.io.seConfigurationChannel.bits.loadStore.poke(false.B)
      // dut.io.seConfigurationChannel.bits.elementWidth.poke(0.U)
      // dut.io.seConfigurationChannel.bits.stream.poke(3.U)
      // dut.io.seConfigurationChannel.bits.mod.poke(false.B)
      // dut.io.seConfigurationChannel.bits.dimOffset.poke(192.U)
      // dut.io.seConfigurationChannel.bits.dimStride.poke(1.U)
      // dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      // dut.clock.step(1)
      // dut.io.seConfigurationChannel.bits.vectorize.poke(true.B)
      // dut.io.seConfigurationChannel.bits.start.poke(false.B)
      // dut.clock.step(1)
      // dut.io.seConfigurationChannel.bits.vectorize.poke(false.B)
      // dut.io.seConfigurationChannel.bits.end.poke(true.B)
      // dut.io.seConfigurationChannel.bits.dimOffset.poke(0.U)
      // dut.io.seConfigurationChannel.bits.dimStride.poke(8.U)
      // dut.io.seConfigurationChannel.bits.dimSize.poke(8.U)
      // dut.clock.step(1)
      // dut.io.seConfigurationChannel.valid.poke(false.B)
      // dut.io.staticConfiguration.streamingEngine.storeStreamsConfigured(1).poke(true.B)
      // dut.io.staticConfiguration.streamingEngine.storeStreamsVecLengthMinusOne(1).poke(7.U)

      // configure load remaper
      for (i <- 0 until CommonBubbleteaParams.minimalConfig.numberOfLoadRemaperSwitchStages) {
        for (j <- 0 until CommonBubbleteaParams.minimalConfig.numberOfLoadRemaperSwitchesPerStage) {
          dut.io.staticConfiguration.loadRemaperSwitchesSetup(i)(j).poke(loadRemaperSetup(i)(j))
        }
      }

      // configure store remaper
      for (i <- 0 until CommonBubbleteaParams.minimalConfig.numberOfStoreRemaperSwitchStages) {
        for (j <- 0 until CommonBubbleteaParams.minimalConfig.numberOfStoreRemaperSwitchesPerStage) {
          dut.io.staticConfiguration.storeRemaperSwitchesSetup(i)(j).poke(storeRemaperSetup(i)(j))
        }
      }

      dut.io.staticConfiguration.initiationIntervalMinusOne.poke(1.U)
      dut.clock.step(1)
      dut.io.control.meshRun.poke(true.B)
      for (i <- 0 until 300) {
        dut.io.meshDataIn.poke(dut.io.meshDataOut.peek())
        dut.clock.step(1)
      }

      //dut.clock.step(300)
    }
  }

  it should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new StreamingStage(CommonBubbleteaParams.minimalConfig, SocParams(32, 32, 32, 64)),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
}
