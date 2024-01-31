package bubbletea

import chisel3._
import chisel3.util.log2Ceil

class RegisterFile[T <: Data](gen: T, n: Int, readPorts: Int, writePorts: Int) extends Module {
    require(readPorts >= 0)
    val io = IO(new Bundle {
        val writeEnable   = Input(Vec(writePorts, Bool()))
        val writeAddress = Input(Vec(writePorts, UInt(log2Ceil(gen.getWidth).W)))
        val writeData = Input(Vec(writePorts, gen))
        val readAddress = Input(Vec(readPorts, UInt(log2Ceil(gen.getWidth).W)))
        val readData = Output(Vec(readPorts, gen))
    })
    
    val reg = Reg(Vec(n, gen))

    for (i <- 0 until writePorts) {
        when (io.writeEnable(i)) {
            reg(io.writeAddress(i)) := io.writeData(i)
        }
    }

    for (i <- 0 until readPorts) {
        io.readData(i) := reg(io.readAddress(i))
    }
}