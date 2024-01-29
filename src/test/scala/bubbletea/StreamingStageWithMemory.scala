package bubbletea

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.Decoupled
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}
import simblocks.AxiMemoryBundle

class StreamingStageWithMemory[T <: Data](config: AcceleratorConfig[T], memoryAddrWidth: Int, memoryReadDelay: Int, memoryWriteDelay: Int) extends Module {
  assert(
    config.maxSimultaneousLoadMacroStreams * config.macroStreamDepth == config.maxInitiationInterval * (2 * config.meshRows + 2 * config.meshColumns),
    "Number of macro stream elements must equal number of micro stream elements"
  )
  val io = IO(new Bundle {
    /* External read channel */
    val memReadEnable = Input(Bool())
    val memReadAddr = Input(UInt(memoryAddrWidth.W))
    val memReadData = Output(UInt((config.seLlbNumBytes * 8).W))

    /* External write channel */
    val memWriteEnable = Input(Bool())
    val memWriteAddr = Input(UInt(memoryAddrWidth.W))
    val memWriteData = Input(UInt((config.seLlbNumBytes * 8).W))
    val memWriteStrb = Input(UInt(config.seLlbNumBytes.W))

    val meshDataOut = Output(new MeshData(config))
    val meshDataIn = Input(new MeshData(config))

    val meshRun = Input(Bool())
    val meshFire = Output(Bool())

    val initiationIntervalMinusOne = Input(UInt(log2Ceil(config.maxInitiationInterval).W))

    val streamingEngineCtrl = Flipped(new StreamingEngineCtrlBundle(config))
    val streamingEngineCfg = Flipped(Decoupled(new StreamingEngineCfgBundle(config)))
    val loadRemaperSwitchesSetup =
      Input(Vec(config.numberOfLoadRemaperSwitchStages, Vec(config.numberOfLoadRemaperSwitchesPerStage, Bool())))
    val storeRemaperSwitchesSetup =
      Input(Vec(config.numberOfStoreRemaperSwitchStages, Vec(config.numberOfStoreRemaperSwitchesPerStage, Bool())))
  })

  val streamingStage = Module(new StreamingStage(config))

  val memory = Module(
    new AxiMemoryBundle(
      config.seAddressWidth,
      config.seAxiDataWidth,
      config.seLlbNumBytes * 8,
      memoryAddrWidth,
      memoryReadDelay,
      memoryWriteDelay
    )
  )

  memory.io.ctrlReset := io.streamingEngineCtrl.reset

  memory.io.readEnable := io.memReadEnable
  memory.io.readAddr := io.memReadAddr
  io.memReadData := memory.io.readData

  memory.io.writeEnable := io.memWriteEnable
  memory.io.writeAddr := io.memWriteAddr
  memory.io.writeData := io.memWriteData
  memory.io.writeStrb := io.memWriteStrb
  

  memory.io.axi :<>= streamingStage.io.memory

  streamingStage.io.meshRun := io.meshRun
  io.meshFire := streamingStage.io.meshFire

  io.meshDataOut := streamingStage.io.meshDataOut
  streamingStage.io.meshDataIn := io.meshDataIn

  streamingStage.io.initiationIntervalMinusOne := io.initiationIntervalMinusOne

  streamingStage.io.streamingEngineCtrl :<>= io.streamingEngineCtrl
  streamingStage.io.streamingEngineCfg :<>= io.streamingEngineCfg

  streamingStage.io.loadRemaperSwitchesSetup := io.loadRemaperSwitchesSetup
  streamingStage.io.storeRemaperSwitchesSetup := io.storeRemaperSwitchesSetup
}
