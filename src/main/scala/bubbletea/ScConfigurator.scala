package bubbletea

import chisel3._
import chisel3.util._
import os.stat

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

  val staticConfiguration = Reg(Vec(io.staticConfigurationMemory.scLines, UInt(io.staticConfigurationMemory.scLineWidth.W)))
  io.staticConfiguration := staticConfiguration.asTypeOf(new AcceleratorStaticConfigurationBundle(params))

  val nextAddress = Wire(UInt(log2Ceil(io.staticConfigurationMemory.scLines).W))
  io.staticConfigurationMemory.address := nextAddress

  staticConfiguration.zipWithIndex.foreach { case (line, i) =>
    when(mask(i)) {
      line := io.staticConfigurationMemory.data
    }
  }

  // FSM

  object State extends ChiselEnum {
    val ready, configuring, done = Value
  }

  val state = withReset(reset.asBool || io.control.reset)(RegInit(State.ready))

  val lineCounter = withReset(reset.asBool || io.control.reset)(Counter(io.staticConfigurationMemory.scLines + 1))

  io.control.done := false.B
  mask.foreach(_ := false.B)
  nextAddress := DontCare
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