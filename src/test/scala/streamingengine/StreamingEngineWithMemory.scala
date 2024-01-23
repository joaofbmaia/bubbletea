package streamingengine

import chisel3._
import simblocks.AxiMemory

class StreamingEngineWithMemory(
  val STREAM_NUM_DIMS:       Int,
  val STREAM_NUM_MODS:       Int,
  val STREAM_OFFSET_WIDTH:   Int,
  val STREAM_STRIDE_WIDTH:   Int,
  val STREAM_SIZE_WIDTH:     Int,
  val STREAM_ID_WIDTH:       Int,
  val LRQ_NUM_TABLES:        Int,
  val LRQ_NUM_REQUESTS:      Int,
  val LLB_NUM_TABLES:        Int,
  val LLB_NUM_BYTES:         Int,
  val LMMU_NUM_VECS:         Int,
  val SMMU_NUM_ADDRESSES:    Int,
  val ADDRESS_WIDTH:         Int,
  val VEC_WIDTH:             Int,
  val NUM_SRC_OPERANDS:      Int,
  val AXI_R_DATA_WIDTH:      Int,
  val AXI_W_DATA_WIDTH:      Int,
  val MAX_NUM_LOAD_STREAMS:  Int,
  val MAX_NUM_STORE_STREAMS: Int,
  val MEMORY_DATA_WIDTH:     Int,
  val MEMORY_ADDR_WIDTH:     Int,
  val MEMORY_READ_DELAY:     Int,
  val MEMORY_WRITE_DELAY:    Int)
    extends Module {

  /* Internal parameters, calculated using external ones */
  val VEC_NUM_BYTES = VEC_WIDTH / 8
  val SS_NUM_TABLES = MAX_NUM_LOAD_STREAMS + MAX_NUM_STORE_STREAMS
  val LF_NUM_BYTES = LMMU_NUM_VECS * VEC_NUM_BYTES
  val LF_NUM_TABLES = MAX_NUM_LOAD_STREAMS
  val SMMU_NUM_TABLES = MAX_NUM_STORE_STREAMS

  val MEMORY_NUM_BYTES    = MEMORY_DATA_WIDTH / 8

  val io = IO(new Bundle {
    val ctrl_reset = Input(Bool())

    /* External read channel */
    val mem_read_enable = Input(Bool())
    val mem_read_addr = Input(UInt(MEMORY_ADDR_WIDTH.W))
    val mem_read_data = Output(UInt(MEMORY_DATA_WIDTH.W))

    /* External write channel */
    val mem_write_enable = Input(Bool())
    val mem_write_addr = Input(UInt(MEMORY_ADDR_WIDTH.W))
    val mem_write_data = Input(UInt(MEMORY_DATA_WIDTH.W))
    val mem_write_strb = Input(UInt(MEMORY_NUM_BYTES.W))

    /* Processing unit - configuration channel */
    val cpu_in_cfg_valid = Input(Bool())
    val cpu_in_cfg_sta = Input(Bool())
    val cpu_in_cfg_end = Input(Bool())
    val cpu_in_cfg_type = Input(Bool())
    val cpu_in_cfg_width = Input(UInt(2.W))
    val cpu_in_cfg_stream = Input(UInt(STREAM_ID_WIDTH.W))
    val cpu_in_cfg_mod = Input(Bool())
    val cpu_in_cfg_vectorize = Input(Bool())
    val cpu_in_cfg_mod_target = Input(UInt(2.W))
    val cpu_in_cfg_mod_behaviour = Input(Bool())
    val cpu_in_cfg_mod_displacement = Input(UInt(STREAM_STRIDE_WIDTH.W))
    val cpu_in_cfg_mod_size = Input(UInt(STREAM_SIZE_WIDTH.W))
    val cpu_in_cfg_dim_offset = Input(UInt(STREAM_OFFSET_WIDTH.W))
    val cpu_in_cfg_dim_stride = Input(UInt(STREAM_STRIDE_WIDTH.W))
    val cpu_in_cfg_dim_size = Input(UInt(STREAM_SIZE_WIDTH.W))
    val cpu_out_cfg_ready = Output(Bool())

    /* Source operands data channel */
    val rs_in_streamid = Input(Vec(NUM_SRC_OPERANDS, UInt(STREAM_ID_WIDTH.W)))
    val rs_in_ready = Input(Vec(NUM_SRC_OPERANDS, Bool()))
    val rs_out_valid = Output(Vec(NUM_SRC_OPERANDS, Bool()))
    val rs_out_done = Output(Vec(NUM_SRC_OPERANDS, Bool()))
    val rs_out_vecdata = Output(Vec(NUM_SRC_OPERANDS, UInt(VEC_WIDTH.W)))
    val rs_out_predicate = Output(Vec(NUM_SRC_OPERANDS, UInt(VEC_NUM_BYTES.W)))
    val rs_out_completed = Output(Vec(NUM_SRC_OPERANDS, UInt(STREAM_NUM_DIMS.W)))

    /* Destination operands data channel */
    val rd_in_valid = Input(Bool())
    val rd_in_streamid = Input(UInt(STREAM_ID_WIDTH.W))
    val rd_in_vecdata = Input(UInt(VEC_WIDTH.W))
    val rd_in_predicate = Input(UInt(VEC_NUM_BYTES.W))
    val rd_out_ready = Output(Bool())
    val rd_out_width = Output(UInt(2.W))
  })

  val streamingEngine = Module(
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
  )

  val axiMemory = Module(
    new AxiMemory(
      STREAM_ADDR_WIDTH = ADDRESS_WIDTH,
      MEMORY_DATA_WIDTH = MEMORY_DATA_WIDTH,
      MEMORY_ADDR_WIDTH = MEMORY_ADDR_WIDTH,
      AXI_R_DATA_WIDTH = AXI_R_DATA_WIDTH,
      AXI_W_DATA_WIDTH = AXI_W_DATA_WIDTH,
      MEMORY_READ_DELAY = MEMORY_READ_DELAY,
      MEMORY_WRITE_DELAY = MEMORY_WRITE_DELAY
    )
  )

  // Connect the reset signal
  streamingEngine.io.ctrl_reset := io.ctrl_reset
  axiMemory.io.ctrl_reset := io.ctrl_reset

// Connect IO configuration channels
  streamingEngine.io.cpu_in_cfg_valid := io.cpu_in_cfg_valid
  streamingEngine.io.cpu_in_cfg_sta := io.cpu_in_cfg_sta
  streamingEngine.io.cpu_in_cfg_end := io.cpu_in_cfg_end
  streamingEngine.io.cpu_in_cfg_type := io.cpu_in_cfg_type
  streamingEngine.io.cpu_in_cfg_width := io.cpu_in_cfg_width
  streamingEngine.io.cpu_in_cfg_stream := io.cpu_in_cfg_stream
  streamingEngine.io.cpu_in_cfg_mod := io.cpu_in_cfg_mod
  streamingEngine.io.cpu_in_cfg_vectorize := io.cpu_in_cfg_vectorize
  streamingEngine.io.cpu_in_cfg_mod_target := io.cpu_in_cfg_mod_target
  streamingEngine.io.cpu_in_cfg_mod_behaviour := io.cpu_in_cfg_mod_behaviour
  streamingEngine.io.cpu_in_cfg_mod_displacement := io.cpu_in_cfg_mod_displacement
  streamingEngine.io.cpu_in_cfg_mod_size := io.cpu_in_cfg_mod_size
  streamingEngine.io.cpu_in_cfg_dim_offset := io.cpu_in_cfg_dim_offset
  streamingEngine.io.cpu_in_cfg_dim_stride := io.cpu_in_cfg_dim_stride
  streamingEngine.io.cpu_in_cfg_dim_size := io.cpu_in_cfg_dim_size
  io.cpu_out_cfg_ready := streamingEngine.io.cpu_out_cfg_ready

// Connect operand channels
  streamingEngine.io.rs_in_streamid := io.rs_in_streamid
  streamingEngine.io.rs_in_ready := io.rs_in_ready
  io.rs_out_valid := streamingEngine.io.rs_out_valid
  io.rs_out_done := streamingEngine.io.rs_out_done
  io.rs_out_vecdata := streamingEngine.io.rs_out_vecdata
  io.rs_out_predicate := streamingEngine.io.rs_out_predicate
  io.rs_out_completed := streamingEngine.io.rs_out_completed

  streamingEngine.io.rd_in_valid := io.rd_in_valid
  streamingEngine.io.rd_in_streamid := io.rd_in_streamid
  streamingEngine.io.rd_in_vecdata := io.rd_in_vecdata
  streamingEngine.io.rd_in_predicate := io.rd_in_predicate
  io.rd_out_ready := streamingEngine.io.rd_out_ready
  io.rd_out_width := streamingEngine.io.rd_out_width

  // Connect external memory channels
  axiMemory.io.read_enable := io.mem_read_enable
  axiMemory.io.read_addr := io.mem_read_addr
  io.mem_read_data := axiMemory.io.read_data

  axiMemory.io.write_enable := io.mem_write_enable
  axiMemory.io.write_addr := io.mem_write_addr
  axiMemory.io.write_data := io.mem_write_data
  axiMemory.io.write_strb := io.mem_write_strb

  // Connect the modules
  // Connect AXI Read Address channel
  streamingEngine.io.axi_ar_ready := axiMemory.io.ar_ready
  axiMemory.io.ar_addr := streamingEngine.io.axi_ar_addr
  axiMemory.io.ar_valid := streamingEngine.io.axi_ar_valid
  axiMemory.io.ar_len := streamingEngine.io.axi_ar_len
  axiMemory.io.ar_size := streamingEngine.io.axi_ar_size
  axiMemory.io.ar_burst := streamingEngine.io.axi_ar_burst

// Connect AXI Read Data channel
  streamingEngine.io.axi_r_data := axiMemory.io.r_data
  streamingEngine.io.axi_r_valid := axiMemory.io.r_valid
  streamingEngine.io.axi_r_last := axiMemory.io.r_last
  streamingEngine.io.axi_r_resp := axiMemory.io.r_resp
  axiMemory.io.r_ready := streamingEngine.io.axi_r_ready

// Connect AXI Write Address channel
  streamingEngine.io.axi_aw_ready := axiMemory.io.aw_ready
  axiMemory.io.aw_addr := streamingEngine.io.axi_aw_addr
  axiMemory.io.aw_valid := streamingEngine.io.axi_aw_valid
  axiMemory.io.aw_len := streamingEngine.io.axi_aw_len
  axiMemory.io.aw_size := streamingEngine.io.axi_aw_size
  axiMemory.io.aw_burst := streamingEngine.io.axi_aw_burst

// Connect AXI Write Data channel
  streamingEngine.io.axi_w_ready := axiMemory.io.w_ready
  axiMemory.io.w_data := streamingEngine.io.axi_w_data
  axiMemory.io.w_strb := streamingEngine.io.axi_w_strb
  axiMemory.io.w_valid := streamingEngine.io.axi_w_valid
  axiMemory.io.w_last := streamingEngine.io.axi_w_last

// Connect AXI Write Response channel
  streamingEngine.io.axi_b_resp := axiMemory.io.b_resp
  streamingEngine.io.axi_b_valid := axiMemory.io.b_valid
  axiMemory.io.b_ready := streamingEngine.io.axi_b_ready
}
