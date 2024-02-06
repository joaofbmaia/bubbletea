package bubbletea

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.Decoupled
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}
import simblocks.AxiMemoryBundle

class StreamingStageWithMemory[T <: Data](params: BubbleteaParams[T], memoryAddrWidth: Int, memoryReadDelay: Int, memoryWriteDelay: Int) extends Module {
  assert(
    params.maxSimultaneousLoadMacroStreams * params.macroStreamDepth == params.maxInitiationInterval * (2 * params.meshRows + 2 * params.meshColumns),
    "Number of macro stream elements must equal number of micro stream elements"
  )
  val io = IO(new Bundle {
    /* External read channel */
    val memReadEnable = Input(Bool())
    val memReadAddr = Input(UInt(memoryAddrWidth.W))
    val memReadData = Output(UInt((params.seLlbNumBytes * 8).W))

    /* External write channel */
    val memWriteEnable = Input(Bool())
    val memWriteAddr = Input(UInt(memoryAddrWidth.W))
    val memWriteData = Input(UInt((params.seLlbNumBytes * 8).W))
    val memWriteStrb = Input(UInt(params.seLlbNumBytes.W))

    val meshDataOut = Output(new MeshData(params))
    val meshDataIn = Input(new MeshData(params))

    val control = Flipped(new StreamingStageControlBundle(params))
  
    val staticConfiguration = Input(new StreamingStageStaticConfigurationBundle(params))

    val seConfigurationChannel = Flipped(Decoupled(new StreamingEngineConfigurationChannelBundle(params)))
  })

  val streamingStage = Module(new StreamingStage(params))

  val memory = Module(
    new AxiMemoryBundle(
      params.seAddressWidth,
      params.seAxiDataWidth,
      params.seLlbNumBytes * 8,
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