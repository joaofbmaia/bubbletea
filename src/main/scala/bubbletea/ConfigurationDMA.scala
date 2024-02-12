package bubbletea

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.Annotated

// This represents the bitstream disposition in memory (remember that the lower fields of the bundle are the higher bytes in memory)
class BitstreamBundle[T <: Data](params: BubbleteaParams[T], socParams: SocParams) extends Bundle {
  // the paddings are so that the DMA can assume that it will always receive a multiple of the cache line size
  val padding1 = UInt(calcAlignPadding((new AcceleratorStaticConfigurationBundle(params)).getWidth, socParams.cacheLineBytes * 8).W)
  val static = new AcceleratorStaticConfigurationBundle(params)
  val padding0 = UInt(calcAlignPadding((params.maxConfigurationInstructions * (new StreamingEngineBytePaddedCompressedConfigurationChannelBundle(params, socParams)).getWidth), socParams.cacheLineBytes * 8).W)
  val streamingEngineInstructions = Vec(params.maxConfigurationInstructions, new StreamingEngineBytePaddedCompressedConfigurationChannelBundle(params, socParams))
}

class ConfigurationMemoryBundle[T <: Data](params: BubbleteaParams[T], socParams: SocParams) extends Bundle {
  val scNumBytes = (new BitstreamBundle[T](params, socParams)).static.getWidth / 8 + (new BitstreamBundle[T](params, socParams)).padding1.getWidth / 8
  val scLineWidth = socParams.cacheLineBytes
  val scLines = scNumBytes / scLineWidth

  val streamingEngineInstructionsMemoryAddress = Output(UInt(log2Ceil(params.maxConfigurationInstructions).W))
  val streamingEngineInstructionsMemoryData = Input(new StreamingEngineCompressedConfigurationChannelBundle(params))
  val staticConfigurationMemoryAddress = Output(UInt(log2Ceil(scLines).W))
  val staticConfigurationMemoryData = Input(UInt(scLineWidth.W))
}

class StreamingEngineBytePaddedCompressedConfigurationChannelBundle[T <: Data](params: BubbleteaParams[T], socParams: SocParams) extends Bundle {
  val padding = UInt(calcAlignPadding(compressedInstruction.getWidth, socParams.frontBusDataBits).W)
  val compressedInstruction = new StreamingEngineCompressedConfigurationChannelBundle(params)
}

