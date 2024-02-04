package bubbletea

import chisel3._
import chisel3.util.Decoupled
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}
import chisel3.util.log2Ceil
import chisel3.util.RRArbiter
import chisel3.util.PriorityEncoder
import chisel3.util.Counter

class StreamingEngineStaticConfigurationBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  val loadStreamsConfigured = Vec(config.maxSimultaneousLoadMacroStreams, Bool())
  val storeStreamsConfigured = Vec(config.maxSimultaneousStoreMacroStreams, Bool())
  val storeStreamsVecLengthMinusOne = Vec(config.maxSimultaneousStoreMacroStreams, UInt(log2Ceil(config.macroStreamDepth).W))
}

class StreamingEngineControlBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
  val reset = Output(Bool())
  val loadStreamsDone = Input(Bool())
  val storeStreamsDone = Input(Bool())
}

class StreamingEngineConfigurationChannelBundle[T <: Data](config: AcceleratorConfig[T]) extends Bundle {
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
    val control = Flipped(new StreamingEngineControlBundle(config))
    val staticConfiguration = Input(new StreamingEngineStaticConfigurationBundle(config))
    val configurationChannel = Flipped(Decoupled(new StreamingEngineConfigurationChannelBundle(config)))
    val loadStreams = Decoupled((Vec(config.maxSimultaneousLoadMacroStreams, Vec(config.macroStreamDepth, config.dataType))))
    val storeStreams = Flipped(Decoupled((Vec(config.maxSimultaneousStoreMacroStreams, Vec(config.macroStreamDepth, config.dataType)))))
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
      NUM_SRC_OPERANDS = config.maxSimultaneousLoadMacroStreams,
      AXI_R_DATA_WIDTH = config.seAxiDataWidth,
      AXI_W_DATA_WIDTH = config.seAxiDataWidth,
      MAX_NUM_LOAD_STREAMS = config.seMaxNumLoadStreams,
      MAX_NUM_STORE_STREAMS = config.seMaxNumStoreStreams
    )
  )

  // Control
  streamingEngineImpl.io.ctrl_reset := io.control.reset



  // Configuration channel
  io.configurationChannel.ready := streamingEngineImpl.io.cpu_out_cfg_ready
  streamingEngineImpl.io.cpu_in_cfg_valid := io.configurationChannel.valid
  streamingEngineImpl.io.cpu_in_cfg_sta := io.configurationChannel.bits.start
  streamingEngineImpl.io.cpu_in_cfg_end := io.configurationChannel.bits.end
  streamingEngineImpl.io.cpu_in_cfg_type := io.configurationChannel.bits.loadStore
  streamingEngineImpl.io.cpu_in_cfg_width := io.configurationChannel.bits.elementWidth
  streamingEngineImpl.io.cpu_in_cfg_stream := io.configurationChannel.bits.stream
  streamingEngineImpl.io.cpu_in_cfg_mod := io.configurationChannel.bits.mod
  streamingEngineImpl.io.cpu_in_cfg_vectorize := io.configurationChannel.bits.vectorize
  streamingEngineImpl.io.cpu_in_cfg_mod_target := io.configurationChannel.bits.modTarget
  streamingEngineImpl.io.cpu_in_cfg_mod_behaviour := io.configurationChannel.bits.modBehaviour
  streamingEngineImpl.io.cpu_in_cfg_mod_displacement := io.configurationChannel.bits.modDisplacement
  streamingEngineImpl.io.cpu_in_cfg_mod_size := io.configurationChannel.bits.modSize
  streamingEngineImpl.io.cpu_in_cfg_dim_offset := io.configurationChannel.bits.dimOffset
  streamingEngineImpl.io.cpu_in_cfg_dim_stride := io.configurationChannel.bits.dimStride
  streamingEngineImpl.io.cpu_in_cfg_dim_size := io.configurationChannel.bits.dimSize


  

  // Load streams channel
  val allConfiguredAreValid = (streamingEngineImpl.io.rs_out_valid zip io.staticConfiguration.loadStreamsConfigured).map { case (a, b) => a || !b }.reduce(_ && _)
  val allUnconfigured = !io.staticConfiguration.loadStreamsConfigured.reduce(_ || _)
  val loadValid = allConfiguredAreValid && !allUnconfigured
  io.loadStreams.valid := loadValid

  val loadReady = Wire(Bool())
  loadReady := io.loadStreams.ready && loadValid

  for (i <- 0 until config.maxSimultaneousLoadMacroStreams) {
    streamingEngineImpl.io.rs_in_ready(i) := loadReady
    io.loadStreams.bits(i) := streamingEngineImpl.io.rs_out_vecdata(i).asTypeOf(Vec(config.macroStreamDepth, config.dataType))
    streamingEngineImpl.io.rs_in_streamid(i) := i.U
    //streamingEngineImpl.io.rs_in_predicate(i) is not used
  }

  // streamingEngineImpl.io.rs_out_completed is unused


  // Handle the all done signal for the load streams
  val loadStreamsDoneReg = withReset(reset.asBool || io.control.reset)(RegInit(VecInit(Seq.fill(config.maxSimultaneousLoadMacroStreams)(false.B))))

  for (i <- 0 until config.maxSimultaneousLoadMacroStreams) {
    when (streamingEngineImpl.io.rs_out_done(i)) {
      loadStreamsDoneReg(i) := true.B
    }
  }

  io.control.loadStreamsDone := (loadStreamsDoneReg === io.staticConfiguration.loadStreamsConfigured) && io.staticConfiguration.loadStreamsConfigured.reduce(_ || _)



  // Store stream channel
  // Since current SE implementation only supports one store stream, we need to use an round robin arbiter to multiplex the store streams
  val storeStreamIdBase = config.maxSimultaneousLoadMacroStreams

  // This is the bundle that will be used by the arbiter
  class storeStreamBundle extends Bundle {
    val streamId = UInt(config.seStreamIdWidth.W)
    val vecData = Vec(config.macroStreamDepth, config.dataType)
    val vecLengthMinusOne = UInt(log2Ceil(config.macroStreamDepth).W)
  }

  // This is the wire that represents the multiple store streams
  val storeStreamsWithMetadata = Wire(Vec(config.maxSimultaneousStoreMacroStreams, Decoupled(new storeStreamBundle)))

  for (i <- 0 until config.maxSimultaneousStoreMacroStreams) {
    storeStreamsWithMetadata(i).valid := io.storeStreams.valid && io.staticConfiguration.storeStreamsConfigured(i)
    storeStreamsWithMetadata(i).bits.streamId := storeStreamIdBase.U + i.U
    storeStreamsWithMetadata(i).bits.vecData := io.storeStreams.bits(i)
    storeStreamsWithMetadata(i).bits.vecLengthMinusOne := io.staticConfiguration.storeStreamsVecLengthMinusOne(i)
  }

  // Register to keep track of which store stream has already been read by the arbiter
  val storeStreamFired = withReset(reset.asBool || io.control.reset)(RegInit(VecInit(Seq.fill(config.maxSimultaneousStoreMacroStreams)(false.B))))
  for (i <- 0 until config.maxSimultaneousStoreMacroStreams) {
    when(storeStreamsWithMetadata(i).fire) {
      storeStreamFired(i) := true.B
    }
  }
  val numStoreStreamsFired = storeStreamFired.count(identity)
  val numberOfConfiguredStoreStreams = io.staticConfiguration.storeStreamsConfigured.count(identity)

  val anyStoreSteamFireNow = storeStreamsWithMetadata.map(_.fire).reduce(_ || _)

  // When all the store streams have been read by the arbiter, including the one that will be read this cycle, we can set the ready signal
  when((numStoreStreamsFired === numberOfConfiguredStoreStreams - 1.U) && anyStoreSteamFireNow) {
    io.storeStreams.ready := true.B
    storeStreamFired := 0.U.asTypeOf(storeStreamFired)
  } .otherwise {
    io.storeStreams.ready := false.B
  }


  val storeStreamArbiter = Module(new RRArbiter(new storeStreamBundle, config.maxSimultaneousStoreMacroStreams))
  storeStreamArbiter.io.in :<>= storeStreamsWithMetadata

  streamingEngineImpl.io.rd_in_valid := storeStreamArbiter.io.out.valid
  streamingEngineImpl.io.rd_in_streamid := storeStreamArbiter.io.out.bits.streamId
  streamingEngineImpl.io.rd_in_vecdata := storeStreamArbiter.io.out.bits.vecData.asUInt
  storeStreamArbiter.io.out.ready := streamingEngineImpl.io.rd_out_ready

  val storePredicate = Wire(UInt((config.macroStreamDepth * config.dataType.getWidth / 8).W))
  // This just makes the storePredicate with the LSBs set to 1 until the vector legth
  // Example: if vector length is 4, storePredicate will be 0b00000000000000000000000000001111
  storePredicate := (-1.S(storePredicate.getWidth.W).asUInt >> (((config.macroStreamDepth.U - 1.U) - storeStreamArbiter.io.out.bits.vecLengthMinusOne) * (config.dataType.getWidth / 8).U)).asUInt
  streamingEngineImpl.io.rd_in_predicate := storePredicate

  // Handle the all done signal for the store streams
  val storeStreamsDoneCounter = withReset(reset.asBool || io.control.reset)(Counter(config.maxSimultaneousStoreMacroStreams + 1))
  when (streamingEngineImpl.io.rd_out_done) {
    storeStreamsDoneCounter.inc()
  }

  val storeStreamsDone = withReset(reset.asBool || io.control.reset)(RegInit(false.B))
  when ((storeStreamsDoneCounter.value === numberOfConfiguredStoreStreams) && numberOfConfiguredStoreStreams =/= 0.U && streamingEngineImpl.io.rd_out_store_request_successful) {
    storeStreamsDone := true.B
  }
  io.control.storeStreamsDone := storeStreamsDone



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
