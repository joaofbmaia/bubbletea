package streamingenginesv

import chisel3._
import chisel3.util.{HasBlackBoxResource, HasBlackBoxInline}
import freechips.rocketchip.util.ElaborationArtefacts
import java.io.FileWriter

class StreamingEngineSv(
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
extends BlackBox with HasBlackBoxResource with HasBlackBoxInline {
  /* Internal parameters, calculated using external ones */
  val VEC_NUM_BYTES   = VEC_WIDTH / 8
  val SS_NUM_TABLES   = MAX_NUM_LOAD_STREAMS + MAX_NUM_STORE_STREAMS
  val LF_NUM_BYTES    = LMMU_NUM_VECS * VEC_NUM_BYTES
  val LF_NUM_TABLES   = MAX_NUM_LOAD_STREAMS
  val SMMU_NUM_TABLES = MAX_NUM_STORE_STREAMS
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val ctrl_reset = Input(Bool())
    // CPU <-> Stream Configurator
    val valid_cpu_sc = Input(Bool())
    val sta_cpu_sc = Input(Bool())
    val end_cpu_sc = Input(Bool())
    val type_cpu_sc = Input(Bool())
    val width_cpu_sc = Input(UInt(2.W))
    val stream_cpu_sc = Input(UInt(STREAM_ID_WIDTH.W))
    val mod_cpu_sc = Input(Bool())
    val vec_cpu_sc = Input(Bool())
    val dim_offset_cpu_sc = Input(UInt(STREAM_OFFSET_WIDTH.W))
    val dim_stride_cpu_sc = Input(UInt(STREAM_STRIDE_WIDTH.W))
    val dim_size_cpu_sc = Input(UInt(STREAM_SIZE_WIDTH.W))
    val mod_target_cpu_sc = Input(UInt(2.W))
    val mod_behaviour_cpu_sc = Input(Bool())
    val mod_displacement_cpu_sc = Input(UInt(STREAM_STRIDE_WIDTH.W))
    val mod_size_cpu_sc = Input(UInt(STREAM_SIZE_WIDTH.W))
    val ready_sc_cpu = Output(Bool())
    // CPU <-> SMMU
    val rd_valid_cpu_smmu = Input(Bool())
    val rd_streamid_cpu_smmu = Input(UInt(STREAM_ID_WIDTH.W))
    val rd_vecdata_cpu_smmu = Input(UInt(VEC_WIDTH.W))
    val rd_predicate_cpu_smmu = Input(UInt(VEC_NUM_BYTES.W))
    val rd_ready_smmu_cpu = Output(Bool())
    val rd_width_smmu_cpu = Output(UInt(2.W))
    val rd_done_smmu_cpu = Output(Bool())
    val rd_store_request_successful_smmu_cpu = Output(Bool())
    // CPU <-> LMMU
    val rs_ready_cpu_lmmu = Input(UInt(NUM_SRC_OPERANDS.W))
    val rs_streamid_cpu_lmmu = Input(UInt((NUM_SRC_OPERANDS * STREAM_ID_WIDTH).W))
    val rs_valid_lmmu_cpu = Output(UInt(NUM_SRC_OPERANDS.W))
    val rs_done_lmmu_cpu = Output(UInt(NUM_SRC_OPERANDS.W))
    val rs_vecdata_lmmu_cpu = Output(UInt((NUM_SRC_OPERANDS * VEC_WIDTH).W))
    val rs_predicate_lmmu_cpu = Output(UInt((NUM_SRC_OPERANDS * VEC_NUM_BYTES).W))
    val rs_completed_lmmu_cpu = Output(UInt((NUM_SRC_OPERANDS * STREAM_NUM_DIMS).W))
    // AXI <-> MMU
    val aw_ready_axi_mmu = Input(Bool())
    val aw_valid_mmu_axi = Output(Bool())
    val aw_addr_mmu_axi = Output(UInt(ADDRESS_WIDTH.W))
    val aw_len_mmu_axi = Output(UInt(8.W))
    val aw_size_mmu_axi = Output(UInt(3.W))
    val aw_burst_mmu_axi = Output(UInt(2.W))
    val w_ready_axi_mmu = Input(Bool())
    val w_valid_mmu_axi = Output(Bool())
    val w_data_mmu_axi = Output(UInt(AXI_W_DATA_WIDTH.W))
    val w_strb_mmu_axi = Output(UInt((AXI_W_DATA_WIDTH / 8).W))
    val w_last_mmu_axi = Output(Bool())
    val b_valid_axi_mmu = Input(Bool())
    val b_resp_axi_mmu = Input(UInt(2.W))
    val b_ready_mmu_axi = Output(Bool())
    val ar_ready_axi_mmu = Input(Bool())
    val ar_addr_mmu_axi = Output(UInt(ADDRESS_WIDTH.W))
    val ar_valid_mmu_axi = Output(Bool())
    val ar_len_mmu_axi = Output(UInt(8.W))
    val ar_size_mmu_axi = Output(UInt(3.W))
    val ar_burst_mmu_axi = Output(UInt(2.W))
    val r_data_axi_mmu = Input(UInt(AXI_R_DATA_WIDTH.W))
    val r_valid_axi_mmu = Input(Bool())
    val r_last_axi_mmu = Input(Bool())
    val r_resp_axi_mmu = Input(UInt(2.W))
    val r_ready_mmu_axi = Output(Bool())
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

  // Generate the external parameters package file
  val externalParametersPackageContent = new StringBuilder
  externalParametersPackageContent.append(s"package auto_external_params_pkg;\n")
  
  val externalParams = Seq(
    ("STREAM_NUM_DIMS", STREAM_NUM_DIMS),
    ("STREAM_NUM_MODS", STREAM_NUM_MODS),
    ("STREAM_OFFSET_WIDTH", STREAM_OFFSET_WIDTH),
    ("STREAM_STRIDE_WIDTH", STREAM_STRIDE_WIDTH),
    ("STREAM_SIZE_WIDTH", STREAM_SIZE_WIDTH),
    ("STREAM_ID_WIDTH", STREAM_ID_WIDTH),
    ("LRQ_NUM_TABLES", LRQ_NUM_TABLES),
    ("LRQ_NUM_REQUESTS", LRQ_NUM_REQUESTS),
    ("LLB_NUM_TABLES", LLB_NUM_TABLES),
    ("LLB_NUM_BYTES", LLB_NUM_BYTES),
    ("LMMU_NUM_VECS", LMMU_NUM_VECS),
    ("SMMU_NUM_ADDRESSES", SMMU_NUM_ADDRESSES),
    ("ADDRESS_WIDTH", ADDRESS_WIDTH),
    ("VEC_WIDTH", VEC_WIDTH),
    ("NUM_SRC_OPERANDS", NUM_SRC_OPERANDS),
    ("AXI_R_DATA_WIDTH", AXI_R_DATA_WIDTH),
    ("AXI_W_DATA_WIDTH", AXI_W_DATA_WIDTH),
    ("MAX_NUM_LOAD_STREAMS", MAX_NUM_LOAD_STREAMS),
    ("MAX_NUM_STORE_STREAMS", MAX_NUM_STORE_STREAMS)
  )

  externalParams.foreach { case (param, value) =>
    externalParametersPackageContent.append(s"  parameter _$param = $value;\n")
  }

  externalParametersPackageContent.append(s"endpackage\n")

  setInline("auto_external_params_pkg.sv", externalParametersPackageContent.toString())

  addResource("/streamingenginesv/vsrc/StreamingEngineSv.sv")
  addResource("/streamingenginesv/vsrc/engine_pkg.sv")
  addResource("/streamingenginesv/vsrc/StreamConfigurator/StreamConfigurator.sv")
  addResource("/streamingenginesv/vsrc/StreamState/StreamState.sv")
  addResource("/streamingenginesv/vsrc/StreamState/StreamTables.sv")
  addResource("/streamingenginesv/vsrc/StreamIterator/StreamIterator.sv")
  addResource("/streamingenginesv/vsrc/Arbiter/RoundRobinArbiter.sv")
  addResource("/streamingenginesv/vsrc/LoadMMU/LoadFIFO.sv")
  addResource("/streamingenginesv/vsrc/LoadMMU/LoadLineBuffer.sv")
  addResource("/streamingenginesv/vsrc/LoadMMU/LoadMMU.sv")
  addResource("/streamingenginesv/vsrc/LoadMMU/LoadRequestQueue.sv")
  addResource("/streamingenginesv/vsrc/StoreMMU/StoreMMU.sv")
}
