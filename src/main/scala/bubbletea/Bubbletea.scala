package bubbletea

import chisel3._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.tile._

class Bubbletea[T <: Data: Arithmetic](params: BubbleteaParams[T])(implicit p: Parameters) extends LazyModule {
    // Control TileLink Interface (connected to the periphery bus as a slave)
    val device = new SimpleDevice("bubbletea", Seq("inesc,bubbletea0"))
    val controlNode = TLRegisterNode(
        address = Seq(AddressSet(params.baseAddress, 0x1000 - 1)),
        device = device,
        beatBytes = p(PeripheryBusKey).beatBytes
    )
    // DMA TileLink Interface (connected to the front bus as a master)
    val dmaNode = TLIdentityNode()

    // The DMA crossbar, used so that both the streaming engine and the configuration DMA can access the front bus
    // The streaming engine has priority over the configuration DMA, since it uses ID 0 and the configuration DMA uses ID 1
    val dmaXbar = LazyModule(new TLXbar(TLArbiter.lowestIndexFirst))

    // Streaming Engine AXI4 Node
    val seAxiNode = AXI4MasterNode(Seq(AXI4MasterPortParameters(
        masters = Seq(AXI4MasterParameters(
            name = "bubbletea-streaming",
            id = IdRange(0, 1),
            maxFlight = Some(1)
        ))
    )))

    // Convert the AXI4 interface to TileLink
    val seNode = TLIdentityNode()
    seNode := 
        AXI4ToTL() := 
        AXI4UserYanker(Some(1)) :=
        AXI4Fragmenter() :=
        seAxiNode


    val socParamsController = SocParams(
        cacheLineBytes = p(FrontBusKey).blockBytes,
        frontBusAddressBits = 0, // the controller does not use this field
        frontBusDataBits = p(FrontBusKey).beatBytes * 8,
        xLen = p(XLen)
    )

    // Controller
    val controller = LazyModule(new Controller(params, socParamsController))

    // Connect both the controller(contains the configuration DMA) and the streaming engine to the DMA crossbar
    dmaXbar.node := seNode
    dmaXbar.node := controller.node

    // Connect the DMA crossbar to the top node
    dmaNode := dmaXbar.node

    
    val controllerIoSink = controller.ioNode.makeSink()

    lazy val module = new LazyModuleImp(this) {
        val socParams = SocParams(
            cacheLineBytes = p(FrontBusKey).blockBytes,
            frontBusAddressBits = seAxiNode.out.head._1.params.addrBits,
            frontBusDataBits = p(FrontBusKey).beatBytes * 8,
            xLen = p(XLen)
        )

        // Control Registers
        val globalControl = Wire(new ControlBundle(socParams))

        val run = RegInit(false.B)
        val done = Wire(Bool())
        val loadBitstream = RegInit(false.B)
        val loadBitstreamDone = Wire(Bool())
        val configurationDone = Wire(Bool())
        val bitstreamBaseAddress = Reg(UInt(socParams.xLen.W))

        globalControl.run := run
        done := globalControl.done
        globalControl.loadBitstream := loadBitstream
        loadBitstreamDone := globalControl.loadBitstreamDone
        configurationDone := globalControl.configurationDone
        globalControl.bitstreamBaseAddress := bitstreamBaseAddress

        when(globalControl.runTriggered) {
            run := false.B
        }

        when(globalControl.loadBitsteamTriggered) {
            loadBitstream := false.B
        }

        // Streaming Engine Node
        val (axi, _) = seAxiNode.out.head

        // Controller
        val controllerIo = controllerIoSink.bundle

        controlNode.regmap(
            0x00 -> Seq(
                RegField(1, run, RegFieldDesc("run", "Run the accelerator"))
            ),
            0x01 -> Seq(
                RegField.r(1, done, RegFieldDesc("done", "Accelerator is done"))
            ),
            0x02 -> Seq(
                RegField(1, loadBitstream, RegFieldDesc("loadBitstream", "Load the configuration bitstream"))
            ),
            0x03 -> Seq(
                RegField.r(1, loadBitstreamDone, RegFieldDesc("loadBitstreanDone", "The configuration bitsteam is loaded (but not necessarily configured)"))
            ),
            0x04 -> Seq(
                RegField.r(1, configurationDone, RegFieldDesc("configurationDone", "The bitstream is configured in the accelerator and the accelerator will run (a new bitstream can be loaded now)"))
            ),
            0x10 -> Seq(
                RegField(socParams.xLen, bitstreamBaseAddress, RegFieldDesc("bitstreamBaseAddress", "Configuration bitstream base address"))
            )
        )

        // Accelerator

        val meshWithDelays = Module(new MeshWithDelays(params))

        val streamingStage = Module(new StreamingStage(params, socParams))

        axi :<>= streamingStage.io.memory

        controllerIo.globalControl :<>= globalControl

        streamingStage.io.control.reset := controllerIo.acceleratorControl.reset
        streamingStage.io.control.meshRun := controllerIo.acceleratorControl.run
        controllerIo.acceleratorControl.done := streamingStage.io.control.done

        meshWithDelays.io.fire := streamingStage.io.control.meshFire
        meshWithDelays.io.in := streamingStage.io.meshDataOut
        streamingStage.io.meshDataIn := meshWithDelays.io.out

        // Configuration

        streamingStage.io.staticConfiguration := controllerIo.staticConfiguration.streamingStage
        streamingStage.io.seConfigurationChannel :<>= controllerIo.seConfigurationChannel

        meshWithDelays.io.meshConfiguration := controllerIo.staticConfiguration.mesh
        meshWithDelays.io.delayerConfiguration := controllerIo.staticConfiguration.delayer
    }   
}
