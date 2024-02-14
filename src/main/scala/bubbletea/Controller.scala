package bubbletea

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._

class ConfigurationBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  val static = new AcceleratorStaticConfigurationBundle(params)
  val streamingEngineInstructions = Vec(params.maxConfigurationInstructions, new StreamingEngineCompressedConfigurationChannelBundle(params))
}

class ControlBundle(socParams: SocParams) extends Bundle {
  val run = Output(Bool())
  val runTriggered = Input(Bool())
  val done = Input(Bool())
  val loadBitstream = Output(Bool())
  val loadBitsteamTriggered = Input(Bool())
  val loadBitstreamDone = Input(Bool())
  val configurationDone = Input(Bool())
  val bitstreamBaseAddress = Output(UInt(socParams.xLen.W))
}

class AcceleratorStaticConfigurationBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  val streamingStage = new StreamingStageStaticConfigurationBundle(params)
  val mesh = Vec(params.maxInitiationInterval, Vec(params.meshRows, Vec(params.meshColumns, new ProcessingElementConfigBundle(params))))
  val delayer = new DelayerConfigBundle(params)
}

class AcceleratorControlBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  val reset = Output(Bool())
  val run = Output(Bool())
  val done = Input(Bool())
}

class ControllerIo[T <: Data](params: BubbleteaParams[T], socParams: SocParams) extends Bundle {
  val globalControl = Flipped(new ControlBundle(socParams))
  val acceleratorControl = new AcceleratorControlBundle(params)
  val staticConfiguration = Output(new AcceleratorStaticConfigurationBundle(params))
  val seConfigurationChannel = Decoupled(new StreamingEngineConfigurationChannelBundle(params))
}

class Controller[T <: Data: Arithmetic](params: BubbleteaParams[T], socParams: SocParams)(implicit p: Parameters) extends LazyModule {
  val node = TLIdentityNode()

  val configurationDma = LazyModule(new ConfigurationDma(params, socParams))

  // Connect the DMA to the TL bus
  node := configurationDma.node

  val configurationDmaIoSink = configurationDma.ioNode.makeSink()

  val ioNode = BundleBridgeSource(() => new ControllerIo(params, socParams))

  lazy val module = new LazyModuleImp(this) {
    // Instantiations
    val io = ioNode.bundle

    val configurationDmaIo = configurationDmaIoSink.bundle

    object State extends ChiselEnum {
      val done, configuringSe, configuringSeAndStatic, computing = Value
    }

    val state = RegInit(State.done)

    val seConfigurator = Module(new SeConfigurator(params))
    val scConfigurator = Module(new ScConfigurator(params, socParams))

    // Wires
    val configurationDone = Wire(Bool())
    val runTriggered = Wire(Bool())


    
    // Configuration Data path connections
  
    io.seConfigurationChannel <> seConfigurator.io.seOutput
    io.staticConfiguration := scConfigurator.io.staticConfiguration
    configurationDmaIo.configurationMemoryInterface.streamingEngineInstructions :<>= seConfigurator.io.instructionsMemory
    configurationDmaIo.configurationMemoryInterface.staticConfiguration :<>= scConfigurator.io.staticConfigurationMemory



    // Control logic

    // FSM State transitions
    switch(state) {
      is(State.done) {
        when(io.globalControl.run) {
          when(configurationDmaIo.streamingEngineInstructionsDone) {
            // Both the SEI and SC are available in the configuration memory
            when(configurationDmaIo.done) {
              state := State.configuringSeAndStatic
            }.otherwise {
              state := State.configuringSe
            }
          }
        }
      }
      is(State.configuringSe) {
        when(configurationDmaIo.done) {
          state := State.configuringSeAndStatic
        }
      }
      is(State.configuringSeAndStatic) {
        when(seConfigurator.io.control.done && scConfigurator.io.control.done) {
          state := State.computing
        }
      }
      is(State.computing) {
        when(io.acceleratorControl.done) {
          state := State.done
        }
      }
    }

    // FSM Outputs
    runTriggered := (state === State.done) && io.globalControl.run && configurationDmaIo.streamingEngineInstructionsDone
    configurationDone := state === State.computing || state === State.done
    io.globalControl.done := state === State.done
    seConfigurator.io.control.configure := state === State.configuringSe || state === State.configuringSeAndStatic
    scConfigurator.io.control.configure := state === State.configuringSeAndStatic

    // Global control outputs
    io.globalControl.runTriggered := runTriggered
    io.globalControl.configurationDone := configurationDone
    io.globalControl.loadBitstreamDone := configurationDmaIo.done
    io.globalControl.loadBitsteamTriggered := configurationDmaIo.startTriggered

    // Control outputs to the DMA
    configurationDmaIo.start := io.globalControl.loadBitstream && configurationDone
    configurationDmaIo.configurationBaseAddress := io.globalControl.bitstreamBaseAddress

    // Control outputs to the accelerator
    io.acceleratorControl.run := state === State.computing
    io.acceleratorControl.reset := io.globalControl.runTriggered

    // Control outputs to the configurators
    seConfigurator.io.control.reset := io.globalControl.runTriggered
    scConfigurator.io.control.reset := io.globalControl.runTriggered
  }
}