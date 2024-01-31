package bubbletea

import chisel3._
import chisel3.util.log2Ceil
import chisel3.util.MuxLookup
import chisel3.util.RegEnable
import chisel3.reflect.DataMirror
import chisel3.experimental.requireIsChiselType

class OutRegsSrcSelBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  // Allowed Sources: FU output, inputs, RF
  val numSrcs = 1 /*fu output*/ + 4 /*inputs*/ + config.rfReadPorts
  val selWidth = log2Ceil(numSrcs)

  val north = UInt(selWidth.W)
  val south = UInt(selWidth.W)
  val west = UInt(selWidth.W)
  val east = UInt(selWidth.W)
}

class RfWritePortsSrcSelBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  // Allowed Sources: FU output, inputs
  val numSrcs = 1 /*fu output*/ + 4 /*inputs*/
  val selWidth = log2Ceil(numSrcs)

  val ports = Vec(config.rfWritePorts, UInt(selWidth.W))
}

class FuSrcSelBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  // Allowed Sources: inputs, RF
  val numSrcs = 4 /*inputs*/ + config.rfReadPorts
  val selWidth = log2Ceil(numSrcs)

  val a = UInt(selWidth.W)
  val b = UInt(selWidth.W)
}

class ProcessingElementConfigBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  val op = FUSel()
  val outRegsSel = new OutRegsSrcSelBundle(config)
  val rfWritePortsSel = new RfWritePortsSrcSelBundle(config)
  val fuSrcSel = new FuSrcSelBundle(config)
  val rfWriteAddr = Vec(config.rfWritePorts, UInt(log2Ceil(config.rfSize).W))
  val rfReadAddr = Vec(config.rfReadPorts, UInt(log2Ceil(config.rfSize).W))
  val rfWriteEn = Vec(config.rfWritePorts, Bool())
}

class ProcessingElementDataBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  val north = Output(config.dataType)
  val south = Output(config.dataType)
  val west = Output(config.dataType)
  val east = Output(config.dataType)
}

class ProcessingElement[T <: Data: Arithmetic](config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {
    val fire = Input(Bool())

    val in = Flipped(new ProcessingElementDataBundle(config))
    val out = new ProcessingElementDataBundle(config)

    val configuration = Input(new ProcessingElementConfigBundle(config))
  })

  // Instantiate Modules
  val functionalUnit = Module(new FunctionalUnit(config))

  val registerFile = Module(new RegisterFile(config.dataType, config.rfSize, config.rfReadPorts, config.rfWritePorts))


  val outputRegistersNext = Wire(new ProcessingElementDataBundle(config))
  val outputRegisters = RegEnable(outputRegistersNext, io.fire)
  
  val dontCareDefault = Wire(config.dataType)
  dontCareDefault := DontCare

  // Connect FU
  val fuSrcLookup = (Seq(io.in.north, io.in.south, io.in.west, io.in.east) ++
    Seq.tabulate(config.rfReadPorts)(i => registerFile.io.readData(i))).zipWithIndex.map { case (x, i) => i.U -> x }

  functionalUnit.io.a := MuxLookup(io.configuration.fuSrcSel.a, dontCareDefault)(fuSrcLookup)
  functionalUnit.io.b := MuxLookup(io.configuration.fuSrcSel.b, dontCareDefault)(fuSrcLookup)

  functionalUnit.io.op := io.configuration.op


  // Connect RF
  val rfWriteSrcLookup =
    Seq(functionalUnit.io.result, io.in.north, io.in.south, io.in.west, io.in.east).zipWithIndex.map { case (x, i) => i.U -> x }

  for (i <- 0 until config.rfWritePorts) {  
    registerFile.io.writeEnable(i) := io.configuration.rfWriteEn(i) && io.fire
    registerFile.io.writeAddress(i) := io.configuration.rfWriteAddr(i)
    registerFile.io.writeData(i) := MuxLookup(io.configuration.rfWritePortsSel.ports(i), dontCareDefault)(rfWriteSrcLookup)
  }

  for (i <- 0 until config.rfReadPorts) {
    registerFile.io.readAddress(i) := io.configuration.rfReadAddr(i)
  }


  // Connect output registers
  val outRegsSrcLookup = (Seq(functionalUnit.io.result, io.in.north, io.in.south, io.in.west, io.in.east) ++
    Seq.tabulate(config.rfReadPorts)(i => registerFile.io.readData(i))).zipWithIndex.map { case (x, i) => i.U -> x }

  outputRegistersNext.north := MuxLookup(io.configuration.outRegsSel.north, dontCareDefault)(outRegsSrcLookup)
  outputRegistersNext.south := MuxLookup(io.configuration.outRegsSel.south, dontCareDefault)(outRegsSrcLookup)
  outputRegistersNext.west := MuxLookup(io.configuration.outRegsSel.west, dontCareDefault)(outRegsSrcLookup)
  outputRegistersNext.east := MuxLookup(io.configuration.outRegsSel.east, dontCareDefault)(outRegsSrcLookup)

  io.out := outputRegisters

  
}
