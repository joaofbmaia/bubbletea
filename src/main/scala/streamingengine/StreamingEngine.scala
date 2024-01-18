import chisel3._
import chisel3.util._
import chisel3.stage.ChiselStage





/**
  * 
  *
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
    val VEC_NUM_BYTES   = VEC_WIDTH / 8
    val SS_NUM_TABLES   = MAX_NUM_LOAD_STREAMS + MAX_NUM_STORE_STREAMS
    val LF_NUM_BYTES    = LMMU_NUM_VECS * VEC_NUM_BYTES
    val LF_NUM_TABLES   = MAX_NUM_LOAD_STREAMS
    val SMMU_NUM_TABLES = MAX_NUM_STORE_STREAMS
             


    /* The set of input and output ports of the module */
    val io = IO(new Bundle {

        /* Control channel */
        val ctrl_reset = Input(Bool())
		
        /* Processing unit - configuration channel */
        val cpu_in_cfg_valid 			= Input(Bool()) 
        val cpu_in_cfg_sta 			    = Input(Bool())                
        val cpu_in_cfg_end 			    = Input(Bool())  
        val cpu_in_cfg_type 			= Input(Bool()) 
        val cpu_in_cfg_width 			= Input(UInt(2.W))
        val cpu_in_cfg_stream 			= Input(UInt(STREAM_ID_WIDTH.W))
        val cpu_in_cfg_mod 				= Input(Bool()) 
        val cpu_in_cfg_vectorize 		= Input(Bool()) 
        val cpu_in_cfg_mod_target       = Input(UInt(2.W))
        val cpu_in_cfg_mod_behaviour    = Input(Bool())
        val cpu_in_cfg_mod_displacement = Input(UInt(STREAM_STRIDE_WIDTH.W))
        val cpu_in_cfg_mod_size         = Input(UInt(STREAM_SIZE_WIDTH.W))
        val cpu_in_cfg_dim_offset    	= Input(UInt(STREAM_OFFSET_WIDTH.W))
        val cpu_in_cfg_dim_stride    	= Input(UInt(STREAM_STRIDE_WIDTH.W))
        val cpu_in_cfg_dim_size      	= Input(UInt(STREAM_SIZE_WIDTH.W))
		val cpu_out_cfg_ready 			= Output(Bool())

        /* Source operands data channel */
        val rs_in_streamid   = Input(Vec(NUM_SRC_OPERANDS, UInt(STREAM_ID_WIDTH.W)))
        val rs_in_ready      = Input(Vec(NUM_SRC_OPERANDS, Bool()))
        val rs_out_valid     = Output(Vec(NUM_SRC_OPERANDS, Bool()))
        val rs_out_done      = Output(Vec(NUM_SRC_OPERANDS, Bool()))
        val rs_out_vecdata   = Output(Vec(NUM_SRC_OPERANDS, UInt(VEC_WIDTH.W)))
        val rs_out_predicate = Output(Vec(NUM_SRC_OPERANDS, UInt(VEC_NUM_BYTES.W)))
        val rs_out_completed = Output(Vec(NUM_SRC_OPERANDS, UInt(STREAM_NUM_DIMS.W)))

        /* Destination operands data channel */
        val rd_in_valid     = Input(Bool())
        val rd_in_streamid  = Input(UInt(STREAM_ID_WIDTH.W))
        val rd_in_vecdata   = Input(UInt(VEC_WIDTH.W))
        val rd_in_predicate = Input(UInt(VEC_NUM_BYTES.W))
        val rd_out_ready    = Output(Bool())
        val rd_out_width    = Output(UInt(2.W))

        /* AXI Read Address Channel */
        val axi_ar_ready = Input(Bool())
        val axi_ar_addr  = Output(UInt(ADDRESS_WIDTH.W))
        val axi_ar_valid = Output(Bool())
        val axi_ar_len   = Output(UInt(8.W)) // ARLEN[7:0]
        val axi_ar_size  = Output(UInt(3.W)) // ARSIZE[2:0]
        val axi_ar_burst = Output(UInt(1.W)) // ARBURST[1:0]

        /* AXI Read Data Channel */
        val axi_r_data   = Input(UInt(AXI_R_DATA_WIDTH.W))
        val axi_r_valid  = Input(Bool())
        val axi_r_last   = Input(Bool())
        val axi_r_resp   = Input(UInt(2.W))
        val axi_r_ready  = Output(Bool())   

		/* AXI Write Address Channel */
		val axi_aw_ready = Input(Bool())
		val axi_aw_valid = Output(Bool())
		val axi_aw_addr  = Output(UInt(ADDRESS_WIDTH.W))
        val axi_aw_len   = Output(UInt(8.W)) // ARLEN[7:0]
        val axi_aw_size  = Output(UInt(3.W)) // ARSIZE[2:0]
        val axi_aw_burst = Output(UInt(1.W)) // ARBURST[1:0]

		/* AXI Write Data Channel */
		val axi_w_ready  = Input(Bool())
		val axi_w_valid  = Output(Bool())
		val axi_w_data   = Output(UInt(AXI_W_DATA_WIDTH.W))
		val axi_w_strb   = Output(UInt((AXI_W_DATA_WIDTH / 8).W))
		val axi_w_last   = Output(Bool())

		/* AXI Write Response Channel */
		val axi_b_resp   = Input(UInt(2.W))
		val axi_b_valid  = Input(Bool())
		val axi_b_ready  = Output(Bool()) 

        /* Performance counters */
        val hpc_ss_desc        = Output(UInt(32.W))
        val hpc_lmmu_commit    = Output(UInt(32.W))
        val hpc_lmmu_stall     = Output(UInt(32.W))
        val hpc_lmmu_stall_lf  = Output(UInt(32.W))
        val hpc_lmmu_stall_lrq = Output(UInt(32.W))
        val hpc_lmmu_stall_llb = Output(UInt(32.W))
        val hpc_smmu_commit    = Output(UInt(32.W))
        val hpc_smmu_stall     = Output(UInt(32.W))
        val hpc_ops_load       = Output(UInt(16.W))
        val hpc_ops_store      = Output(UInt(16.W))
        
    })



    /* The Streaming Engine is the combination of the
     * Stream Configuration, the Stream State, the Stream
     * Iterator and the Load and Store Memory Management Units
     */
    val SC = Module(new StreamConfiguration(
        STREAM_NUM_DIMS, 
        STREAM_NUM_MODS,
        STREAM_OFFSET_WIDTH, 
        STREAM_STRIDE_WIDTH, 
        STREAM_SIZE_WIDTH, 
        STREAM_ID_WIDTH))

    val SS = Module(new StreamState(
        STREAM_NUM_DIMS, 
        STREAM_NUM_MODS,
        SS_NUM_TABLES,
        STREAM_OFFSET_WIDTH, 
        STREAM_STRIDE_WIDTH, 
        STREAM_SIZE_WIDTH, 
        STREAM_ID_WIDTH))

    val SI = Module(new StreamIterator(
        STREAM_NUM_DIMS, 
        STREAM_OFFSET_WIDTH, 
        STREAM_STRIDE_WIDTH, 
        STREAM_SIZE_WIDTH))

    val LMMU = Module(new LoadMMU(
        STREAM_NUM_DIMS,
        STREAM_ID_WIDTH,
        VEC_NUM_BYTES,
        LRQ_NUM_TABLES,
        LRQ_NUM_REQUESTS,
        LLB_NUM_TABLES,
        LLB_NUM_BYTES,
        LF_NUM_TABLES,
        LF_NUM_BYTES,
        ADDRESS_WIDTH,
        AXI_R_DATA_WIDTH,
        NUM_SRC_OPERANDS))

    val SMMU = Module(new StoreMMU(
        SMMU_NUM_TABLES, 
        SMMU_NUM_ADDRESSES, 
        STREAM_ID_WIDTH, 
        ADDRESS_WIDTH, 
        VEC_NUM_BYTES,
        AXI_W_DATA_WIDTH))




                            
    /* Connecting the input ports of the declared modules
     * to some of the declared wires
     */	

    /* Stream Configurations */
	SC.io.ctrl_reset 			  := io.ctrl_reset
	  
    SC.io.cpu_in_valid 			  := io.cpu_in_cfg_valid
    SC.io.cpu_in_sta   			  := io.cpu_in_cfg_sta
    SC.io.cpu_in_end   			  := io.cpu_in_cfg_end
    SC.io.cpu_in_type   		  := io.cpu_in_cfg_type
    SC.io.cpu_in_width  		  := io.cpu_in_cfg_width
    SC.io.cpu_in_stream 		  := io.cpu_in_cfg_stream 
    SC.io.cpu_in_mod 			  := io.cpu_in_cfg_mod 
    SC.io.cpu_in_vec 			  := io.cpu_in_cfg_vectorize
    SC.io.cpu_in_dim_offset		  := io.cpu_in_cfg_dim_offset
    SC.io.cpu_in_dim_stride 	  := io.cpu_in_cfg_dim_stride
    SC.io.cpu_in_dim_size   	  := io.cpu_in_cfg_dim_size
    SC.io.cpu_in_mod_target       := io.cpu_in_cfg_mod_target 
    SC.io.cpu_in_mod_behaviour    := io.cpu_in_cfg_mod_behaviour
    SC.io.cpu_in_mod_displacement := io.cpu_in_cfg_mod_displacement
    SC.io.cpu_in_mod_size         := io.cpu_in_cfg_mod_size 

    SC.io.se_in_ready_load		  := SS.io.cfg_out_ready && LMMU.io.cfg_out_ready
    SC.io.se_in_ready_store		  := SS.io.cfg_out_ready && SMMU.io.cfg_out_ready





    /* Stream State */
	SS.io.ctrl_reset  	  	   := io.ctrl_reset

	SS.io.cfg_in_ack     	   := SC.io.se_out_ack
    SS.io.cfg_in_we 	  	   := SC.io.se_out_cfg_we
    SS.io.cfg_in_width   	   := SC.io.se_out_cfg_width
    SS.io.cfg_in_stream  	   := SC.io.se_out_cfg_stream
    SS.io.cfg_in_type    	   := SC.io.se_out_cfg_type
    SS.io.cfg_vec_we     	   := SC.io.se_out_vec_we
    SS.io.cfg_vec_idx    	   := SC.io.se_out_vec_idx
    SS.io.cfg_dim_we     	   := SC.io.se_out_dim_we 
    SS.io.cfg_dim_idx    	   := SC.io.se_out_dim_idx
    SS.io.cfg_dim_offset 	   := SC.io.se_out_dim_offset
    SS.io.cfg_dim_stride 	   := SC.io.se_out_dim_stride
    SS.io.cfg_dim_size         := SC.io.se_out_dim_size
    SS.io.cfg_mod_we           := SC.io.se_out_mod_we
    SS.io.cfg_mod_idx          := SC.io.se_out_mod_idx 
    SS.io.cfg_mod_target       := SC.io.se_out_mod_target 
    SS.io.cfg_mod_behaviour    := SC.io.se_out_mod_behaviour
    SS.io.cfg_mod_displacement := SC.io.se_out_mod_displacement 
    SS.io.cfg_mod_size         := SC.io.se_out_mod_size
    SS.io.cfg_in_lmmu_idx      := LMMU.io.cfg_out_mmu_idx
    SS.io.cfg_in_smmu_idx      := SMMU.io.cfg_out_mmu_idx

    SS.io.si_in_iterations    := SI.io.res_iterations
    SS.io.si_in_accumulation  := SI.io.res_accumulation
    SS.io.si_in_last  	 	  := SI.io.val_last
    SS.io.si_in_load_ena      := SI.io.load_ena
    SS.io.si_in_load_dim      := SI.io.load_dim

	SS.io.mmu_in_trigger  	  := (!SS.io.mmu_out_type && SMMU.io.ss_out_trigger) || (SS.io.mmu_out_type && LMMU.io.ss_out_trigger)





    /* Stream Iterator */
    SI.io.src_op1       := SS.io.si_out_op1
    SI.io.src_op2       := SS.io.si_out_op2
    
    SI.io.val_mod        := SS.io.si_out_mod
    SI.io.val_dim        := SS.io.si_out_dim
    SI.io.val_width      := SS.io.si_out_width
    SI.io.val_behaviour  := SS.io.si_out_behaviour
    SI.io.val_iterations := SS.io.si_out_iterations
    SI.io.val_size       := SS.io.si_out_size
    SI.io.val_completed  := SS.io.si_out_completed
    SI.io.val_configured := SS.io.si_out_configured





    /* Load Memory Management Unit */
    LMMU.io.ctrl_reset       := io.ctrl_reset
    
    LMMU.io.cfg_in_we        := SC.io.se_out_cfg_we && SC.io.se_out_cfg_type
    LMMU.io.cfg_in_stream    := SC.io.se_out_cfg_stream
    LMMU.io.cfg_in_width     := SC.io.se_out_cfg_width

    LMMU.io.ss_in_mmu_idx    := SS.io.mmu_out_mmu_idx
    LMMU.io.ss_in_valid      := SS.io.mmu_out_valid && SS.io.mmu_out_type
    LMMU.io.ss_in_addr       := SS.io.mmu_out_addr
    LMMU.io.ss_in_addr_valid := SS.io.mmu_out_addr_valid && SS.io.mmu_out_type
    LMMU.io.ss_in_width      := SS.io.mmu_out_width
    LMMU.io.ss_in_completed  := SS.io.mmu_out_completed
    LMMU.io.ss_in_vectorize  := SS.io.mmu_out_vectorize
    LMMU.io.ss_in_last       := SS.io.mmu_out_last

    LMMU.io.rs_in_ready      := io.rs_in_ready
    LMMU.io.rs_in_streamid   := io.rs_in_streamid

    LMMU.io.axi_ar_ready     := io.axi_ar_ready
    LMMU.io.axi_r_data       := io.axi_r_data
    LMMU.io.axi_r_valid      := io.axi_r_valid
    LMMU.io.axi_r_last       := io.axi_r_last
    LMMU.io.axi_r_resp       := io.axi_r_resp





    /* Store Memory Management Unit */
    SMMU.io.ctrl_reset       := io.ctrl_reset

    SMMU.io.cfg_in_we        := SC.io.se_out_cfg_we && !SC.io.se_out_cfg_type 
    SMMU.io.cfg_in_stream    := SC.io.se_out_cfg_stream
    SMMU.io.cfg_in_width     := SC.io.se_out_cfg_width

    SMMU.io.rd_in_valid      := io.rd_in_valid 
    SMMU.io.rd_in_streamid   := io.rd_in_streamid
    SMMU.io.rd_in_vecdata    := io.rd_in_vecdata
    SMMU.io.rd_in_predicate  := io.rd_in_predicate

    SMMU.io.ss_in_mmu_idx    := SS.io.mmu_out_mmu_idx
    SMMU.io.ss_in_addr       := SS.io.mmu_out_addr
    SMMU.io.ss_in_addr_valid := SS.io.mmu_out_addr_valid && !SS.io.mmu_out_type
	SMMU.io.ss_in_last       := SS.io.mmu_out_last

    SMMU.io.axi_aw_ready     := io.axi_aw_ready
    SMMU.io.axi_w_ready      := io.axi_w_ready
    SMMU.io.axi_b_resp       := io.axi_b_resp
    SMMU.io.axi_b_valid      := io.axi_b_valid





    /* Connecting the output ports of the module to
     * some of the internal wires
     */
	io.cpu_out_cfg_ready := SC.io.cpu_out_ready

    io.rs_out_valid     := LMMU.io.rs_out_valid
    io.rs_out_done      := LMMU.io.rs_out_done
    io.rs_out_vecdata   := LMMU.io.rs_out_vecdata
    io.rs_out_predicate := LMMU.io.rs_out_predicate
    io.rs_out_completed := LMMU.io.rs_out_completed

    io.rd_out_ready := SMMU.io.rd_out_ready
    io.rd_out_width := SMMU.io.rd_out_width

    io.axi_ar_addr  := LMMU.io.axi_ar_addr
    io.axi_ar_valid := LMMU.io.axi_ar_valid
    io.axi_ar_len   := LMMU.io.axi_ar_len
    io.axi_ar_size  := LMMU.io.axi_ar_size
    io.axi_ar_burst := LMMU.io.axi_ar_burst

    io.axi_r_ready  := LMMU.io.axi_r_ready

	io.axi_aw_valid := SMMU.io.axi_aw_valid
	io.axi_aw_addr  := SMMU.io.axi_aw_addr
    io.axi_aw_len   := SMMU.io.axi_aw_len
    io.axi_aw_size  := SMMU.io.axi_aw_size
    io.axi_aw_burst := SMMU.io.axi_aw_burst
	
	io.axi_w_valid  := SMMU.io.axi_w_valid
	io.axi_w_data   := SMMU.io.axi_w_data
	io.axi_w_strb   := SMMU.io.axi_w_strb
    io.axi_w_last   := SMMU.io.axi_w_last

    io.axi_b_ready  := SMMU.io.axi_b_ready

    io.hpc_ss_desc        := SS.io.hpc_ss_desc
    io.hpc_lmmu_commit    := LMMU.io.hpc_lmmu_commit
    io.hpc_lmmu_stall     := LMMU.io.hpc_lmmu_stall
    io.hpc_lmmu_stall_lf  := LMMU.io.hpc_lmmu_stall_lf
    io.hpc_lmmu_stall_lrq := LMMU.io.hpc_lmmu_stall_lrq
    io.hpc_lmmu_stall_llb := LMMU.io.hpc_lmmu_stall_llb
    io.hpc_smmu_commit    := SMMU.io.hpc_smmu_commit
    io.hpc_smmu_stall     := SMMU.io.hpc_smmu_stall
    io.hpc_ops_load       := LMMU.io.hpc_ops_load
    io.hpc_ops_store      := SMMU.io.hpc_ops_store 

}





