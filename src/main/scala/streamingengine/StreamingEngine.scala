package streamingengine

import chisel3._
import chisel3.util._

/**
  * @param STREAM_NUM_DIMS       - number of dimensions the Stream Engine supports
  * @param STREAM_NUM_MODS       - number of mods each dimension supports
  * @param STREAM_OFFSET_WIDTH   - width of the offset field of a stream
  * @param STREAM_STRIDE_WIDTH   - width of the stride field of a stream
  * @param STREAM_SIZE_WIDTH     - width of the size field of a stream
  * @param STREAM_ID_WIDTH       - width of the Stream ID signal
  * @param LRQ_NUM_TABLES        - number of Load Request Queue tables (associative ways)
  * @param LRQ_NUM_REQUESTS      - number of requests within a same LRQ table
  * @param LLB_NUM_TABLES        - number of Data Memory words the Load Line Buffer can hold simultaneously
  * @param LLB_NUM_BYTES         - number of bytes the Load Line Buffer data registers hold (must match Data Memory word size)
  * @param LMMU_NUM_VECS         - number of vectors each Load FIFO can prefetch
  * @param SMMU_NUM_ADDRESSES    - size of the address queue of each Store FIFO
  * @param ADDRESS_WIDTH         - width of addresses produced by the Streaming Engine
  * @param VEC_WIDTH             - width of each vector
  * @param NUM_SRC_OPERANDS      - number of source operands communicating with the Streaming Engine
  * @param AXI_R_DATA_WIDTH      - width of the AXI RDATA bus
  * @param AXI_W_DATA_WIDTH      - width of the AXI WDATA bus
  * @param MAX_NUM_LOAD_STREAMS  - number of load streams supported by the Streaming Engine simulataneously
  * @param MAX_NUM_STORE_STREAMS - number of store streams supported by the Streaming Engine simulataneously
  */
