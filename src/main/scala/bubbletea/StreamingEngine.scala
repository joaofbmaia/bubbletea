package bubbletea

import chisel3._
import chisel3.util.Decoupled
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}

class StreamingEngineCtrlBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  val reset = Output(Bool())
  val loadStreamsDone = Input(Vec(config.maxSimultaneousMacroStreams, Bool()))
  val loadStreamsCompleted = Input(Vec(config.maxSimultaneousMacroStreams, Vec(config.seMaxStreamDims, Bool())))
}

class StreamingEngineCfgBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  val start = Bool()
  val end = Bool()
  val loadStore = Bool()
  val elementWidth = UInt(2.W) //should be enum
  val stream = UInt(config.seStreamIdWidth.W)
  val mod = Bool()
  val vectorize = Bool()
  val modTarget = UInt(2.W) //should be enum
  val modBehaviour = Bool()
  val modDisplacement = UInt(config.seStrideWidth.W)
  val modSize = UInt(config.seSizeWidth.W)
  val dimOffset = UInt(config.seOffsetWidth.W)
  val dimStride = UInt(config.seStrideWidth.W)
  val dimSize = UInt(config.seSizeWidth.W)
}

// class StreamingEngineLoadOperandBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
//   val done = Bool()
//   val vecData = UInt(config.macroStreamDepth.W)
//   val predicate = UInt((config.macroStreamDepth / 8).W)
//   val completed = UInt(config.seMaxStreamDims.W)
// }

// class StreamingEngineStoreOperandBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
//   val vecData = UInt(config.macroStreamDepth.W)
//   val predicate = UInt((config.macroStreamDepth / 8).W)
// }

class StreamingEngineHpcBundle extends Bundle {
  val ssDesc = UInt(32.W)
  val lmmuCommit = UInt(32.W)
  val lmmuStall = UInt(32.W)
  val llmuStallLf = UInt(32.W)
  val llmuStallLrq = UInt(32.W)
  val llmuStallLlb = UInt(32.W)
  val smmuCommit = UInt(32.W)
  val smmuStall = UInt(32.W)
  val opsLoad = UInt(16.W)
  val opsStore = UInt(16.W)
}

class StreamingEngine[T <: Data](config: AcceleratorConfig[T]) extends Module {
  val io = IO(new Bundle {
    //val ctrlReset = Input(Bool())
    val control = Flipped(new StreamingEngineCtrlBundle(config))
    val cfg = Flipped(Decoupled(new StreamingEngineCfgBundle(config)))
    val loadStreams = Decoupled((Vec(config.maxSimultaneousMacroStreams, Vec(config.macroStreamDepth, config.dataType))))
    val storeStreams = Flipped(Decoupled((Vec(1, Vec(config.macroStreamDepth, config.dataType)))))
    // val loadOperands = Vec(config.maxSimultaneousMacroStreams, Decoupled(new StreamingEngineLoadOperandBundle(config)))
    // val storeOperands = Flipped(Vec(1, Decoupled(new StreamingEngineStoreOperandBundle(config))))
    val memory = AXI4Bundle(new AXI4BundleParameters(config.seAddressWidth, config.seAxiDataWidth, 1))
    val hpc = Output(new StreamingEngineHpcBundle)
  })

  // Instantiate the streaming engine
  val streamingEngineImpl = Module(
    new streamingengine.StreamingEngine(
      STREAM_NUM_DIMS = config.seMaxStreamDims,
      STREAM_NUM_MODS = config.seMaxStreamMods,
      STREAM_OFFSET_WIDTH = config.seOffsetWidth,
      STREAM_STRIDE_WIDTH = config.seStrideWidth,
      STREAM_SIZE_WIDTH = config.seSizeWidth,
      STREAM_ID_WIDTH = config.seStreamIdWidth,
      LRQ_NUM_TABLES = config.seLrqNumTables,
      LRQ_NUM_REQUESTS = config.seLrqNumRequests,
      LLB_NUM_TABLES = config.seLlbNumTables,
      LLB_NUM_BYTES = config.seLlbNumBytes,
      LMMU_NUM_VECS = config.seLmmuNumVecs,
      SMMU_NUM_ADDRESSES = config.seSmmuNumAddresses,
      ADDRESS_WIDTH = config.seAddressWidth,
      VEC_WIDTH = config.macroStreamDepth * config.dataType.getWidth,
      NUM_SRC_OPERANDS = config.maxSimultaneousMacroStreams,
      AXI_R_DATA_WIDTH = config.seAxiDataWidth,
      AXI_W_DATA_WIDTH = config.seAxiDataWidth,
      MAX_NUM_LOAD_STREAMS = config.seMaxNumLoadStreams,
      MAX_NUM_STORE_STREAMS = config.seMaxNumStoreStreams
    )
  )

