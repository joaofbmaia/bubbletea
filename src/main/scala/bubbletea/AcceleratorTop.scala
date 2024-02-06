package bubbletea

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.Decoupled
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}

class AcceleratorTop[T <: Data: Arithmetic](val config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {
    val configuration = Input(new ConfigurationBundle(config))
    val globalControl = Flipped(new ControlBundle)

    val memory = AXI4Bundle(new AXI4BundleParameters(config.seAddressWidth, config.seAxiDataWidth, 1))
  })

  val controller = Module(new Controller(config))

  val meshWithDelays = Module(new MeshWithDelays(config))

  val streamingStage = Module(new StreamingStage(config))

  io.memory :<>= streamingStage.io.memory

  controller.io.configuration := io.configuration
  controller.io.globalControl :<>= io.globalControl

  streamingStage.io.control.reset := controller.io.acceleratorControl.reset
  streamingStage.io.control.meshRun := controller.io.acceleratorControl.run
  controller.io.acceleratorControl.done := streamingStage.io.control.done

  meshWithDelays.io.fire := streamingStage.io.control.meshFire
  meshWithDelays.io.in := streamingStage.io.meshDataOut
  streamingStage.io.meshDataIn := meshWithDelays.io.out

  // Configuration

  streamingStage.io.staticConfiguration := controller.io.staticConfiguration.streamingStage
  streamingStage.io.seConfigurationChannel :<>= controller.io.seConfigurationChannel

  meshWithDelays.io.meshConfiguration := controller.io.staticConfiguration.mesh
  meshWithDelays.io.delayerConfiguration := controller.io.staticConfiguration.delayer
}