/**
  * Verilog generator application
  */
object StreamingEngine_Verilog extends App {

    /* Define the parameters */
    val STREAM_NUM_DIMS       = 8
    val STREAM_NUM_MODS       = 3
    val STREAM_OFFSET_WIDTH   = 32
    val STREAM_STRIDE_WIDTH   = 32
    val STREAM_SIZE_WIDTH     = 32
    val STREAM_ID_WIDTH       = 5
    val LRQ_NUM_TABLES        = 16
    val LRQ_NUM_REQUESTS      = 16
    val LLB_NUM_TABLES        = 8
    val LLB_NUM_BYTES         = 8
    val LMMU_NUM_VECS         = 4
    val SMMU_NUM_ADDRESSES    = 64
    val ADDRESS_WIDTH         = 32
    val VEC_WIDTH             = 128
    val NUM_SRC_OPERANDS      = 2
    val AXI_R_DATA_WIDTH      = 64
    val AXI_W_DATA_WIDTH      = 64
    val MAX_NUM_LOAD_STREAMS  = 6
    val MAX_NUM_STORE_STREAMS = 2


    val path = "output/StreamingEngine/"

    
    /* Generate verilog */
    (new ChiselStage).emitVerilog(
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
            MAX_NUM_STORE_STREAMS), 
        Array("--target-dir", path))

}