class ConfigurationDMA[T <: Data: Arithmetic](params: BubbleteaParams[T], socParams: SocParams)(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
    name = "bubbletea-configuration-dma",
    sourceId = IdRange(0, 1)
  )))))

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {
      val configurationBaseAddress = Input(UInt(64.W))
      val start = Input(Bool())
      val streamingEngineInstructionsDone = Output(Bool())
      val done = Output(Bool())
      val controlReset = Input(Bool())
      val configurationMemoryInterface = Flipped(new ConfigurationMemoryBundle(params, socParams))
    })

    val (mem, edge) = node.out.head
    val addressBits = edge.bundle.addressBits
    val beatBytes = edge.bundle.dataBits / 8

    object State extends ChiselEnum {
      val done, seiTransfering, scTransfering = Value
    }

    val state = withReset(reset.asBool || io.controlReset)(RegInit(State.done))
    val address = Reg(UInt(addressBits.W))

    // SEI = Streaming Engine Instructions
    // SC = Static Configuration

    val seiBytesPerInstruction = (new StreamingEngineBytePaddedCompressedConfigurationChannelBundle(params, socParams)).getWidth / 8
    require(seiBytesPerInstruction % beatBytes == 0, "Streaming Engine instruction size must be a multiple of the bus size. This shouldn't ever happen, as the intruction bundle is padded to the bus")
    val seiBeatsPerBurst = seiBytesPerInstruction / beatBytes
    val seiNumBursts = params.maxConfigurationInstructions
    
    val seiRequestesLeft = withReset(reset.asBool || io.controlReset)(RegInit(seiNumBursts.U))
    val seiBurstsLeft = withReset(reset.asBool || io.controlReset)(RegInit(seiNumBursts.U))

    val seiBeatsBuffer = Reg(Vec(seiBeatsPerBurst - 1, UInt(beatBytes.W)))
    val seiMemory = SyncReadMem(seiNumBursts, new StreamingEngineCompressedConfigurationChannelBundle(params))
    val seiBeatsLeft = withReset(reset.asBool || io.controlReset)(RegInit(seiBeatsPerBurst.U))


    val scBaseAddress = WireInit(io.configurationBaseAddress + (new BitstreamBundle[T](params, socParams)).streamingEngineInstructions.getWidth.U / 8.U + (new BitstreamBundle[T](params, socParams)).padding0.getWidth.U / 8.U)

    val scBlockBytes = socParams.cacheLineBytes
    val scNumBytes = (new BitstreamBundle[T](params, socParams)).static.getWidth / 8 + (new BitstreamBundle[T](params, socParams)).padding1.getWidth / 8
    require(scNumBytes % scBlockBytes == 0, "Static Configuration size must be a multiple of the cache line size. This shouldn't ever happen, as the static configuration bundle is padded to the cache line")
    val scBeatsPerBlock = scBlockBytes / beatBytes
    val scNumBlocks = scNumBytes / scBlockBytes

    val scRequestesLeft = withReset(reset.asBool || io.controlReset)(RegInit(scNumBlocks.U))
    val scResponseBytesLeft = withReset(reset.asBool || io.controlReset)(RegInit(scNumBytes.U))

    val scMemory = SyncReadMem(scNumBlocks, Vec(scBeatsPerBlock, UInt((beatBytes * 8).W)))
    // initialize the write mask to write the first beat (0001). also the mask is one hot, since we can only write one beat at a time
    val scWriteMask = withReset(reset.asBool || io.controlReset)(RegInit(VecInit(Seq.tabulate(scBeatsPerBlock)(i => if(i == 0) true.B else false.B))))
    val scMemoryWriteData = WireInit(VecInit(Seq.fill(scBeatsPerBlock)(mem.d.bits.data)))


    // Connect local configuration memory interfaces
    io.configurationMemoryInterface.streamingEngineInstructionsMemoryData := seiMemory.read(io.configurationMemoryInterface.streamingEngineInstructionsMemoryAddress)
    io.configurationMemoryInterface.staticConfigurationMemoryData := scMemory.read(io.configurationMemoryInterface.staticConfigurationMemoryAddress).asUInt

    io.streamingEngineInstructionsDone := state === State.scTransfering
    io.done := state === State.done

    mem.a.valid := false.B
    mem.a.bits := DontCare
    mem.d.ready := false.B

    switch(state) {
      is(State.done) {
        when(io.start) {
          state := State.seiTransfering
          address := io.configurationBaseAddress
        }
      }
      // This state is responsible for transfering the Streaming Engine Instructions, with burst of one instruction
      is(State.seiTransfering) {
        mem.a.bits := edge.Get(
          fromSource = 0.U,
          toAddress = address,
          lgSize = log2Ceil(seiBytesPerInstruction).U)._2

        // Send requests (ask for burst of one instruction)
        when(seiRequestesLeft > 0.U) {
          mem.a.valid := true.B
          when(mem.a.ready) {
            seiRequestesLeft := seiRequestesLeft - 1.U
            address := address + seiBytesPerInstruction.U
          }
        }.otherwise {
          mem.a.valid := false.B
        }

        // Receive data
        when(seiBurstsLeft > 0.U) {
          mem.d.ready := true.B
          when(mem.d.valid && mem.d.bits.opcode === TLMessages.AccessAckData) {
            // Save the data
            when(seiBeatsLeft > 1.U) {
              // This is not the last beat, save it in the buffer
              seiBeatsBuffer(seiBeatsPerBurst.U - seiBeatsLeft) := mem.d.bits.data
              seiBeatsLeft := seiBeatsLeft - 1.U
            } .elsewhen(seiBeatsLeft === 1.U) {
              // This is the last beat
              seiMemory.write((seiNumBursts.U - seiBurstsLeft), (mem.d.bits.data ## seiBeatsBuffer.asUInt).asTypeOf(new StreamingEngineCompressedConfigurationChannelBundle(params)))
              seiBeatsLeft := seiBeatsPerBurst.U
              seiBurstsLeft := seiBurstsLeft - 1.U
            }
          }
        }.otherwise {
          mem.d.ready := false.B
        }

        when(seiRequestesLeft === 0.U && seiBurstsLeft === 0.U) {
          state := State.scTransfering
          address := scBaseAddress
        }
      }
      // This state is responsible for transfering the Static Configuration, with bursts of a cache line
      is(State.scTransfering) {
        mem.a.bits := edge.Get(
          fromSource = 0.U,
          toAddress = address,
          lgSize = log2Ceil(scBlockBytes).U)._2

        // Send requests (ask for burst of one cache line)
        when(scRequestesLeft > 0.U) {
          mem.a.valid := true.B
          when(mem.a.ready) {
            scRequestesLeft := scRequestesLeft - 1.U
            address := address + scBlockBytes.U
          }
        }.otherwise {
          mem.a.valid := false.B
        }

        // Receive data
        when(scResponseBytesLeft > 0.U) {
          mem.d.ready := true.B
          when(mem.d.valid && mem.d.bits.opcode === TLMessages.AccessAckData) {
            scResponseBytesLeft := scResponseBytesLeft - beatBytes.U
            scMemory.write((scNumBytes.U - scResponseBytesLeft) / scBlockBytes.U, scMemoryWriteData, scWriteMask)
            scWriteMask := rotateLeft(scWriteMask.asUInt, 1).asTypeOf(scWriteMask) // rotate the mask to write the next beat
          }
        }.otherwise {
          mem.d.ready := false.B
        }

        when(scRequestesLeft === 0.U && scResponseBytesLeft === 0.U) {
          state := State.done
        }
      }
    }
    
  }
}