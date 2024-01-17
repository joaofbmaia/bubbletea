package bubbletea

import chisel3._
import chisel3.util.log2Ceil
import scala.math.max
import chisel3.util.Decoupled
import chisel3.util.RegEnable

object PortSide extends ChiselEnum {
  val north, south, west, east = Value
}

class DstMaskElement[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  val used = Bool()
  val side = PortSide()
  val index = UInt(log2Ceil(max(config.meshRows, config.meshColumns)).W)
  val moduloCycle = UInt(log2Ceil(config.maxInitiationInterval).W)
}

object StreamRemaper {
  val latency = 1
}

class StreamRemaper[T <: Data](config: AcceleratorConfig[T]) extends Module {
  import StreamRemaper._
  val io = IO(new Bundle {
    val macroStreamsIn = Flipped(Decoupled(Vec(config.maxMacroStreams, Vec(config.macroStreamDepth, config.dataType))))
    val microStreamsOut = Decoupled(Vec(config.maxInitiationInterval, new MeshData(config)))
    val dstMask = Input(Vec(config.maxMacroStreams, Vec(config.macroStreamDepth, new DstMaskElement(config))))
  })

  val macroStreams = io.macroStreamsIn.bits
  val microStreams = Wire(Vec(config.maxInitiationInterval, new MeshData(config)))

  val upstreamValid = Wire(Bool())
  val upstreamReady = Wire(Bool())
  val downstreamValid = Wire(Bool())
  val downstreamReady = Wire(Bool())

  // default assignment
  microStreams := DontCare

  for (i <- 0 until config.maxMacroStreams) {
    for (j <- 0 until config.macroStreamDepth) {
      when (io.dstMask(i)(j).used) {
        when (io.dstMask(i)(j).side === PortSide.north) {
          microStreams(io.dstMask(i)(j).moduloCycle).north(io.dstMask(i)(j).index) := macroStreams(i)(j)
        }
        when (io.dstMask(i)(j).side === PortSide.south) {
          microStreams(io.dstMask(i)(j).moduloCycle).south(io.dstMask(i)(j).index) := macroStreams(i)(j)
        }
        when (io.dstMask(i)(j).side === PortSide.west) {
          microStreams(io.dstMask(i)(j).moduloCycle).west(io.dstMask(i)(j).index) := macroStreams(i)(j)
        }
        when (io.dstMask(i)(j).side === PortSide.east) {
          microStreams(io.dstMask(i)(j).moduloCycle).east(io.dstMask(i)(j).index) := macroStreams(i)(j)
        }
      }
    }
  }

  // Registered Hadshake Protocol
  // https://www.itdev.co.uk/blog/pipelining-axi-buses-registered-ready-signals
  upstreamValid := io.macroStreamsIn.valid
  downstreamValid := RegEnable(upstreamValid, false.B, upstreamReady)
  upstreamReady := downstreamReady || !downstreamValid
  downstreamReady := io.microStreamsOut.ready

  io.macroStreamsIn.ready := upstreamReady
  io.microStreamsOut.valid := downstreamValid
  io.microStreamsOut.bits := RegEnable(microStreams, upstreamReady)
}


class StreamingStage[T <: Data](config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {
    val macroStreamBuffer = Flipped(Decoupled((Vec(config.maxMacroStreams, Vec(config.macroStreamDepth, config.dataType)))))
    val meshOut = Decoupled(new MeshData(config))

    val initiationIntervalMinusOne = Input(UInt(log2Ceil(config.maxInitiationInterval).W))
    val dstMask = Input(Vec(config.maxMacroStreams, Vec(config.macroStreamDepth, new DstMaskElement(config))))
  })

  val currentModuloCycle = RegInit(0.U(log2Ceil(config.maxInitiationInterval).W))

  when (io.meshOut.fire && (currentModuloCycle === io.initiationIntervalMinusOne)) {
    currentModuloCycle := 0.U
  } .elsewhen (io.meshOut.fire) {
    currentModuloCycle := currentModuloCycle + 1.U
  }

  val remaper = Module(new StreamRemaper(config))

  remaper.io.macroStreamsIn :<>= io.macroStreamBuffer

  io.meshOut.bits := remaper.io.microStreamsOut.bits(currentModuloCycle)
  
  io.meshOut.valid := remaper.io.microStreamsOut.valid
  
  remaper.io.microStreamsOut.ready := io.meshOut.fire && (currentModuloCycle === io.initiationIntervalMinusOne)

  remaper.io.dstMask := io.dstMask

}
