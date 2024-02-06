package bubbletea

import chisel3._
import chisel3.util._

class ConfigurationBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  val static = new AcceleratorStaticConfigurationBundle(params)
  val streamingEngineInstructions = Vec(params.maxConfigurationInstructions, new StreamingEngineCompressedConfigurationChannelBundle(params))
}

class ControlBundle extends Bundle {
  val run = Output(Bool())
  val runTriggered = Input(Bool())
  val done = Input(Bool())
  val configurationDone = Input(Bool())
}

class AcceleratorStaticConfigurationBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  val streamingStage = new StreamingStageStaticConfigurationBundle(params)
  val mesh = Vec(params.meshRows, Vec(params.meshColumns, new ProcessingElementConfigBundle(params)))
  val delayer = new DelayerConfigBundle(params)
}

class AcceleratorControlBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  val reset = Output(Bool())
  val run = Output(Bool())
  val done = Input(Bool())
}

class Controller[T <: Data](params: BubbleteaParams[T]) extends Module {
  val io = IO(new Bundle {
    val configuration = Input(new ConfigurationBundle(params))
    val globalControl = Flipped(new ControlBundle)

    val acceleratorControl = new AcceleratorControlBundle(params)
    val staticConfiguration = new AcceleratorStaticConfigurationBundle(params)
    val seConfigurationChannel = Decoupled(new StreamingEngineConfigurationChannelBundle(params))
  })

  // This is the hot static configuration
  val currentStaticConfiguration = Reg(new AcceleratorStaticConfigurationBundle(params))

  object State extends ChiselEnum {
    val done, configuring, computing = Value
  }

  val state = RegInit(State.done)

  val seConfigurator = Module(new SeConfigurator(params))

  val controlReset = Wire(Bool())
  io.acceleratorControl.reset := controlReset
  seConfigurator.io.control.reset := controlReset

  // The SE configuration is read directly from the configuration bundle, since it will be saved in the SE's memory
  seConfigurator.io.configurationMemoryInput := io.configuration.streamingEngineInstructions

  // The SE configuration is sent to the SE
  io.seConfigurationChannel <> seConfigurator.io.seOutput

  // The static configuration is sent to the accelerator
  io.staticConfiguration := currentStaticConfiguration

  // Control logic below

  // Default values
  controlReset := false.B
  io.acceleratorControl.run := false.B
  io.globalControl.configurationDone := true.B
  io.globalControl.done := true.B
  io.globalControl.runTriggered := false.B
  seConfigurator.io.control.configure := false.B

  switch(state) {
    is(State.done) {
      when(io.globalControl.run) {
        state := State.configuring
        currentStaticConfiguration := io.configuration.static
        controlReset := true.B
        io.globalControl.runTriggered := true.B
      }
    }
    is(State.configuring) {
      io.globalControl.done := false.B
      io.globalControl.configurationDone := false.B
      seConfigurator.io.control.configure := true.B

      when(seConfigurator.io.control.done) {
        state := State.computing
      }

    }
    is(State.computing) {
      io.globalControl.done := false.B
      io.acceleratorControl.run := true.B
      

      when(io.acceleratorControl.done) {
        state := State.done
      }
    }
  }
}