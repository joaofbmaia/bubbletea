package bitstreamassembler

import bubbletea._
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._
import java.io._
import upickle.default._
import scala.io.Source
import scala.annotation.meta.param

class BitstreamAssember[T <: Data](configurationFile: String, params: BubbleteaParams[T], socParams: SocParams) extends AnyFlatSpec with ChiselScalatestTester {
  "BitstreamAssembler" should  "generate binary file with bitstream" in {
    test(new Module { 
      val io = IO(new Bundle {
        val in = Input(new BitstreamBundle(params, socParams))
        val out = Output(UInt(in.getWidth.W))
        val sizeBytes = Output(UInt(32.W))
        println(s"BitstreamBundle width: ${in.getWidth}")
        println(s"BitstreamBundle width in Bytes: ${in.getWidth / 8}")
        val usefulBits = (new ConfigurationBundle(params)).getWidth
        println(s"Useful bits: $usefulBits")
        println(s"Paddings: ${in.getWidth - usefulBits}")
      })
      io.sizeBytes := io.in.getWidth.U / 8.U
      io.out := io.in.asUInt
    }) {dut => 

      val jsonConfiguration = Source.fromFile(configurationFile).getLines().mkString
      val configurationData = read[ConfigurationData](jsonConfiguration)

      // Configuration Mapping
      {
        dut.io.in.padding0.poke(0.U)
        dut.io.in.padding1.poke(0.U)

        /* Streaming Stage */

        assert(configurationData.static.streamingStage.streamingEngine.loadStreamsConfigured.length == params.maxSimultaneousLoadMacroStreams)
        for (i <- 0 until params.maxSimultaneousLoadMacroStreams) {
          dut.io.in.static.streamingStage.streamingEngine.loadStreamsConfigured(i)
          .poke(configurationData.static.streamingStage.streamingEngine.loadStreamsConfigured(i).B)
        }

        assert(configurationData.static.streamingStage.streamingEngine.storeStreamsConfigured.length == params.maxSimultaneousStoreMacroStreams)
        for (i <- 0 until params.maxSimultaneousStoreMacroStreams) {
          dut.io.in.static.streamingStage.streamingEngine.storeStreamsConfigured(i)
          .poke(configurationData.static.streamingStage.streamingEngine.storeStreamsConfigured(i).B)
        }

        assert(configurationData.static.streamingStage.streamingEngine.storeStreamsVecLengthMinusOne.length == params.maxSimultaneousStoreMacroStreams)
        for (i <- 0 until params.maxSimultaneousStoreMacroStreams) {
          dut.io.in.static.streamingStage.streamingEngine.storeStreamsVecLengthMinusOne(i)
          .poke(configurationData.static.streamingStage.streamingEngine.storeStreamsVecLengthMinusOne(i).U)
        }

        dut.io.in.static.streamingStage.initiationIntervalMinusOne.poke(configurationData.static.streamingStage.initiationIntervalMinusOne.U)

        dut.io.in.static.streamingStage.storeStreamsFixedDelay.poke(configurationData.static.streamingStage.storeStreamsFixedDelay.U)

        /* Remaper */
        val loadRemaperSwitchesSetup = PermutationNetworkUtils.generateSwitchSettingsFromDstMask(
          configurationData.static.streamingStage.loadRemaperSwitchesSetup,
          params.meshRows,
          params.meshColumns
        )
        assert(loadRemaperSwitchesSetup.length == params.numberOfLoadRemaperSwitchStages)
        assert(loadRemaperSwitchesSetup(0).length == params.numberOfLoadRemaperSwitchesPerStage)
        for (i <- 0 until params.numberOfLoadRemaperSwitchStages) {
          for (j <- 0 until params.numberOfLoadRemaperSwitchesPerStage) {
            dut.io.in.static.streamingStage.loadRemaperSwitchesSetup(i)(j).poke(loadRemaperSwitchesSetup(i)(j).B)
          }
        }

        val storeRemaperSwitchesSetup = PermutationNetworkUtils.generateSwitchSettingsFromSrcMask(
          configurationData.static.streamingStage.storeRemaperSwitchesSetup,
          params.meshRows,
          params.meshColumns
        )
        assert(storeRemaperSwitchesSetup.length == params.numberOfStoreRemaperSwitchStages)
        assert(storeRemaperSwitchesSetup(0).length == params.numberOfStoreRemaperSwitchesPerStage)
        for (i <- 0 until params.numberOfStoreRemaperSwitchStages) {
          for (j <- 0 until params.numberOfStoreRemaperSwitchesPerStage) {
            dut.io.in.static.streamingStage.storeRemaperSwitchesSetup(i)(j).poke(storeRemaperSwitchesSetup(i)(j).B)
          }
        }

        /* Mesh */

        assert(configurationData.static.mesh.length == params.maxInitiationInterval)
        assert(configurationData.static.mesh(0).length == params.meshRows)
        assert(configurationData.static.mesh(0)(0).length == params.meshColumns)
        for (i <- 0 until params.maxInitiationInterval) {
          for (j <- 0 until params.meshRows) {
            for (k <- 0 until params.meshColumns) {
              dut.io.in.static.mesh(i)(j)(k).op.poke(configurationData.static.mesh(i)(j)(k).op.toFuSel)
              dut.io.in.static.mesh(i)(j)(k).outRegsSel.north.poke(configurationData.static.mesh(i)(j)(k).outRegsSel.north.U)
              dut.io.in.static.mesh(i)(j)(k).outRegsSel.south.poke(configurationData.static.mesh(i)(j)(k).outRegsSel.south.U)
              dut.io.in.static.mesh(i)(j)(k).outRegsSel.west.poke(configurationData.static.mesh(i)(j)(k).outRegsSel.west.U)
              dut.io.in.static.mesh(i)(j)(k).outRegsSel.east.poke(configurationData.static.mesh(i)(j)(k).outRegsSel.east.U)

              assert(configurationData.static.mesh(i)(j)(k).rfWritePortsSel.ports.length == params.rfWritePorts)
              for (l <- 0 until params.rfWritePorts) {
                dut.io.in.static.mesh(i)(j)(k).rfWritePortsSel.ports(l).poke(configurationData.static.mesh(i)(j)(k).rfWritePortsSel.ports(l).U)
              }

              dut.io.in.static.mesh(i)(j)(k).fuSrcSel.a.poke(configurationData.static.mesh(i)(j)(k).fuSrcSel.a.U)
              dut.io.in.static.mesh(i)(j)(k).fuSrcSel.b.poke(configurationData.static.mesh(i)(j)(k).fuSrcSel.b.U)

              assert(configurationData.static.mesh(i)(j)(k).rfWriteAddr.length == params.rfWritePorts)
              for (l <- 0 until params.rfWritePorts) {
                dut.io.in.static.mesh(i)(j)(k).rfWriteAddr(l).poke(configurationData.static.mesh(i)(j)(k).rfWriteAddr(l).U)
              }

              assert(configurationData.static.mesh(i)(j)(k).rfReadAddr.length == params.rfReadPorts)
              for (l <- 0 until params.rfReadPorts) {
                dut.io.in.static.mesh(i)(j)(k).rfReadAddr(l).poke(configurationData.static.mesh(i)(j)(k).rfReadAddr(l).U)
              }

              assert(configurationData.static.mesh(i)(j)(k).rfWriteEn.length == params.rfWritePorts)  
              for (l <- 0 until params.rfWritePorts) {
                dut.io.in.static.mesh(i)(j)(k).rfWriteEn(l).poke(configurationData.static.mesh(i)(j)(k).rfWriteEn(l).B)
              }
            }
          }
        }

        /* Delayer */

        assert(configurationData.static.delayer.loads.north.length == params.meshColumns)
        for (i <- 0 until params.meshColumns) {
          dut.io.in.static.delayer.loads.north(i).poke(configurationData.static.delayer.loads.north(i).U)
        }

        assert(configurationData.static.delayer.loads.south.length == params.meshColumns)
        for (i <- 0 until params.meshColumns) {
          dut.io.in.static.delayer.loads.south(i).poke(configurationData.static.delayer.loads.south(i).U)
        }

        assert(configurationData.static.delayer.loads.west.length == params.meshRows)
        for (i <- 0 until params.meshRows) {
          dut.io.in.static.delayer.loads.west(i).poke(configurationData.static.delayer.loads.west(i).U)
        }

        assert(configurationData.static.delayer.loads.east.length == params.meshRows)
        for (i <- 0 until params.meshRows) {
          dut.io.in.static.delayer.loads.east(i).poke(configurationData.static.delayer.loads.east(i).U)
        }

        assert(configurationData.static.delayer.stores.north.length == params.meshColumns)
        for (i <- 0 until params.meshColumns) {
          dut.io.in.static.delayer.stores.north(i).poke(configurationData.static.delayer.stores.north(i).U)
        }

        assert(configurationData.static.delayer.stores.south.length == params.meshColumns)
        for (i <- 0 until params.meshColumns) {
          dut.io.in.static.delayer.stores.south(i).poke(configurationData.static.delayer.stores.south(i).U)
        }

        assert(configurationData.static.delayer.stores.west.length == params.meshRows)
        for (i <- 0 until params.meshRows) {
          dut.io.in.static.delayer.stores.west(i).poke(configurationData.static.delayer.stores.west(i).U)
        }

        assert(configurationData.static.delayer.stores.east.length == params.meshRows)
        for (i <- 0 until params.meshRows) {
          dut.io.in.static.delayer.stores.east(i).poke(configurationData.static.delayer.stores.east(i).U)
        }

        /* Streaming Engine Instructions */

        assert(configurationData.streamingEngineInstructions.length == params.maxConfigurationInstructions)
        for (i <- 0 until params.maxConfigurationInstructions) {
          dut.io.in.streamingEngineInstructions(i).padding
          .poke(0.U)

          dut.io.in.streamingEngineInstructions(i).compressedInstruction.isValid
          .poke(configurationData.streamingEngineInstructions(i).isValid.B)

          dut.io.in.streamingEngineInstructions(i).compressedInstruction.stream
          .poke(configurationData.streamingEngineInstructions(i).stream.U)

          dut.io.in.streamingEngineInstructions(i).compressedInstruction.elementWidth
          .poke(configurationData.streamingEngineInstructions(i).elementWidth.U)
          
          dut.io.in.streamingEngineInstructions(i).compressedInstruction.loadStoreOrMod
          .poke(configurationData.streamingEngineInstructions(i).loadStoreOrMod.B)

          dut.io.in.streamingEngineInstructions(i).compressedInstruction.dimOffsetOrModSize
          .poke(configurationData.streamingEngineInstructions(i).dimOffsetOrModSize.U)

          dut.io.in.streamingEngineInstructions(i).compressedInstruction.dimSizeOtModTargetAndModBehaviour
          .poke(configurationData.streamingEngineInstructions(i).dimSizeOtModTargetAndModBehaviour.U)

          dut.io.in.streamingEngineInstructions(i).compressedInstruction.end
          .poke(configurationData.streamingEngineInstructions(i).end.B)

          dut.io.in.streamingEngineInstructions(i).compressedInstruction.start
          .poke(configurationData.streamingEngineInstructions(i).start.B)

          dut.io.in.streamingEngineInstructions(i).compressedInstruction.dimStrideOrModDisplacement
          .poke(configurationData.streamingEngineInstructions(i).dimStrideOrModDisplacement.U)

          dut.io.in.streamingEngineInstructions(i).compressedInstruction.vectorize
          .poke(configurationData.streamingEngineInstructions(i).vectorize.B)
        }
      }


      val size = dut.io.sizeBytes.peek().litValue.toInt
      val bitstream = dut.io.out.peek().litValue.toByteArray.reverse.padTo(size, 0.toByte)
      println("Bitstream:")
      bitstream.map(x => f"0x$x%02X ").grouped(16).foreach(x => println(x.mkString))

      val out = new FileOutputStream("test_bitstream.bin")
      out.write(bitstream)
      out.close()
    }
  }
}

