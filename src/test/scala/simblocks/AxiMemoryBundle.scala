package simblocks

import chisel3._
import chisel3.util.Decoupled
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}

class AxiMemoryBundle(
  val axiAddrWidth:     Int,
  val axiDataWidth:     Int,
  val memoryDataWidth:  Int,
  val memoryAddrWidth:  Int,
  val memoryReadDelay:  Int,
  val memoryWriteDelay: Int)
    extends Module {

  val memoryNumBytes = memoryDataWidth / 8

  val io = IO(new Bundle {
    /* Control channel */
    val ctrlReset = Input(Bool())

    /* External read channel */
    val readEnable = Input(Bool())
    val readAddr = Input(UInt(memoryAddrWidth.W))
    val readData = Output(UInt(memoryDataWidth.W))

    /* External write channel */
    val writeEnable = Input(Bool())
    val writeAddr = Input(UInt(memoryAddrWidth.W))
    val writeData = Input(UInt(memoryDataWidth.W))
    val writeStrb = Input(UInt(memoryNumBytes.W))

    /* AXI4 interface */
    val axi = Flipped(new AXI4Bundle(new AXI4BundleParameters(axiAddrWidth, axiDataWidth, 1)))
  })

  val memory = Module(
    new AxiMemory(
      axiAddrWidth,
      memoryDataWidth,
      memoryAddrWidth,
      axiDataWidth,
      axiDataWidth,
      memoryReadDelay,
      memoryWriteDelay
    )
  )

  memory.io.ctrl_reset := io.ctrlReset

  memory.io.read_enable := io.readEnable
  memory.io.read_addr := io.readAddr
  io.readData := memory.io.read_data

  memory.io.write_enable := io.writeEnable
  memory.io.write_addr := io.writeAddr
  memory.io.write_data := io.writeData
  memory.io.write_strb := io.writeStrb

  // AW
  io.axi.aw.ready := memory.io.aw_ready
  memory.io.aw_valid := io.axi.aw.valid
  memory.io.aw_addr := io.axi.aw.bits.addr
  memory.io.aw_len := io.axi.aw.bits.len
  memory.io.aw_size := io.axi.aw.bits.size
  memory.io.aw_burst := io.axi.aw.bits.burst

  // W
  io.axi.w.ready := memory.io.w_ready
  memory.io.w_valid := io.axi.w.valid
  memory.io.w_data := io.axi.w.bits.data
  memory.io.w_strb := io.axi.w.bits.strb
  memory.io.w_last := io.axi.w.bits.last

  // B
  memory.io.b_ready := io.axi.b.ready
  io.axi.b.valid := memory.io.b_valid
  io.axi.b.bits.id := 0.U
  io.axi.b.bits.resp := memory.io.b_resp

  // AR
  io.axi.ar.ready := memory.io.ar_ready
  memory.io.ar_valid := io.axi.ar.valid
  memory.io.ar_addr := io.axi.ar.bits.addr
  memory.io.ar_len := io.axi.ar.bits.len
  memory.io.ar_size := io.axi.ar.bits.size
  memory.io.ar_burst := io.axi.ar.bits.burst

  // R
  memory.io.r_ready := io.axi.r.ready
  io.axi.r.valid := memory.io.r_valid
  io.axi.r.bits.id := 0.U
  io.axi.r.bits.data := memory.io.r_data
  io.axi.r.bits.resp := memory.io.r_resp
  io.axi.r.bits.last := memory.io.r_last

}
