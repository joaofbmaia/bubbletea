package bubbletea

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.Annotated


class ConfigurationDMA[T <: Data: Arithmetic](params: BubbleteaParams[T], socParams: SocParams)(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
    name = "bubbletea-configuration-dma",
    sourceId = IdRange(0, 1)
  )))))

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {
      val configurationBaseAddress = Input(UInt(64.W))
      val start = Input(Bool())
      val done = Output(Bool())
      val controlReset = Input(Bool())
    })

    val (mem, edge) = node.out.head
    val addressBits = edge.bundle.addressBits
    val beatBytes = edge.bundle.dataBits / 8
    val blockBytes = socParams.cacheLineBytes
    
    object State extends ChiselEnum {
      val done, transfering = Value
    }

    val bitstreamBytes = (new BitstreamBundle(params, socParams)).streamingEngineInstructions.getWidth + (new BitstreamBundle(params, socParams)).dummy.getWidth
    require(bitstreamBytes % blockBytes == 0, "Bitstream size must be a multiple of the cache block size")

    val state = withReset(reset.asBool || io.controlReset)(RegInit(State.done))
    val address = Reg(UInt(addressBits.W))
    val bytesRequestedLeft = withReset(reset.asBool || io.controlReset)(RegInit(UInt(bitstreamBytes.W)))
    val bytesReceivedLeft = withReset(reset.asBool || io.controlReset)(RegInit(UInt(bitstreamBytes.W)))

    io.done := state === State.done

    mem.a.valid := false.B
    mem.a.bits := edge.Get(
      fromSource = 0.U,
      toAddress = address,
      lgSize = log2Ceil(blockBytes).U)._2

    mem.d.ready := false.B

    switch(state) {
      is(State.done) {
        when(io.start) {
          state := State.transfering
          address := io.configurationBaseAddress
        }
      }
      is(State.transfering) {
        // Send requests
        when(bytesRequestedLeft > 0.U) {
          mem.a.valid := true.B
          when(mem.a.ready) {
            bytesRequestedLeft := bytesRequestedLeft - blockBytes.U
            address := address + blockBytes.U
          }
        }.otherwise {
          mem.a.valid := false.B
        }

        // Receive data
        when(bytesReceivedLeft > 0.U) {
          mem.d.ready := true.B
          when(mem.d.valid && mem.d.bits.opcode === TLMessages.AccessAckData) {
            bytesReceivedLeft := bytesReceivedLeft - beatBytes.U
            // save mem.d.bits.data
          }
        }.otherwise {
          mem.d.ready := false.B
        }

        when(bytesRequestedLeft === 0.U && bytesReceivedLeft === 0.U) {
          state := State.done
        }
      }
    }
    
  }
}