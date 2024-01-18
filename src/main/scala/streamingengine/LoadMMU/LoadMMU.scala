import chisel3._
import chisel3.util._
import chisel3.stage.ChiselStage





/**
  * 
  *
  * @param STREAM_NUM_DIMS  - number of dimensions the Stream Engine supports
  * @param STREAM_ID_WIDTH  - width of the Stream ID signal
  * @param VEC_NUM_BYTES    - number of bytes in a vector
  * @param LRQ_NUM_TABLES   - number of Load Request Queue tables (associative ways)
  * @param LRQ_NUM_REQUESTS - number of requests within a same LRQ table
  * @param LLB_NUM_TABLES   - number of Data Memory words the Load Line Buffer can hold simultaneously
  * @param LLB_NUM_BYTES    - number of bytes the Load Line Buffer data registers hold (must match Data Memory word size)
  * @param LF_NUM_TABLES    - number of Load FIFOs supported
  * @param LF_NUM_BYTES     - number of bytes each Load FIFO supports
  * @param ADDRESS_WIDTH    - width of addresses produced by the Streaming Engine
  * @param AXI_R_DATA_WIDTH - width of the AXI RDATA bus
  * @param NUM_SRC_OPERANDS - number of source operands communicating with the Streaming Engine
  */
class LoadMMU ( 
    val STREAM_NUM_DIMS:	Int,
    val STREAM_ID_WIDTH:	Int,
    val VEC_NUM_BYTES:		Int,
    val LRQ_NUM_TABLES:  	Int,
    val LRQ_NUM_REQUESTS:	Int, 
    val LLB_NUM_TABLES:    	Int,
    val LLB_NUM_BYTES:		Int, 
    val LF_NUM_TABLES:		Int,
    val LF_NUM_BYTES:		Int,
    val ADDRESS_WIDTH:		Int,
    val AXI_R_DATA_WIDTH:	Int,
    val NUM_SRC_OPERANDS:	Int)
