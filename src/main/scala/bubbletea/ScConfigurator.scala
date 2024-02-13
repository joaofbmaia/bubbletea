package bubbletea

import chisel3._
import chisel3.util._

class ScConfiguratorControlBundle extends Bundle {
  val reset = Output(Bool())
  val configure = Output(Bool())
  val done = Input(Bool())
}

class ScConfigurator[T <: Data](params: BubbleteaParams[T], socParams: SocParams) extends Module {
  val io = IO(new Bundle {
    val control = Flipped(new ScConfiguratorControlBundle)
    val staticConfigurationMemory = new StaticConfigurationMemoryBundle(params, socParams)
    val staticConfiguration = Output(new AcceleratorStaticConfigurationBundle(params))
  })

  // Datapath

  val mask = Wire(Vec(io.staticConfigurationMemory.scLines, Bool()))

  val staticConfiguration = Reg(UInt((new AcceleratorStaticConfigurationBundle(params)).getWidth.W))
  io.staticConfiguration := staticConfiguration.asTypeOf(new AcceleratorStaticConfigurationBundle(params))

  val nextAddress = Wire(UInt(log2Ceil(io.staticConfigurationMemory.scLines).W))
  io.staticConfigurationMemory.address := nextAddress

  for (i <- 0 until io.staticConfigurationMemory.scLines) {
    when(mask(i)) {
      val l = i * io.staticConfigurationMemory.scLineWidth
      val h = if (i == io.staticConfigurationMemory.scLines - 1) {
          (i + 1) * io.staticConfigurationMemory.scLineWidth - 1
      } else {
          staticConfiguration.getWidth - 1
      }
      staticConfiguration(h, l) := io.staticConfigurationMemory.data
    }
  }

  // FSM

  object State extends ChiselEnum {
    val ready, configuring, done = Value
  }

  val state = withReset(reset.asBool || io.control.reset)(RegInit(State.ready))

  val lineCounter = withReset(reset.asBool || io.control.reset)(Counter(io.staticConfigurationMemory.scLines))

  io.control.done := false.B
  mask.foreach(_ := false.B)
  switch(state) {
    is(State.ready) {
      when(io.control.configure) {
        state := State.configuring
        nextAddress := lineCounter.value
      }
    }
    is(State.configuring) {
      mask(lineCounter.value) := true.B
      nextAddress := lineCounter.value + 1.U
      lineCounter.inc()
      when(lineCounter.value === io.staticConfigurationMemory.scLines.U) {
        state := State.done
      }
    }
    is(State.done) {
      io.control.done := true.B
    }
  }
  
}