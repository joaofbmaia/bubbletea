package streamingengine

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import circt.stage.ChiselStage

class StreamingEngineTest extends AnyFlatSpec with ChiselScalatestTester {
  val STREAM_NUM_DIMS = 4
  val STREAM_NUM_MODS = 2
  val STREAM_OFFSET_WIDTH = 32
  val STREAM_STRIDE_WIDTH = 32
  val STREAM_SIZE_WIDTH = 32
  val STREAM_ID_WIDTH = 5
  val LRQ_NUM_TABLES = 4
  val LRQ_NUM_REQUESTS = 16
  val LLB_NUM_TABLES = 8
  val LLB_NUM_BYTES = 8
  val LMMU_NUM_VECS = 4
  val SMMU_NUM_ADDRESSES = 64
  val ADDRESS_WIDTH = 32
  val VEC_WIDTH = 64
  val NUM_SRC_OPERANDS = 2
  val AXI_R_DATA_WIDTH = 32
  val AXI_W_DATA_WIDTH = 32
  val MAX_NUM_LOAD_STREAMS = 4
  val MAX_NUM_STORE_STREAMS = 2
  val MEMORY_DATA_WIDTH = LLB_NUM_BYTES * 8
  val MEMORY_ADDR_WIDTH = 10
  val MEMORY_READ_DELAY = 2
  val MEMORY_WRITE_DELAY = 2

  "StreamingEngine" should "do something" in {
    test(
      new StreamingEngine(
        STREAM_NUM_DIMS,
        STREAM_NUM_MODS,
        STREAM_OFFSET_WIDTH,
        STREAM_STRIDE_WIDTH,
        STREAM_SIZE_WIDTH,
        STREAM_ID_WIDTH,
        LRQ_NUM_TABLES,
        LRQ_NUM_REQUESTS,
        LLB_NUM_TABLES,
        LLB_NUM_BYTES,
        LMMU_NUM_VECS,
        SMMU_NUM_ADDRESSES,
        ADDRESS_WIDTH,
        VEC_WIDTH,
        NUM_SRC_OPERANDS,
        AXI_R_DATA_WIDTH,
        AXI_W_DATA_WIDTH,
        MAX_NUM_LOAD_STREAMS,
        MAX_NUM_STORE_STREAMS
      )
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      dut.io.ctrl_reset.poke(true.B)
      dut.clock.step(1)
      dut.io.ctrl_reset.poke(false.B)

      dut.io.axi_ar_ready.poke(true.B)
      //dut.io.axi_r_valid.poke(true.B)
      dut.io.rs_in_ready(1).poke(true.B)

      dut.io.cpu_in_cfg_valid.poke(true.B)
      dut.io.cpu_in_cfg_vectorize.poke(false.B)
      dut.io.cpu_in_cfg_sta.poke(true.B)

      dut.io.cpu_in_cfg_type.poke(true.B)
      dut.io.cpu_in_cfg_width.poke(1.U)
      dut.io.cpu_in_cfg_stream.poke(1.U)
      dut.io.cpu_in_cfg_mod.poke(false.B)
      dut.io.cpu_in_cfg_dim_offset.poke(0.U)
      dut.io.cpu_in_cfg_dim_stride.poke(1.U)
      dut.io.cpu_in_cfg_dim_size.poke(1.U)
      dut.io.cpu_in_cfg_end.poke(false.B)
      dut.clock.step(1)
      dut.io.cpu_in_cfg_vectorize.poke(true.B)
      dut.io.cpu_in_cfg_sta.poke(false.B)
      dut.clock.step(1)

      dut.io.cpu_in_cfg_vectorize.poke(false.B)
      dut.io.cpu_in_cfg_end.poke(true.B)
      dut.io.cpu_in_cfg_dim_offset.poke(0.U)
      dut.io.cpu_in_cfg_dim_stride.poke(1.U)
      dut.io.cpu_in_cfg_dim_size.poke(8.U)

      dut.clock.step(1)
      dut.io.cpu_in_cfg_valid.poke(false.B)

      dut.io.rs_in_streamid(1).poke(1.U)

      while (dut.io.axi_ar_valid.peek().litToBoolean == false) {
        dut.clock.step(1)
      }
      dut.clock.step(1)
      dut.io.axi_ar_ready.poke(false.B)
      dut.clock.step(1)

      dut.io.axi_r_valid.poke(true.B)
      dut.io.axi_r_data.poke(0x0807060504030201L.U)
      dut.io.axi_r_last.poke(true.B)
      dut.clock.step(1)
      dut.io.axi_r_valid.poke(false.B)
      dut.io.axi_r_last.poke(false.B)
      dut.io.axi_ar_ready.poke(true.B)

      while (dut.io.axi_ar_valid.peek().litToBoolean == false) {
        dut.clock.step(1)
      }
      dut.clock.step(1)
      dut.io.axi_ar_ready.poke(false.B)
      dut.clock.step(1)

      dut.io.axi_r_valid.poke(true.B)
      dut.io.axi_r_data.poke(0x100f0e0d0c0b0a09L.U)
      dut.io.axi_r_last.poke(true.B)
      dut.clock.step(1)
      dut.io.axi_r_valid.poke(false.B)
      dut.io.axi_r_last.poke(false.B)

      dut.clock.step(30)

    }
  }

