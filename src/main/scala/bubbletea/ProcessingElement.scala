package bubbletea

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.MuxLookup
import chisel3.util.RegEnable
import chisel3.reflect.DataMirror
import chisel3.experimental.requireIsChiselType

class OutRegsSrcSelBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  // Allowed Sources: FU output, inputs, RF
  val numSrcs = 1 /*fu output*/ + 4 /*inputs*/ + params.rfReadPorts
  val selWidth = log2Ceil(numSrcs)

  val north = UInt(selWidth.W)
  val south = UInt(selWidth.W)
  val west = UInt(selWidth.W)
  val east = UInt(selWidth.W)
}

class RfWritePortsSrcSelBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  // Allowed Sources: FU output, inputs
  val numSrcs = 1 /*fu output*/ + 4 /*inputs*/
  val selWidth = log2Ceil(numSrcs)

  val ports = Vec(params.rfWritePorts, UInt(selWidth.W))
}

class FuSrcSelBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  // Allowed Sources: inputs, RF
  val numSrcs = 4 /*inputs*/ + params.rfReadPorts
  val selWidth = log2Ceil(numSrcs)

  val a = UInt(selWidth.W)
  val b = UInt(selWidth.W)
}

class ProcessingElementConfigBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  val op = FUSel()
  val outRegsSel = new OutRegsSrcSelBundle(params)
  val rfWritePortsSel = new RfWritePortsSrcSelBundle(params)
  val fuSrcSel = new FuSrcSelBundle(params)
  val rfWriteAddr = Vec(params.rfWritePorts, UInt(log2Ceil(params.rfSize).W))
  val rfReadAddr = Vec(params.rfReadPorts, UInt(log2Ceil(params.rfSize).W))
  val rfWriteEn = Vec(params.rfWritePorts, Bool())
}

class ProcessingElementDataBundle[T <: Data](params: BubbleteaParams[T]) extends Bundle {
  val north = Output(params.dataType)
  val south = Output(params.dataType)
  val west = Output(params.dataType)
  val east = Output(params.dataType)
}

class ProcessingElement[T <: Data: Arithmetic](params: BubbleteaParams[T]) extends Module {
  val io = IO(new Bundle {
    val fire = Input(Bool())

    val in = Flipped(new ProcessingElementDataBundle(params))
    val out = new ProcessingElementDataBundle(params)

    val configuration = Input(new ProcessingElementConfigBundle(params))
  })

  // Instantiate Modules
  val functionalUnit = Module(new FunctionalUnit(params))

  val registerFile = Module(new RegisterFile(params.dataType, params.rfSize, params.rfReadPorts, params.rfWritePorts))


  val outputRegistersNext = Wire(new ProcessingElementDataBundle(params))
  val outputRegisters = RegEnable(outputRegistersNext, io.fire)
  
  val dontCareDefault = Wire(params.dataType)
  dontCareDefault := DontCare

  // Connect FU
  val fuSrcLookup = (Seq(io.in.north, io.in.south, io.in.west, io.in.east) ++
    Seq.tabulate(params.rfReadPorts)(i => registerFile.io.readData(i))).zipWithIndex.map { case (x, i) => i.U -> x }

  functionalUnit.io.a := MuxLookup(io.configuration.fuSrcSel.a, dontCareDefault)(fuSrcLookup)
  functionalUnit.io.b := MuxLookup(io.configuration.fuSrcSel.b, dontCareDefault)(fuSrcLookup)

  functionalUnit.io.op := io.configuration.op


  // Connect RF
  val rfWriteSrcLookup =
    Seq(functionalUnit.io.result, io.in.north, io.in.south, io.in.west, io.in.east).zipWithIndex.map { case (x, i) => i.U -> x }

  for (i <- 0 until params.rfWritePorts) {  
    registerFile.io.writeEnable(i) := io.configuration.rfWriteEn(i) && io.fire
    registerFile.io.writeAddress(i) := io.configuration.rfWriteAddr(i)
    registerFile.io.writeData(i) := MuxLookup(io.configuration.rfWritePortsSel.ports(i), dontCareDefault)(rfWriteSrcLookup)
  }

  for (i <- 0 until params.rfReadPorts) {
    registerFile.io.readAddress(i) := io.configuration.rfReadAddr(i)
  }


  // Connect output registers
  val outRegsSrcLookup = (Seq(functionalUnit.io.result, io.in.north, io.in.south, io.in.west, io.in.east) ++
    Seq.tabulate(params.rfReadPorts)(i => registerFile.io.readData(i))).zipWithIndex.map { case (x, i) => i.U -> x }

  outputRegistersNext.north := MuxLookup(io.configuration.outRegsSel.north, dontCareDefault)(outRegsSrcLookup)
  outputRegistersNext.south := MuxLookup(io.configuration.outRegsSel.south, dontCareDefault)(outRegsSrcLookup)
  outputRegistersNext.west := MuxLookup(io.configuration.outRegsSel.west, dontCareDefault)(outRegsSrcLookup)
  outputRegistersNext.east := MuxLookup(io.configuration.outRegsSel.east, dontCareDefault)(outRegsSrcLookup)

  io.out := outputRegisters

  
}