  // Control
  //streamingEngineImpl.io.ctrl_reset := io.ctrlReset
  streamingEngineImpl.io.ctrl_reset := io.control.reset
  for (i <- 0 until config.maxSimultaneousMacroStreams) {
    io.control.loadStreamsCompleted(i) := streamingEngineImpl.io.rs_out_completed(i).asTypeOf(Vec(config.seMaxStreamDims, Bool()))
  }
  io.control.loadStreamsDone := streamingEngineImpl.io.rs_out_done


  // Configuration channel
  io.cfg.ready := streamingEngineImpl.io.cpu_out_cfg_ready
  streamingEngineImpl.io.cpu_in_cfg_valid := io.cfg.valid
  streamingEngineImpl.io.cpu_in_cfg_sta := io.cfg.bits.start
  streamingEngineImpl.io.cpu_in_cfg_end := io.cfg.bits.end
  streamingEngineImpl.io.cpu_in_cfg_type := io.cfg.bits.loadStore
  streamingEngineImpl.io.cpu_in_cfg_width := io.cfg.bits.elementWidth
  streamingEngineImpl.io.cpu_in_cfg_stream := io.cfg.bits.stream
  streamingEngineImpl.io.cpu_in_cfg_mod := io.cfg.bits.mod
  streamingEngineImpl.io.cpu_in_cfg_vectorize := io.cfg.bits.vectorize
  streamingEngineImpl.io.cpu_in_cfg_mod_target := io.cfg.bits.modTarget
  streamingEngineImpl.io.cpu_in_cfg_mod_behaviour := io.cfg.bits.modBehaviour
  streamingEngineImpl.io.cpu_in_cfg_mod_displacement := io.cfg.bits.modDisplacement
  streamingEngineImpl.io.cpu_in_cfg_mod_size := io.cfg.bits.modSize
  streamingEngineImpl.io.cpu_in_cfg_dim_offset := io.cfg.bits.dimOffset
  streamingEngineImpl.io.cpu_in_cfg_dim_stride := io.cfg.bits.dimStride
  streamingEngineImpl.io.cpu_in_cfg_dim_size := io.cfg.bits.dimSize

  // Load streams channel
  io.loadStreams.valid := streamingEngineImpl.io.rs_out_valid.reduce(_&&_)
  for (i <- 0 until config.maxSimultaneousMacroStreams) {
    streamingEngineImpl.io.rs_in_ready(i) := io.loadStreams.ready
    io.loadStreams.bits(i) := streamingEngineImpl.io.rs_out_vecdata(i).asTypeOf(Vec(config.macroStreamDepth, config.dataType))
    streamingEngineImpl.io.rs_in_streamid(i) := i.U
    //streamingEngineImpl.io.rs_in_predicate(i) is not used //TODO: fix this
  }

  // Store stream channel
  streamingEngineImpl.io.rd_in_valid := io.storeStreams.valid
  streamingEngineImpl.io.rd_in_streamid := 0.U
  streamingEngineImpl.io.rd_in_vecdata := io.storeStreams.bits(0).asUInt
  io.storeStreams.ready := streamingEngineImpl.io.rd_out_ready
  streamingEngineImpl.io.rd_in_predicate := -1.S.asUInt //TODO: fix this


  
  // // Load operands channel
  // for (i <- 0 until config.maxSimultaneousMacroStreams) {
  //   streamingEngineImpl.io.rs_in_ready(i) := io.loadOperands(i).ready
  //   io.loadOperands(i).valid := streamingEngineImpl.io.rs_out_valid(i)
  //   io.loadOperands(i).bits.done := streamingEngineImpl.io.rs_out_done(i)
  //   io.loadOperands(i).bits.vecData := streamingEngineImpl.io.rs_out_vecdata(i)
  //   io.loadOperands(i).bits.predicate := streamingEngineImpl.io.rs_out_predicate(i)
  //   io.loadOperands(i).bits.completed := streamingEngineImpl.io.rs_out_completed(i)
  //   streamingEngineImpl.io.rs_in_streamid(i) := i.U
  // }

