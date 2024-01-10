package bubbletea

import chisel3._

class dstMaskBundle extends Bundle {
  val port        = Bool()
  val moduloCycle    = UInt(8.W)
}

class StreamingStage[T <: Data](config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {
    val inRegs = Input(Vec(config.maxMacroStreams, Vec(config.maxInitiationInterval, config.dataType)))
    val inRegsValid = Input(Bool())
    val outPortN = Output(Vec(config.meshColumns, config.dataType))
    val outPortS = Output(Vec(config.meshColumns, config.dataType))
    val outPortW = Output(Vec(config.meshRows, config.dataType))
    val outPortE = Output(Vec(config.meshRows, config.dataType))
  })
  
  val dstMask = Reg(Vec(config.maxInitiationInterval, Vec(config.maxMacroStreams, Bool())))

}
