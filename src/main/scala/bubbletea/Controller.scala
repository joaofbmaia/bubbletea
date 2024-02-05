package bubbletea

import chisel3._
import chisel3.util._

class ConfigurationBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  val static = new AcceleratorStaticConfigurationBundle(config)
  val streamingEngineInstructions = Vec(config.maxConfigurationInstructions, new StreamingEngineCompressedConfigurationChannelBundle(config))
}

class ControlBundle extends Bundle {
  val run = Output(Bool())
  val runTriggered = Input(Bool())
  val done = Input(Bool())
  val configurationDone = Input(Bool())
}

class AcceleratorStaticConfigurationBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  val streamingStage = new StreamingStageStaticConfigurationBundle(config)
  val mesh = Vec(config.meshRows, Vec(config.meshColumns, new ProcessingElementConfigBundle(config)))
  val delayer = new DelayerConfigBundle(config)
}

class AcceleratorControlBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  val reset = Output(Bool())
  val run = Output(Bool())
  val done = Input(Bool())
}

class Controller[T <: Data](config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {
    val configuration = Input(new ConfigurationBundle(config))
    val globalControl = Flipped(new ControlBundle)

    val acceleratorControl = new AcceleratorControlBundle(config)
    val staticConfiguration = new AcceleratorStaticConfigurationBundle(config)
    val seConfigurationChannel = Decoupled(new StreamingEngineConfigurationChannelBundle(config))
  })

  // This is the hot static configuration
  val currentStaticConfiguration = Reg(new AcceleratorStaticConfigurationBundle(config))

  object State extends ChiselEnum {
    val done, configuring, computing = Value
  }

  val state = RegInit(State.done)

  val seConfigurator = Module(new SeConfigurator(config))

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