  // // Store operands channel
  // streamingEngineImpl.io.rd_in_valid := io.storeOperands(0).valid
  // streamingEngineImpl.io.rd_in_streamid := io.storeOperands(0).bits.vecData
  // streamingEngineImpl.io.rd_in_vecdata := io.storeOperands(0).bits.vecData
  // streamingEngineImpl.io.rd_in_predicate := io.storeOperands(0).bits.predicate
  // io.storeOperands(0).ready := streamingEngineImpl.io.rd_out_ready
  // streamingEngineImpl.io.rd_in_streamid := 0.U
  // // streamingEngineImpl.io.rd_out_width is not used


  // Memory AXI Connections
  // AW
  streamingEngineImpl.io.axi_aw_ready := io.memory.aw.ready
  io.memory.aw.valid := streamingEngineImpl.io.axi_aw_valid
  io.memory.aw.bits.id := 0.U
  io.memory.aw.bits.addr := streamingEngineImpl.io.axi_aw_addr
  io.memory.aw.bits.len := streamingEngineImpl.io.axi_aw_len
  io.memory.aw.bits.size := streamingEngineImpl.io.axi_aw_size
  io.memory.aw.bits.burst := streamingEngineImpl.io.axi_aw_burst
  io.memory.aw.bits.lock := 0.U
  io.memory.aw.bits.cache := 0.U
  io.memory.aw.bits.prot := 0.U
  io.memory.aw.bits.qos := 0.U

  // W
  streamingEngineImpl.io.axi_w_ready := io.memory.w.ready
  io.memory.w.valid := streamingEngineImpl.io.axi_w_valid
  io.memory.w.bits.data := streamingEngineImpl.io.axi_w_data
  io.memory.w.bits.strb := streamingEngineImpl.io.axi_w_strb
  io.memory.w.bits.last := streamingEngineImpl.io.axi_w_last
  
  // B
  io.memory.b.ready := streamingEngineImpl.io.axi_b_ready
  streamingEngineImpl.io.axi_b_valid := io.memory.b.valid
  streamingEngineImpl.io.axi_b_resp := io.memory.b.bits.resp
  // io.memory.b.bits.id is not used

  // AR
  streamingEngineImpl.io.axi_ar_ready := io.memory.ar.ready
  io.memory.ar.valid := streamingEngineImpl.io.axi_ar_valid
  io.memory.ar.bits.id := 0.U
  io.memory.ar.bits.addr := streamingEngineImpl.io.axi_ar_addr
  io.memory.ar.bits.len := streamingEngineImpl.io.axi_ar_len
  io.memory.ar.bits.size := streamingEngineImpl.io.axi_ar_size
  io.memory.ar.bits.burst := streamingEngineImpl.io.axi_ar_burst
  io.memory.ar.bits.lock := 0.U
  io.memory.ar.bits.cache := 0.U
  io.memory.ar.bits.prot := 0.U
  io.memory.ar.bits.qos := 0.U

  // R
  io.memory.r.ready := streamingEngineImpl.io.axi_r_ready
  streamingEngineImpl.io.axi_r_valid := io.memory.r.valid
  streamingEngineImpl.io.axi_r_data := io.memory.r.bits.data
  streamingEngineImpl.io.axi_r_resp := io.memory.r.bits.resp
  streamingEngineImpl.io.axi_r_last := io.memory.r.bits.last
  // io.memory.r.bits.id is not used


  // Hardware performance counters
  io.hpc.ssDesc := streamingEngineImpl.io.hpc_ss_desc
  io.hpc.lmmuCommit := streamingEngineImpl.io.hpc_lmmu_commit
  io.hpc.lmmuStall := streamingEngineImpl.io.hpc_lmmu_stall
  io.hpc.llmuStallLf := streamingEngineImpl.io.hpc_lmmu_stall_lf
  io.hpc.llmuStallLrq := streamingEngineImpl.io.hpc_lmmu_stall_lrq
  io.hpc.llmuStallLlb := streamingEngineImpl.io.hpc_lmmu_stall_llb
  io.hpc.smmuCommit := streamingEngineImpl.io.hpc_smmu_commit
  io.hpc.smmuStall := streamingEngineImpl.io.hpc_smmu_stall
  io.hpc.opsLoad := streamingEngineImpl.io.hpc_ops_load
  io.hpc.opsStore := streamingEngineImpl.io.hpc_ops_store

}
