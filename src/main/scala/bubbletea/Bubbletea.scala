package bubbletea

import chisel3._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.amba.axi4._

class Bubbletea[T <: Data: Arithmetic](params: BubbleteaParams[T])(implicit p: Parameters) extends LazyModule {
    val device = new SimpleDevice("bubbletea", Seq("inesc,bubbletea0"))
    val controlNode = TLRegisterNode(
        address = Seq(AddressSet(params.baseAddress, 0x1000 - 1)),
        device = device,
        beatBytes = p(PeripheryBusKey).beatBytes
    )
    val dmaNode = TLIdentityNode()

    val seAxiNode = AXI4MasterNode(Seq(AXI4MasterPortParameters(
        masters = Seq(AXI4MasterParameters(
            name = "bubbletea-streaming",
            id = IdRange(0, 1)
        ))
    )))

    dmaNode := 
        AXI4ToTL() := 
        AXI4UserYanker() :=
        AXI4Fragmenter() :=
        seAxiNode

    lazy val module = new LazyModuleImp(this) {

        // Control Registers
        val globalControl = Wire(new ControlBundle)

        val run = RegInit(false.B)
        val done = Wire(Bool())
        val configurationDone = Wire(Bool())

        globalControl.run := run
        done := globalControl.done
        configurationDone := globalControl.configurationDone

        when(globalControl.runTriggered) {
            run := false.B
        }

        controlNode.regmap(
            0x00 -> Seq(
                RegField(1, run, RegFieldDesc("run", "Run the accelerator"))
            ),
            0x04 -> Seq(
                RegField.r(1, done, RegFieldDesc("done", "Accelerator is done"))
            ),
            0x08 -> Seq(
                RegField.r(1, configurationDone, RegFieldDesc("configurationDone", "Configuration is done"))
            )
        )

        // Streaming Engine Node
        val (axi, _) = seAxiNode.out.head


        // Accelerator
        val controller = Module(new Controller(params))

        val meshWithDelays = Module(new MeshWithDelays(params))

        val streamingStage = Module(new StreamingStage(params))

        axi :<>= streamingStage.io.memory

        controller.io.configuration := 0.U.asTypeOf(controller.io.configuration) //io.configuration
        controller.io.globalControl :<>= globalControl

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
}
