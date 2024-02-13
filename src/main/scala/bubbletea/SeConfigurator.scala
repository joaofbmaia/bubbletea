package bubbletea

import chisel3._
import chisel3.util.Decoupled
import chisel3.util._

class StreamingEngineCompressedConfigurationChannelBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  val isValid = Bool()
  val stream = UInt(params.seStreamIdWidth.W)
  val elementWidth = UInt(2.W) //should be enum
  val loadStoreOrMod = Bool()
  val dimOffsetOrModSize = UInt((params.seOffsetWidth max params.seSizeWidth).W)
  val dimSizeOtModTargetAndModBehaviour = UInt((params.seSizeWidth max 3/*2 bits for target and 1 for behaviour*/).W)
  val end = Bool()
  val start = Bool()
  val dimStrideOrModDisplacement = UInt(params.seStrideWidth.W)
  val vectorize = Bool()
}

class SeConfiguratorControlBundle extends Bundle {
  val reset = Output(Bool())
  val configure = Output(Bool())
  val done = Input(Bool())
}

class SeConfigurator[T <: Data](params: BubbleteaParams[T]) extends Module {
  val io = IO(new Bundle {
    val control = Flipped(new SeConfiguratorControlBundle)
    val instructionsMemory = new StreamingEngineInstructionsMemoryBundle(params)
    val seOutput = Decoupled(new StreamingEngineConfigurationChannelBundle(params))
  })

  object State extends ChiselEnum {
    val ready, configuring, done = Value
  }

  val state = withReset(reset.asBool || io.control.reset)(RegInit(State.ready))
  val instructionCounter = withReset(reset.asBool || io.control.reset)(Counter(params.maxConfigurationInstructions))
  val nextAddress = Wire(UInt(log2Ceil(params.maxConfigurationInstructions).W))

  io.instructionsMemory.address := nextAddress

  // Decode the compressed instruction
  val compressedInstruction = io.instructionsMemory.data

  io.seOutput.bits.start := compressedInstruction.start
  io.seOutput.bits.end := compressedInstruction.end
  io.seOutput.bits.loadStore := compressedInstruction.loadStoreOrMod
  io.seOutput.bits.elementWidth := compressedInstruction.elementWidth
  io.seOutput.bits.stream := compressedInstruction.stream
  io.seOutput.bits.mod := compressedInstruction.loadStoreOrMod && !compressedInstruction.start
  io.seOutput.bits.vectorize := compressedInstruction.vectorize
  io.seOutput.bits.modTarget := compressedInstruction.dimSizeOtModTargetAndModBehaviour(1, 0)
  io.seOutput.bits.modBehaviour := compressedInstruction.dimSizeOtModTargetAndModBehaviour(2)
  io.seOutput.bits.modDisplacement := compressedInstruction.dimStrideOrModDisplacement
  io.seOutput.bits.modSize := compressedInstruction.dimOffsetOrModSize
  io.seOutput.bits.dimOffset := compressedInstruction.dimOffsetOrModSize
  io.seOutput.bits.dimStride := compressedInstruction.dimStrideOrModDisplacement
  io.seOutput.bits.dimSize := compressedInstruction.dimSizeOtModTargetAndModBehaviour

  io.seOutput.valid := DontCare
  io.control.done := DontCare
  nextAddress := DontCare
  switch(state) {
    is(State.ready) {
      io.seOutput.valid := false.B
      io.control.done := false.B

      when(io.control.configure) {
        state := State.configuring
        nextAddress := instructionCounter.value
      }
    }
    is(State.configuring) {
      io.seOutput.valid := compressedInstruction.isValid
      io.control.done := false.B

      when(io.seOutput.fire) {
        nextAddress := instructionCounter.value + 1.U
        instructionCounter.inc()
      } .otherwise(
        nextAddress := instructionCounter.value
      )

      when(!compressedInstruction.isValid) {
        state := State.done
      }
    }
    is(State.done) {
      io.seOutput.valid := false.B
      io.control.done := true.B

    }
  }


}