object BitstreamAssembler extends App {
  val configurationFile = "test.json"
  val params = CommonBubbleteaParams.minimalConfig
  val socParams = SocParams(
    cacheLineBytes = 64,
    frontBusAddressBits = 32,
    frontBusDataBits = 64,
    xLen = 64
  )
  org.scalatest.run(new BitstreamAssember(configurationFile, params, socParams))
}

object JsonTest extends App {
  val configuration = ConfigurationData(
    static = AcceleratorStaticConfigurationData(
      streamingStage = StreamingStageStaticConfigurationData(
        streamingEngine = StreamingEngineStaticConfigurationData(
          loadStreamsConfigured = Seq(true, false),
          storeStreamsConfigured = Seq(true, false),
          storeStreamsVecLengthMinusOne = Seq(3, 0)
        ),
        initiationIntervalMinusOne = 0,
        storeStreamsFixedDelay = 2,
        loadRemaperSwitchesSetup = Seq(
          Seq.fill(8)(RemaperMask(false, 0, Side.North, 0)),
          Seq.fill(8)(RemaperMask(false, 0, Side.North, 0))
        ),
        storeRemaperSwitchesSetup = Seq(
          Seq.fill(8)(RemaperMask(false, 0, Side.North, 0)),
          Seq.fill(8)(RemaperMask(false, 0, Side.North, 0))
        )
      ),
      mesh = Seq(
        Seq(
          Seq(
            ProcessingElementConfigData(
              op = FuAdd,
              outRegsSel = OutRegsSrcSelData(0, 0, 0, 0),
              rfWritePortsSel = RfWritePortsSrcSelData(Seq(0, 0)),
              fuSrcSel = FuSrcSelData(0, 0),
              rfWriteAddr = Seq(0, 0),
              rfReadAddr = Seq(0, 0),
              rfWriteEn = Seq(true, false)
            ),
            ProcessingElementConfigData(
              op = FuAdd,
              outRegsSel = OutRegsSrcSelData(0, 0, 0, 0),
              rfWritePortsSel = RfWritePortsSrcSelData(Seq(0, 0)),
              fuSrcSel = FuSrcSelData(0, 0),
              rfWriteAddr = Seq(0, 0),
              rfReadAddr = Seq(0, 0),
              rfWriteEn = Seq(true, false)
            )
          ),
          Seq(
            ProcessingElementConfigData(
              op = FuAdd,
              outRegsSel = OutRegsSrcSelData(0, 0, 0, 0),
              rfWritePortsSel = RfWritePortsSrcSelData(Seq(0, 0)),
              fuSrcSel = FuSrcSelData(0, 0),
              rfWriteAddr = Seq(0, 0),
              rfReadAddr = Seq(0, 0),
              rfWriteEn = Seq(true, false)
            ),
            ProcessingElementConfigData(
              op = FuAdd,
              outRegsSel = OutRegsSrcSelData(0, 0, 0, 0),
              rfWritePortsSel = RfWritePortsSrcSelData(Seq(0, 0)),
              fuSrcSel = FuSrcSelData(0, 0),
              rfWriteAddr = Seq(0, 0),
              rfReadAddr = Seq(0, 0),
              rfWriteEn = Seq(true, false)
            )
          )
        ),
        Seq(
          Seq(
            ProcessingElementConfigData(
              op = FuAdd,
              outRegsSel = OutRegsSrcSelData(0, 0, 0, 0),
              rfWritePortsSel = RfWritePortsSrcSelData(Seq(0, 0)),
              fuSrcSel = FuSrcSelData(0, 0),
              rfWriteAddr = Seq(0, 0),
              rfReadAddr = Seq(0, 0),
              rfWriteEn = Seq(true, false)
            ),
            ProcessingElementConfigData(
              op = FuAdd,
              outRegsSel = OutRegsSrcSelData(0, 0, 0, 0),
              rfWritePortsSel = RfWritePortsSrcSelData(Seq(0, 0)),
              fuSrcSel = FuSrcSelData(0, 0),
              rfWriteAddr = Seq(0, 0),
              rfReadAddr = Seq(0, 0),
              rfWriteEn = Seq(true, false)
            )
          ),
          Seq(
            ProcessingElementConfigData(
              op = FuAdd,
              outRegsSel = OutRegsSrcSelData(0, 0, 0, 0),
              rfWritePortsSel = RfWritePortsSrcSelData(Seq(0, 0)),
              fuSrcSel = FuSrcSelData(0, 0),
              rfWriteAddr = Seq(0, 0),
              rfReadAddr = Seq(0, 0),
              rfWriteEn = Seq(true, false)
            ),
            ProcessingElementConfigData(
              op = FuAdd,
              outRegsSel = OutRegsSrcSelData(0, 0, 0, 0),
              rfWritePortsSel = RfWritePortsSrcSelData(Seq(0, 0)),
              fuSrcSel = FuSrcSelData(0, 0),
              rfWriteAddr = Seq(0, 0),
              rfReadAddr = Seq(0, 0),
              rfWriteEn = Seq(true, false)
            )
          )
        )
      ),
      delayer = DelayerConfigData(
        loads = DelayerBundleData(
          north = Seq(0, 0),
          south = Seq(0, 0),
          west = Seq(0, 0),
          east = Seq(0, 0)
        ),
        stores = DelayerBundleData(
          north = Seq(0, 0),
          south = Seq(0, 0),
          west = Seq(0, 0),
          east = Seq(0, 0)
        )
      )
    ),
    streamingEngineInstructions = Seq(
      StreamingEngineCompressedConfigurationChannelData(
        isValid = true,
        stream = 1,
        elementWidth = 2,
        loadStoreOrMod = true,
        dimOffsetOrModSize = 0,
        dimSizeOtModTargetAndModBehaviour = 16,
        end = false,
        start = true,
        dimStrideOrModDisplacement = 4,
        vectorize = true
      )
    ) ++ Seq.fill(15)(StreamingEngineCompressedConfigurationChannelData(
      isValid = false,
      stream = 0,
      elementWidth = 0,
      loadStoreOrMod = false,
      dimOffsetOrModSize = 0,
      dimSizeOtModTargetAndModBehaviour = 0,
      end = false,
      start = false,
      dimStrideOrModDisplacement = 0,
      vectorize = false
    ))
  )

  val json = write(configuration, indent = 2)
  println(json)

  val fileWriter = new FileWriter("test.json")
  fileWriter.write(json)
  fileWriter.close()

  //val deserialized = read[ConfigurationData](json)
  val deserialized = read[ConfigurationData](Source.fromFile("test.json").getLines().mkString)
  assert(configuration == deserialized)
}