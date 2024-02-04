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

    val control = Flipped(new StreamingStageControlBundle(config))
  
    val staticConfiguration = Input(new StreamingStageStaticConfigurationBundle(config))

    val seConfigurationChannel = Flipped(Decoupled(new StreamingEngineConfigurationChannelBundle(config)))
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

  memory.io.ctrlReset := io.control.reset

  memory.io.readEnable := io.memReadEnable
  memory.io.readAddr := io.memReadAddr
  io.memReadData := memory.io.readData

  memory.io.writeEnable := io.memWriteEnable
  memory.io.writeAddr := io.memWriteAddr
  memory.io.writeData := io.memWriteData
  memory.io.writeStrb := io.memWriteStrb
  

  memory.io.axi :<>= streamingStage.io.memory

  io.meshDataOut := streamingStage.io.meshDataOut
  streamingStage.io.meshDataIn := io.meshDataIn

  streamingStage.io.staticConfiguration := io.staticConfiguration

  streamingStage.io.control :<>= io.control
  streamingStage.io.seConfigurationChannel :<>= io.seConfigurationChannel

}