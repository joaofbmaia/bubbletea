package bubbletea

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.Decoupled
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}

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

class AcceleratorTop[T <: Data: Arithmetic](config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {
    val control = Flipped(new AcceleratorControlBundle(config))

    // This must be internal in the future
    val staticConfiguration = Input(new AcceleratorStaticConfigurationBundle(config))
    val seConfigurationChannel = Flipped(Decoupled(new StreamingEngineConfigurationChannelBundle(config)))

    val memory = AXI4Bundle(new AXI4BundleParameters(config.seAddressWidth, config.seAxiDataWidth, 1))
  })

  val meshWithDelays = Module(new MeshWithDelays(config))

  val streamingStage = Module(new StreamingStage(config))

  io.memory :<>= streamingStage.io.memory

  streamingStage.io.control.reset := io.control.reset
  streamingStage.io.control.meshRun := io.control.run
  meshWithDelays.io.fire := streamingStage.io.control.meshFire
  io.control.done := streamingStage.io.control.done

  meshWithDelays.io.in := streamingStage.io.meshDataOut
  streamingStage.io.meshDataIn := meshWithDelays.io.out

  // Configuration

  streamingStage.io.staticConfiguration := io.staticConfiguration.streamingStage
  streamingStage.io.seConfigurationChannel :<>= io.seConfigurationChannel

  meshWithDelays.io.meshConfiguration := io.staticConfiguration.mesh
  meshWithDelays.io.delayerConfiguration := io.staticConfiguration.delayer
}
