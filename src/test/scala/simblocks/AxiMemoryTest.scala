package simblocks

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage


class AxiMemoryTest extends AnyFlatSpec with ChiselScalatestTester {
  "DataMemory" should "do something" in {
    test(new AxiMemory(
          STREAM_ADDR_WIDTH = 32,
          MEMORY_DATA_WIDTH = 64,
          MEMORY_ADDR_WIDTH = 10,
          AXI_R_DATA_WIDTH = 32,
          AXI_W_DATA_WIDTH = 32,
          MEMORY_READ_DELAY = 10,
          MEMORY_WRITE_DELAY = 5)
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      dut.io.ctrl_reset.poke(true.B)
      dut.clock.step(1)
      dut.io.ctrl_reset.poke(false.B)

      dut.io.write_enable.poke(true.B)
      dut.io.write_strb.poke(0xF.U)
      dut.io.write_addr.poke(0.U)
      dut.io.write_data.poke(0x04030201L.U)
      dut.clock.step(1)
      dut.io.write_addr.poke(1.U)
      dut.io.write_data.poke(0x08070605L.U)
      dut.clock.step(1)
      dut.io.write_addr.poke(2.U)
      dut.io.write_data.poke(0x0C0B0A09L.U)
      dut.clock.step(1)
      dut.io.write_addr.poke(3.U)
      dut.io.write_data.poke(0x100F0E0DL.U)
      dut.clock.step(1)
      dut.io.write_enable.poke(false.B)
      dut.clock.step(1)

      dut.io.read_enable.poke(true.B)
      dut.io.read_addr.poke(0.U)
      dut.clock.step(1)
      dut.io.read_data.expect(0x04030201L.U)
      dut.io.read_addr.poke(1.U)
      dut.clock.step(1)
      dut.io.read_data.expect(0x08070605L.U)
      dut.io.read_addr.poke(2.U)
      dut.clock.step(1)
      dut.io.read_data.expect(0x0C0B0A09L.U)
      dut.io.read_addr.poke(3.U)
      dut.clock.step(1)
      dut.io.read_data.expect(0x100F0E0DL.U)
      dut.io.read_enable.poke(false.B)
      dut.clock.step(1)
    }
  }

  it should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new AxiMemory(
          STREAM_ADDR_WIDTH = 32,
          MEMORY_DATA_WIDTH = 64,
          MEMORY_ADDR_WIDTH = 10,
          AXI_R_DATA_WIDTH = 32,
          AXI_W_DATA_WIDTH = 32,
          MEMORY_READ_DELAY = 10,
          MEMORY_WRITE_DELAY = 5),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
  
}