  it should "do linear pattern" in {
    test(
      new StreamingEngine(
        STREAM_NUM_DIMS,
        STREAM_NUM_MODS,
        STREAM_OFFSET_WIDTH,
        STREAM_STRIDE_WIDTH,
        STREAM_SIZE_WIDTH,
        STREAM_ID_WIDTH,
        LRQ_NUM_TABLES,
        LRQ_NUM_REQUESTS,
        LLB_NUM_TABLES,
        LLB_NUM_BYTES,
        LMMU_NUM_VECS,
        SMMU_NUM_ADDRESSES,
        ADDRESS_WIDTH,
        VEC_WIDTH,
        NUM_SRC_OPERANDS,
        AXI_R_DATA_WIDTH,
        AXI_W_DATA_WIDTH,
        MAX_NUM_LOAD_STREAMS,
        MAX_NUM_STORE_STREAMS
      )
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      dut.io.ctrl_reset.poke(true.B)
      dut.clock.step(1)
      dut.io.ctrl_reset.poke(false.B)

      dut.io.axi_ar_ready.poke(true.B)
      //dut.io.axi_r_valid.poke(true.B)
      dut.io.rs_in_ready(1).poke(true.B)

      dut.io.cpu_in_cfg_vectorize.poke(false.B)
      dut.io.cpu_in_cfg_sta.poke(true.B)
      dut.io.cpu_in_cfg_end.poke(true.B)
      dut.io.cpu_in_cfg_type.poke(true.B)
      dut.io.cpu_in_cfg_width.poke(1.U)
      dut.io.cpu_in_cfg_stream.poke(1.U)
      dut.io.cpu_in_cfg_mod.poke(false.B)
      dut.io.cpu_in_cfg_dim_offset.poke(0.U)
      dut.io.cpu_in_cfg_dim_stride.poke(1.U)
      dut.io.cpu_in_cfg_dim_size.poke(8.U)
      dut.io.cpu_in_cfg_valid.poke(true.B)
      dut.clock.step(1)
      dut.io.cpu_in_cfg_valid.poke(false.B)

      dut.io.rs_in_streamid(1).poke(1.U)

      while (dut.io.axi_ar_valid.peek().litToBoolean == false) {
        dut.clock.step(1)
      }
      dut.clock.step(1)
      dut.io.axi_ar_ready.poke(false.B)
      dut.clock.step(1)

      dut.io.axi_r_valid.poke(true.B)
      dut.io.axi_r_data.poke(0x0807060504030201L.U)
      dut.io.axi_r_last.poke(true.B)
      dut.clock.step(1)
      dut.io.axi_r_valid.poke(false.B)
      dut.io.axi_r_last.poke(false.B)
      dut.io.axi_ar_ready.poke(true.B)

      while (dut.io.axi_ar_valid.peek().litToBoolean == false) {
        dut.clock.step(1)
      }
      dut.clock.step(1)
      dut.io.axi_ar_ready.poke(false.B)
      dut.clock.step(1)

      dut.io.axi_r_valid.poke(true.B)
      dut.io.axi_r_data.poke(0x100f0e0d0c0b0a09L.U)
      dut.io.axi_r_last.poke(true.B)
      dut.clock.step(1)
      dut.io.axi_r_valid.poke(false.B)
      dut.io.axi_r_last.poke(false.B)

      dut.clock.step(30)

    }
  }