class StreamingEngine(
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
  val MAX_NUM_STORE_STREAMS: Int)
    extends Module {

  /* Internal parameters, calculated using external ones */
  val VEC_NUM_BYTES = VEC_WIDTH / 8
  val SS_NUM_TABLES = MAX_NUM_LOAD_STREAMS + MAX_NUM_STORE_STREAMS
  val LF_NUM_BYTES = LMMU_NUM_VECS * VEC_NUM_BYTES
  val LF_NUM_TABLES = MAX_NUM_LOAD_STREAMS
  val SMMU_NUM_TABLES = MAX_NUM_STORE_STREAMS

  /* The set of input and output ports of the module */
  val io = IO(new Bundle {

    /* Control channel */
    val ctrl_reset = Input(Bool())

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
    val rd_out_done = Output(Bool())
    val rd_out_store_request_successful = Output(Bool())

    /* AXI Read Address Channel */
    val axi_ar_ready = Input(Bool())
    val axi_ar_addr = Output(UInt(ADDRESS_WIDTH.W))
    val axi_ar_valid = Output(Bool())
    val axi_ar_len = Output(UInt(8.W)) // ARLEN[7:0]
    val axi_ar_size = Output(UInt(3.W)) // ARSIZE[2:0]
    val axi_ar_burst = Output(UInt(1.W)) // ARBURST[1:0]

    /* AXI Read Data Channel */
    val axi_r_data = Input(UInt(AXI_R_DATA_WIDTH.W))
    val axi_r_valid = Input(Bool())
    val axi_r_last = Input(Bool())
    val axi_r_resp = Input(UInt(2.W))
    val axi_r_ready = Output(Bool())

    /* AXI Write Address Channel */
    val axi_aw_ready = Input(Bool())
    val axi_aw_valid = Output(Bool())
    val axi_aw_addr = Output(UInt(ADDRESS_WIDTH.W))
    val axi_aw_len = Output(UInt(8.W)) // ARLEN[7:0]
    val axi_aw_size = Output(UInt(3.W)) // ARSIZE[2:0]
    val axi_aw_burst = Output(UInt(1.W)) // ARBURST[1:0]

    /* AXI Write Data Channel */
    val axi_w_ready = Input(Bool())
    val axi_w_valid = Output(Bool())
    val axi_w_data = Output(UInt(AXI_W_DATA_WIDTH.W))
    val axi_w_strb = Output(UInt((AXI_W_DATA_WIDTH / 8).W))
    val axi_w_last = Output(Bool())

    /* AXI Write Response Channel */
    val axi_b_resp = Input(UInt(2.W))
    val axi_b_valid = Input(Bool())
    val axi_b_ready = Output(Bool())

    /* Performance counters */
    val hpc_ss_desc = Output(UInt(64.W))
    val hpc_lmmu_commit = Output(UInt(64.W))
    val hpc_lmmu_stall = Output(UInt(64.W))
    val hpc_lmmu_stall_lf = Output(UInt(64.W))
    val hpc_lmmu_stall_lrq = Output(UInt(64.W))
    val hpc_lmmu_stall_llb = Output(UInt(64.W))
    val hpc_smmu_commit = Output(UInt(64.W))
    val hpc_smmu_stall = Output(UInt(64.W))
    val hpc_ops_load = Output(UInt(64.W))
    val hpc_ops_store = Output(UInt(64.W))

  })

  val streamingEngineSv = Module(
    new StreamingEngineSv(
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

  streamingEngineSv.io.clk := clock
  streamingEngineSv.io.ctrl_reset := io.ctrl_reset

  streamingEngineSv.io.valid_cpu_sc := io.cpu_in_cfg_valid
  streamingEngineSv.io.sta_cpu_sc := io.cpu_in_cfg_sta
  streamingEngineSv.io.end_cpu_sc := io.cpu_in_cfg_end
  streamingEngineSv.io.type_cpu_sc := io.cpu_in_cfg_type
  streamingEngineSv.io.width_cpu_sc := io.cpu_in_cfg_width
  streamingEngineSv.io.stream_cpu_sc := io.cpu_in_cfg_stream
  streamingEngineSv.io.mod_cpu_sc := io.cpu_in_cfg_mod
  streamingEngineSv.io.vec_cpu_sc := io.cpu_in_cfg_vectorize
  streamingEngineSv.io.dim_offset_cpu_sc := io.cpu_in_cfg_dim_offset
  streamingEngineSv.io.dim_stride_cpu_sc := io.cpu_in_cfg_dim_stride
  streamingEngineSv.io.dim_size_cpu_sc := io.cpu_in_cfg_dim_size
  streamingEngineSv.io.mod_target_cpu_sc := io.cpu_in_cfg_mod_target
  streamingEngineSv.io.mod_behaviour_cpu_sc := io.cpu_in_cfg_mod_behaviour
  streamingEngineSv.io.mod_displacement_cpu_sc := io.cpu_in_cfg_mod_displacement
  streamingEngineSv.io.mod_size_cpu_sc := io.cpu_in_cfg_mod_size

  io.cpu_out_cfg_ready := streamingEngineSv.io.ready_sc_cpu

  streamingEngineSv.io.rd_valid_cpu_smmu := io.rd_in_valid
  streamingEngineSv.io.rd_streamid_cpu_smmu := io.rd_in_streamid
  streamingEngineSv.io.rd_vecdata_cpu_smmu := io.rd_in_vecdata
  streamingEngineSv.io.rd_predicate_cpu_smmu := io.rd_in_predicate

  io.rd_out_ready := streamingEngineSv.io.rd_ready_smmu_cpu
  io.rd_out_width := streamingEngineSv.io.rd_width_smmu_cpu
  io.rd_out_done := streamingEngineSv.io.rd_done_smmu_cpu
  io.rd_out_store_request_successful := streamingEngineSv.io.rd_store_request_successful_smmu_cpu

  streamingEngineSv.io.rs_ready_cpu_lmmu := io.rs_in_ready.asUInt
  streamingEngineSv.io.rs_streamid_cpu_lmmu := io.rs_in_streamid.asUInt

  io.rs_out_valid := streamingEngineSv.io.rs_valid_lmmu_cpu.asTypeOf(io.rs_out_valid)
  io.rs_out_done := streamingEngineSv.io.rs_done_lmmu_cpu.asTypeOf(io.rs_out_done)
  io.rs_out_vecdata := streamingEngineSv.io.rs_vecdata_lmmu_cpu.asTypeOf(io.rs_out_vecdata)
  io.rs_out_predicate := streamingEngineSv.io.rs_predicate_lmmu_cpu.asTypeOf(io.rs_out_predicate)
  io.rs_out_completed := streamingEngineSv.io.rs_completed_lmmu_cpu.asTypeOf(io.rs_out_completed)

  streamingEngineSv.io.aw_ready_axi_mmu := io.axi_aw_ready
  io.axi_aw_valid := streamingEngineSv.io.aw_valid_mmu_axi
  io.axi_aw_addr := streamingEngineSv.io.aw_addr_mmu_axi
  io.axi_aw_len := streamingEngineSv.io.aw_len_mmu_axi
  io.axi_aw_size := streamingEngineSv.io.aw_size_mmu_axi
  io.axi_aw_burst := streamingEngineSv.io.aw_burst_mmu_axi

  streamingEngineSv.io.w_ready_axi_mmu := io.axi_w_ready
  io.axi_w_valid := streamingEngineSv.io.w_valid_mmu_axi
  io.axi_w_data := streamingEngineSv.io.w_data_mmu_axi
  io.axi_w_strb := streamingEngineSv.io.w_strb_mmu_axi
  io.axi_w_last := streamingEngineSv.io.w_last_mmu_axi

  streamingEngineSv.io.b_resp_axi_mmu := io.axi_b_resp
  streamingEngineSv.io.b_valid_axi_mmu := io.axi_b_valid
  io.axi_b_ready := streamingEngineSv.io.b_ready_mmu_axi

  streamingEngineSv.io.ar_ready_axi_mmu := io.axi_ar_ready
  io.axi_ar_addr := streamingEngineSv.io.ar_addr_mmu_axi
  io.axi_ar_valid := streamingEngineSv.io.ar_valid_mmu_axi
  io.axi_ar_len := streamingEngineSv.io.ar_len_mmu_axi
  io.axi_ar_size := streamingEngineSv.io.ar_size_mmu_axi
  io.axi_ar_burst := streamingEngineSv.io.ar_burst_mmu_axi

  streamingEngineSv.io.r_data_axi_mmu := io.axi_r_data
  streamingEngineSv.io.r_valid_axi_mmu := io.axi_r_valid
  streamingEngineSv.io.r_last_axi_mmu := io.axi_r_last
  streamingEngineSv.io.r_resp_axi_mmu := io.axi_r_resp
  io.axi_r_ready := streamingEngineSv.io.r_ready_mmu_axi

  io.hpc_ss_desc := streamingEngineSv.io.hpc_ss_desc
  io.hpc_lmmu_commit := streamingEngineSv.io.hpc_lmmu_commit
  io.hpc_lmmu_stall := streamingEngineSv.io.hpc_lmmu_stall
  io.hpc_lmmu_stall_lf := streamingEngineSv.io.hpc_lmmu_stall_lf
  io.hpc_lmmu_stall_lrq := streamingEngineSv.io.hpc_lmmu_stall_lrq
  io.hpc_lmmu_stall_llb := streamingEngineSv.io.hpc_lmmu_stall_llb
  io.hpc_smmu_commit := streamingEngineSv.io.hpc_smmu_commit
  io.hpc_smmu_stall := streamingEngineSv.io.hpc_smmu_stall
  io.hpc_ops_load := streamingEngineSv.io.hpc_ops_load
  io.hpc_ops_store := streamingEngineSv.io.hpc_ops_store
}