extends Module {

    /* Internal parameters, calculated using external ones */
    val ADDRESS_TAG_WIDTH = ADDRESS_WIDTH - log2Ceil(LLB_NUM_BYTES)
    val VEC_WIDTH = VEC_NUM_BYTES * 8



    /* The set of input and output ports of the module */
    val io = IO(new Bundle {

        /* Control channel */
        val ctrl_reset = Input(Bool())

        /* Configuration channel */
		val cfg_in_we       = Input(Bool())
		val cfg_in_stream   = Input(UInt(STREAM_ID_WIDTH.W))
        val cfg_in_width    = Input(UInt(2.W))
		val cfg_out_ready   = Output(Bool())   
		val cfg_out_mmu_idx = Output(UInt(log2Ceil(LF_NUM_TABLES).W))

        /* Stream State channel */
        val ss_in_mmu_idx    = Input(UInt(log2Ceil(LF_NUM_TABLES).W))
		val ss_in_valid      = Input(Bool())
        val ss_in_addr       = Input(UInt(ADDRESS_WIDTH.W))
        val ss_in_addr_valid = Input(Bool()) 							
        val ss_in_width      = Input(UInt(2.W))
		val ss_in_completed  = Input(UInt(STREAM_NUM_DIMS.W))
        val ss_in_vectorize  = Input(Bool())
		val ss_in_last       = Input(Bool())
        val ss_out_trigger   = Output(Bool()) 	

        /* Source operands data channel */
        val rs_in_streamid   = Input(Vec(NUM_SRC_OPERANDS, UInt(STREAM_ID_WIDTH.W)))
        val rs_in_ready      = Input(Vec(NUM_SRC_OPERANDS, Bool()))
        val rs_out_valid     = Output(Vec(NUM_SRC_OPERANDS, Bool()))
        val rs_out_done      = Output(Vec(NUM_SRC_OPERANDS, Bool()))
        val rs_out_vecdata   = Output(Vec(NUM_SRC_OPERANDS, UInt(VEC_WIDTH.W)))
        val rs_out_predicate = Output(Vec(NUM_SRC_OPERANDS, UInt(VEC_NUM_BYTES.W)))
        val rs_out_completed = Output(Vec(NUM_SRC_OPERANDS, UInt(STREAM_NUM_DIMS.W)))
        
        /* AXI Read Address channel */
        val axi_ar_ready = Input(Bool())
        val axi_ar_addr  = Output(UInt(ADDRESS_WIDTH.W))
        val axi_ar_valid = Output(Bool())
        val axi_ar_len   = Output(UInt(8.W)) // ARLEN[7:0]
        val axi_ar_size  = Output(UInt(3.W)) // ARSIZE[2:0]
        val axi_ar_burst = Output(UInt(1.W)) // ARBURST[1:0]

        /* AXI Read Data channel */
        val axi_r_data  = Input(UInt(AXI_R_DATA_WIDTH.W))
        val axi_r_valid = Input(Bool())
        val axi_r_last  = Input(Bool())
        val axi_r_resp  = Input(UInt(2.W))
        val axi_r_ready = Output(Bool())   

        /* Performance counters */
        val hpc_lmmu_commit    = Output(UInt(32.W))
        val hpc_lmmu_stall     = Output(UInt(32.W))
        val hpc_lmmu_stall_lrq = Output(UInt(32.W))
        val hpc_lmmu_stall_lf  = Output(UInt(32.W))
        val hpc_lmmu_stall_llb = Output(UInt(32.W))
        val hpc_ops_load       = Output(UInt(16.W)) 

    })


    
    /* The Load MMU is the combination of the Load FIFO,
     * the Load Request Queue and the Load Line Buffer
     */
    val LF  = Module(new LoadFIFO(         
        STREAM_NUM_DIMS,
        STREAM_ID_WIDTH,
        VEC_NUM_BYTES,
        LF_NUM_TABLES,
        LF_NUM_BYTES,
        LLB_NUM_BYTES,
        ADDRESS_WIDTH,
        NUM_SRC_OPERANDS))
                                      
    val LRQ = Module(new LoadRequestQueue( 
        LRQ_NUM_REQUESTS,
        LRQ_NUM_TABLES,
        LLB_NUM_BYTES,
        LF_NUM_TABLES,
        LF_NUM_BYTES,
        STREAM_ID_WIDTH,
        ADDRESS_WIDTH,
        ADDRESS_TAG_WIDTH))
                                  
    val LLB = Module(new LoadLineBuffer(   
        LLB_NUM_TABLES,
        LLB_NUM_BYTES,
        ADDRESS_WIDTH,
        ADDRESS_TAG_WIDTH,
        AXI_R_DATA_WIDTH))



    

    /* Declaring hardware performance counters regarding the
     * number of addresses received and the number of stalls
     */
    val hpc_lmmu_commit    = Reg(UInt(32.W))
    val hpc_lmmu_stall     = Reg(UInt(32.W))
    val hpc_lmmu_stall_lf  = Reg(UInt(32.W))
    val hpc_lmmu_stall_lrq = Reg(UInt(32.W))




    /* When the Stream State provides a new valid address, the
     * Load MMU increments the performance counters, depending
     * on if it could accept the address or not
     */
    when (io.ss_in_addr_valid) {

        /* The Load FIFO is stalling */
        when (LF.io.ss_out_trigger) {
            hpc_lmmu_stall_lf := hpc_lmmu_stall_lf + 1.U
        }

        /* The Load Request Queue is stalling */
        when (LRQ.io.ss_out_trigger) {
            hpc_lmmu_stall_lrq := hpc_lmmu_stall_lrq + 1.U
        }

        /* Neither the Load FIFO or the LRQ stalled */
        when (!(LF.io.ss_out_trigger || LRQ.io.ss_out_trigger)) {
            hpc_lmmu_commit := hpc_lmmu_commit + 1.U
        }
        .otherwise {
            hpc_lmmu_stall := hpc_lmmu_stall + 1.U
        }

    }





    /* Load FIFO */
    LF.io.ctrl_reset := io.ctrl_reset

    LF.io.cfg_in_we     := io.cfg_in_we         
    LF.io.cfg_in_stream := io.cfg_in_stream         
    LF.io.cfg_in_width  := io.cfg_in_width

    LF.io.ss_in_mmu_idx    := io.ss_in_mmu_idx        
    LF.io.ss_in_valid      := io.ss_in_valid
    LF.io.ss_in_addr_valid := io.ss_in_addr_valid
	LF.io.ss_in_completed  := io.ss_in_completed
    LF.io.ss_in_vectorize  := io.ss_in_vectorize
    LF.io.ss_in_last       := io.ss_in_last

    LF.io.lrq_in_ready := !LRQ.io.ss_out_trigger   

    LF.io.data_llb_in_bytes      := LLB.io.data_lf_out_bytes
    LF.io.data_lrq_in_width      := LRQ.io.lf_out_req_width
    LF.io.data_lrq_in_mmu_idx    := LRQ.io.lf_out_req_mmu_idx
    LF.io.data_lrq_in_offset_lf  := LRQ.io.lf_out_req_offset_lf
    LF.io.data_lrq_in_offset_llb := LRQ.io.lf_out_req_offset_llb
    LF.io.data_lrq_in_valid      := LRQ.io.lf_out_req_valid && LLB.io.data_out_valid 

    LF.io.rs_in_streamid := io.rs_in_streamid
    LF.io.rs_in_ready    := io.rs_in_ready





    /* Load Request Queue */
    LRQ.io.ctrl_reset := io.ctrl_reset

    LRQ.io.ss_in_mmu_idx    := io.ss_in_mmu_idx
    LRQ.io.ss_in_addr       := io.ss_in_addr
    LRQ.io.ss_in_addr_valid := io.ss_in_addr_valid
    LRQ.io.ss_in_width      := io.ss_in_width

    LRQ.io.lf_in_ptr   := LF.io.lrq_out_ptr
    LRQ.io.lf_in_ready := !LF.io.ss_out_trigger

    LRQ.io.req_llb_in_ready := LLB.io.req_lrq_out_ready

    LRQ.io.data_llb_in_addr_tag := LLB.io.data_lrq_out_addr_tag
    LRQ.io.data_llb_in_valid    := LLB.io.data_out_valid



      

    /* Load Line Buffer */
    LLB.io.ctrl_reset := io.ctrl_reset

    LLB.io.req_lrq_in_valid    := LRQ.io.req_llb_out_valid
    LLB.io.req_lrq_in_addr_tag := LRQ.io.req_llb_out_addr_tag

    LLB.io.data_lrq_in_clear := LRQ.io.data_llb_out_clear     

    LLB.io.axi_ar_ready := io.axi_ar_ready 

    LLB.io.axi_r_data  := io.axi_r_data
    LLB.io.axi_r_valid := io.axi_r_valid
    LLB.io.axi_r_last  := io.axi_r_last
    LLB.io.axi_r_resp  := io.axi_r_resp





    /* When the reset signal is activated, the registers of
     * the module will be updated to their default values
     */
    when (io.ctrl_reset) {
        hpc_lmmu_commit    := 0.U
        hpc_lmmu_stall     := 0.U
        hpc_lmmu_stall_lf  := 0.U
        hpc_lmmu_stall_lrq := 0.U
    }





    /* Connecting the output ports to some of 
     * the internal wires of the module
     */
    io.cfg_out_ready   := LF.io.cfg_out_ready
    io.cfg_out_mmu_idx := LF.io.cfg_out_mmu_idx

    io.ss_out_trigger := Mux(io.ss_in_addr_valid, LF.io.ss_out_trigger || LRQ.io.ss_out_trigger, false.B)

    io.rs_out_valid     := LF.io.rs_out_valid
    io.rs_out_done      := LF.io.rs_out_done
    io.rs_out_vecdata   := LF.io.rs_out_vecdata
    io.rs_out_predicate := LF.io.rs_out_predicate
    io.rs_out_completed := LF.io.rs_out_completed

    io.axi_ar_addr  := LLB.io.axi_ar_addr
    io.axi_ar_valid := LLB.io.axi_ar_valid
    io.axi_ar_len   := LLB.io.axi_ar_len
    io.axi_ar_size  := LLB.io.axi_ar_size
    io.axi_ar_burst := LLB.io.axi_ar_burst

    io.axi_r_ready  := LLB.io.axi_r_ready

    io.hpc_lmmu_commit    := hpc_lmmu_commit
    io.hpc_lmmu_stall     := hpc_lmmu_stall
    io.hpc_lmmu_stall_lf  := hpc_lmmu_stall_lf
    io.hpc_lmmu_stall_lrq := hpc_lmmu_stall_lrq
    io.hpc_lmmu_stall_llb := LRQ.io.hpc_lmmu_stall_llb
    io.hpc_ops_load       := LLB.io.hpc_ops_load

}





/**
  * Verilog generator application
  */
object LoadMMU_Verilog extends App {

    /* Define the parameters */
    val STREAM_NUM_DIMS  = 4
    val STREAM_ID_WIDTH  = 5
    val VEC_NUM_BYTES    = 16
    val LRQ_NUM_TABLES   = 16
    val LRQ_NUM_REQUESTS = 8
    val LLB_NUM_TABLES   = 4
    val LLB_NUM_BYTES    = 32
    val LF_NUM_TABLES    = 4
    val LF_NUM_BYTES     = 16
    val ADDRESS_WIDTH    = 32
    val AXI_R_DATA_WIDTH = 128
    val NUM_SRC_OPERANDS = 2

    
    val path = "output/LoadMMU/"

    
    /* Generate verilog */
    (new ChiselStage).emitVerilog(
        new LoadMMU(
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
            NUM_SRC_OPERANDS),
        Array("--target-dir", path))

}