  it should "do linear pattern with simulated AXI memory" in {
    test(
      new StreamingEngineWithMemory(
        STREAM_NUM_DIMS,
        STREAM_NUM_MODS,
        STREAM_OFFSET_WIDTH,
        STREAM_STRIDE_WIDTH,
        STREAM_SIZE_WIDTH,
        STREAM_ID_WIDTH,
        LRQ_NUM_TABLES,
        LRQ_NUM_REQUESTS,
        LLB_NUM_TABLES,
        LLB_NUM_BYTES,
        LMMU_NUM_VECS,
        SMMU_NUM_ADDRESSES,
        ADDRESS_WIDTH,
        VEC_WIDTH,
        NUM_SRC_OPERANDS,
        AXI_R_DATA_WIDTH,
        AXI_W_DATA_WIDTH,
        MAX_NUM_LOAD_STREAMS,
        MAX_NUM_STORE_STREAMS,
        MEMORY_DATA_WIDTH,
        MEMORY_ADDR_WIDTH,
        MEMORY_READ_DELAY,
        MEMORY_WRITE_DELAY
      )
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      // fill first 256 bytes of memory with linear pattern
      dut.io.mem_write_enable.poke(true.B)
      dut.io.mem_write_strb.poke(0xffL.U)
      for (i <- 0 until 256 by MEMORY_DATA_WIDTH / 8) {
        // value with every byte equal to the adress
        val x = BigInt((i until i + MEMORY_DATA_WIDTH / 8).map(x => f"$x%02x").reverse.reduce(_ ++ _), 16)
        println((i / (MEMORY_DATA_WIDTH / 8)).U)
        dut.io.mem_write_addr.poke((i / (MEMORY_DATA_WIDTH / 8)).U)
        println(x.toString(16))
        dut.io.mem_write_data.poke(x)
        dut.clock.step(1)
      }
      dut.io.mem_write_enable.poke(false.B)
      dut.clock.step(1)
      dut.io.ctrl_reset.poke(true.B)
      dut.clock.step(1)
      dut.io.ctrl_reset.poke(false.B)

      // set output register ready (using register 1)
      dut.io.rs_in_ready(1).poke(true.B)
      // using streamid 1 and output on register 1
      dut.io.rs_in_streamid(1).poke(1.U)

      // set up configuration
      dut.io.cpu_in_cfg_vectorize.poke(false.B)
      dut.io.cpu_in_cfg_sta.poke(true.B)
      dut.io.cpu_in_cfg_end.poke(true.B)
      dut.io.cpu_in_cfg_type.poke(true.B)
      dut.io.cpu_in_cfg_width.poke(2.U)
      dut.io.cpu_in_cfg_stream.poke(1.U)
      dut.io.cpu_in_cfg_mod.poke(false.B)
      dut.io.cpu_in_cfg_dim_offset.poke(0.U)
      dut.io.cpu_in_cfg_dim_stride.poke(1.U)
      dut.io.cpu_in_cfg_dim_size.poke(64.U)
      dut.io.cpu_in_cfg_valid.poke(true.B)
      dut.clock.step(1)
      dut.io.cpu_in_cfg_valid.poke(false.B)

      dut.clock.step(500)
    }
  }

