package bubbletea

import chisel3._
import chisel3.util.log2Ceil

/* Instead of normal RF with normal write ports that use an adress to select in which register to write, 
this module allows the same write port to broadcast into multiple registers, 
by allowing each register to select its input port. 
This is because Morpher mapping works like this and I can't change it... */

class RegisterFile[T <: Data](gen: T, n: Int, readPorts: Int, writePorts: Int) extends Module {
  require(readPorts >= 0)
  val io = IO(new Bundle {
    val writeSourceSel = Input(Vec(n, UInt(log2Ceil(writePorts).W)))
    val writeEnable = Input(Vec(n, Bool()))
    val writeData = Input(Vec(writePorts, gen))
    val readAddress = Input(Vec(readPorts, UInt(log2Ceil(gen.getWidth).W)))
    val readData = Output(Vec(readPorts, gen))
  })

  val reg = Reg(Vec(n, gen))

  for (i <- 0 until n) {
    when(io.writeEnable(i)) {
      reg(i) := io.writeData(io.writeSourceSel(i))
    }
  }

  for (i <- 0 until readPorts) {
    io.readData(i) := reg(io.readAddress(i))
  }
}