  it should "do linear pattern, with a specific vec size with simulated AXI memory" in {
    val vecSize = 2
    test(
      new StreamingEngineWithMemory(
        STREAM_NUM_DIMS,
        STREAM_NUM_MODS,
        STREAM_OFFSET_WIDTH,
        STREAM_STRIDE_WIDTH,
        STREAM_SIZE_WIDTH,
        STREAM_ID_WIDTH,
        LRQ_NUM_TABLES,
        LRQ_NUM_REQUESTS,
        LLB_NUM_TABLES,
        LLB_NUM_BYTES,
        LMMU_NUM_VECS,
        SMMU_NUM_ADDRESSES,
        ADDRESS_WIDTH,
        VEC_WIDTH,
        NUM_SRC_OPERANDS,
        AXI_R_DATA_WIDTH,
        AXI_W_DATA_WIDTH,
        MAX_NUM_LOAD_STREAMS,
        MAX_NUM_STORE_STREAMS,
        MEMORY_DATA_WIDTH,
        MEMORY_ADDR_WIDTH,
        MEMORY_READ_DELAY,
        MEMORY_WRITE_DELAY
      )
    ).withAnnotations(Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)) { dut =>
      // fill first 256 bytes of memory with linear pattern
      dut.io.mem_write_enable.poke(true.B)
      dut.io.mem_write_strb.poke(0xffL.U)
      for (i <- 0 until 256 by MEMORY_DATA_WIDTH / 8) {
        // value with every byte equal to the adress
        val x = BigInt((i until i + MEMORY_DATA_WIDTH / 8).map(x => f"$x%02x").reverse.reduce(_ ++ _), 16)
        println((i / (MEMORY_DATA_WIDTH / 8)).U)
        dut.io.mem_write_addr.poke((i / (MEMORY_DATA_WIDTH / 8)).U)
        println(x.toString(16))
        dut.io.mem_write_data.poke(x)
        dut.clock.step(1)
      }
      dut.io.mem_write_enable.poke(false.B)
      dut.clock.step(1)
      dut.io.ctrl_reset.poke(true.B)
      dut.clock.step(1)
      dut.io.ctrl_reset.poke(false.B)

      // set output register ready (using register 1)
      dut.io.rs_in_ready(1).poke(true.B)
      // using streamid 1 and output on register 1
      dut.io.rs_in_streamid(1).poke(1.U)

      // set up configuration
      dut.io.cpu_in_cfg_valid.poke(true.B)
      dut.io.cpu_in_cfg_vectorize.poke(false.B)
      dut.io.cpu_in_cfg_sta.poke(true.B)
      dut.io.cpu_in_cfg_end.poke(false.B)
      dut.io.cpu_in_cfg_type.poke(true.B)
      dut.io.cpu_in_cfg_width.poke(1.U)
      dut.io.cpu_in_cfg_stream.poke(1.U)
      dut.io.cpu_in_cfg_mod.poke(false.B)
      dut.io.cpu_in_cfg_dim_offset.poke(0.U)
      dut.io.cpu_in_cfg_dim_stride.poke(1.U)
      dut.io.cpu_in_cfg_dim_size.poke(vecSize.U)
      dut.clock.step(1)
      dut.io.cpu_in_cfg_vectorize.poke(true.B)
      dut.io.cpu_in_cfg_sta.poke(false.B)
      dut.clock.step(1)
      dut.io.cpu_in_cfg_vectorize.poke(false.B)
      dut.io.cpu_in_cfg_end.poke(true.B)
      dut.io.cpu_in_cfg_dim_offset.poke(0.U)
      dut.io.cpu_in_cfg_dim_stride.poke(1.U)
      dut.io.cpu_in_cfg_dim_size.poke(64.U)
      dut.clock.step(1)
      dut.io.cpu_in_cfg_valid.poke(false.B)

      dut.clock.step(500)
    }
  }


  it should "emit Verilog" in {
    ChiselStage.emitSystemVerilogFile(
      new StreamingEngine(
        STREAM_NUM_DIMS,
        STREAM_NUM_MODS,
        STREAM_OFFSET_WIDTH,
        STREAM_STRIDE_WIDTH,
        STREAM_SIZE_WIDTH,
        STREAM_ID_WIDTH,
        LRQ_NUM_TABLES,
        LRQ_NUM_REQUESTS,
        LLB_NUM_TABLES,
        LLB_NUM_BYTES,
        LMMU_NUM_VECS,
        SMMU_NUM_ADDRESSES,
        ADDRESS_WIDTH,
        VEC_WIDTH,
        NUM_SRC_OPERANDS,
        AXI_R_DATA_WIDTH,
        AXI_W_DATA_WIDTH,
        MAX_NUM_LOAD_STREAMS,
        MAX_NUM_STORE_STREAMS
      ),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
    )
  }
}
