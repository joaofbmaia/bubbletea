import chisel3._
import chisel3.util._
import chisel3.stage.ChiselStage





/**
  * 
  *
  * @param LLB_NUM_BYTES
  * @param LF_NUM_TABLES
  * @param LF_NUM_BYTES
  * @param STREAM_ID_WIDTH
  */
class LRQ_Request_Bundle(
    val LLB_NUM_BYTES:   Int,
    val LF_NUM_TABLES:   Int,
    val LF_NUM_BYTES:    Int, 
    val STREAM_ID_WIDTH: Int)
extends Bundle {

    /* The set of wires that define the bundle */
    val offset_llb  = UInt(log2Ceil(LLB_NUM_BYTES).W)          
    val offset_lf   = UInt(log2Ceil(LF_NUM_BYTES).W)          
    val width_      = UInt(2.W)
    val mmu_idx     = UInt(log2Ceil(LF_NUM_TABLES).W)
    val valid       = Bool()

}





/**
  * 
  *
  * @param LRQ_NUM_REQUESTS
  * @param LRQ_NUM_TABLES
  * @param LLB_NUM_BYTES
  * @param LF_NUM_TABLES
  * @param LF_NUM_BYTES
  * @param STREAM_ID_WIDTH
  * @param ADDRESS_TAG_WIDTH
  */
class LRQ_RequestTable_Bundle ( 
        val LRQ_NUM_REQUESTS:   Int,
        val LRQ_NUM_TABLES:     Int,
        val LLB_NUM_BYTES:      Int,
        val LF_NUM_TABLES:      Int, 
        val LF_NUM_BYTES:       Int,
        val STREAM_ID_WIDTH:    Int,
        val ADDRESS_TAG_WIDTH:  Int)                      
extends Bundle {

    /* The set of wires that define the bundle */
    val requests    = Vec(LRQ_NUM_REQUESTS, 
                        new LRQ_Request_Bundle(
                            LLB_NUM_BYTES,
                            LF_NUM_TABLES, 
                            LF_NUM_BYTES,
                            STREAM_ID_WIDTH))

    val address_tag = UInt(ADDRESS_TAG_WIDTH.W)
    val requested   = Bool()

}





/**
  * 
  *
  * @param LRQ_NUM_REQUESTS  - number of requests within a same LRQ table
  * @param LRQ_NUM_TABLES    - number of Load Request Queue tables (associative ways)
  * @param LLB_NUM_BYTES     - number of bytes the Load Line Buffer data registers hold (must match Data Memory word size)
  * @param LF_NUM_TABLES     - number of Load FIFOs supported
  * @param LF_NUM_BYTES      - number of bytes each Load FIFO supports
  * @param STREAM_ID_WIDTH   - width of the Stream ID signal
  * @param ADDRESS_WIDTH     - width of the addresses produced by the Streaming Engine
  * @param ADDRESS_TAG_WIDTH - width of the address tag (number of bits excluding the offset bits)
  */
class LoadRequestQueue(
    val LRQ_NUM_REQUESTS:  Int, 
    val LRQ_NUM_TABLES:    Int, 
    val LLB_NUM_BYTES:     Int,
    val LF_NUM_TABLES:     Int,
    val LF_NUM_BYTES:      Int,
    val STREAM_ID_WIDTH:   Int,
    val ADDRESS_WIDTH:     Int, 
    val ADDRESS_TAG_WIDTH: Int)
extends Module {

    /* The set of input and output ports of the module */
    val io = IO(new Bundle {

        /* Control channel */
        val ctrl_reset = Input(Bool())

        /* Stream State channel */
        val ss_in_mmu_idx    = Input(UInt(log2Ceil(LF_NUM_TABLES).W))
        val ss_in_addr       = Input(UInt(ADDRESS_WIDTH.W))
        val ss_in_addr_valid = Input(Bool())
        val ss_in_width      = Input(UInt(2.W))
        val ss_out_trigger   = Output(Bool())                                      

        /* Request solver channel */
        val lf_in_ptr             = Input(UInt(log2Ceil(LF_NUM_BYTES).W))             
        val lf_in_ready           = Input(Bool())                                         
        val lf_out_req_width      = Output(UInt(2.W))
        val lf_out_req_mmu_idx    = Output(UInt(log2Ceil(LF_NUM_TABLES).W))
        val lf_out_req_offset_lf  = Output(UInt(log2Ceil(LF_NUM_BYTES).W))
        val lf_out_req_offset_llb = Output(UInt(log2Ceil(LLB_NUM_BYTES).W))
        val lf_out_req_valid      = Output(Bool())

        /* Data request channel */
        val req_llb_out_valid    = Output(Bool())                                        
        val req_llb_out_addr_tag = Output(UInt(ADDRESS_TAG_WIDTH.W))                     
        val req_llb_in_ready     = Input(Bool())                                        
        
        /* Request solver channel */
        val data_llb_out_clear   = Output(Bool())                                       
        val data_llb_in_addr_tag = Input(UInt(ADDRESS_TAG_WIDTH.W))                    
        val data_llb_in_valid    = Input(Bool())   

        /* Performance counters */
        val hpc_lmmu_stall_llb = Output(UInt(32.W))                 

    })



    /* The Load Request Queue consists of multiple tables
     * which will buffer outstanding data requests. When the
     * Stream State provides a new address, that request is 
     * registered in the LRQ. As soon as the data is available,
     * the requests are solved and the data copied to the
     * Load FIFOs
     */
    val lrq_reg = Reg(Vec(LRQ_NUM_TABLES, 
                    new LRQ_RequestTable_Bundle(
                        LRQ_NUM_REQUESTS,
                        LRQ_NUM_TABLES,
                        LLB_NUM_BYTES,
                        LF_NUM_TABLES, 
                        LF_NUM_BYTES,
                        STREAM_ID_WIDTH,
                        ADDRESS_TAG_WIDTH)))



    /* Declaring hardware performance counters regarding the
     * number of addresses received and the number of stalls
     */
    val hpc_lmmu_stall_llb = Reg(UInt(32.W))
                        




    /* Declaring internal wires */
    val ss_in_addr_tag        = Wire(UInt(ADDRESS_TAG_WIDTH.W))
    val ss_in_llb_offset      = Wire(UInt(log2Ceil(LLB_NUM_BYTES).W))
    val ss_out_trigger        = Wire(Bool())

    val lf_out_req_width      = Wire(UInt(2.W))
    val lf_out_req_mmu_idx    = Wire(UInt(log2Ceil(LF_NUM_TABLES).W))
    val lf_out_req_offset_lf  = Wire(UInt(log2Ceil(LF_NUM_BYTES).W))
    val lf_out_req_offset_llb = Wire(UInt(log2Ceil(LLB_NUM_BYTES).W))
    val lf_out_req_valid      = Wire(Bool())

    val req_llb_out_valid     = Wire(Bool())                                        
    val req_llb_out_addr_tag  = Wire(UInt(ADDRESS_TAG_WIDTH.W))  

    val lrq_config_tables     = Wire(Vec(LRQ_NUM_TABLES, Bool()))

    val data_llb_out_clear    = Wire(Bool())

    val nr_free_table_vec     = Wire(Vec(LRQ_NUM_TABLES, Bool())) 
    val nr_free_table_valid   = Wire(Bool())                                        
    val nr_requested_vec      = Wire(Vec(LRQ_NUM_TABLES, Bool()))   
    val nr_requested_valid    = Wire(Bool())
    val nr_empty_table_vec    = Wire(Vec(LRQ_NUM_TABLES, Bool()))    
    val nr_empty_table_valid  = Wire(Bool())     

    val nd_table_vec          = Wire(Vec(LRQ_NUM_TABLES, Bool()))
    val nd_table_valid        = Wire(Bool())

    /* An arbiter selects unrequested but valid memory addresses from
     * the Load Request Queue to the Load Line Buffer. When ready,
     * the Load Line Buffer will accept the address and configure
     * a new table
     */
	val ARBITER_LLB = Module(new RoundRobinArbiter(LRQ_NUM_TABLES))
	val arbiter_llb_trigger = Wire(Bool())                                 
	val arbiter_llb_input   = Wire(Vec(LRQ_NUM_TABLES, Bool()))            
	val arbiter_llb_output  = Wire(UInt(log2Ceil(LRQ_NUM_TABLES).W))  





    /* The Load Request Queue will free all tables that no longer
     * have valid outstanding requests */
    for (i <- 0 until LRQ_NUM_TABLES) {
        val lrq_valid_requests = Wire(Vec(LRQ_NUM_REQUESTS, Bool()))

        for (j <- 0 until LRQ_NUM_REQUESTS) {
            lrq_valid_requests(j) := lrq_reg(i).requests(j).valid
        }

        lrq_config_tables(i) := lrq_valid_requests.reduce(_ || _)
    }

    



    /* Asserting the default values for internal wires related to
    * incoming generated addresses from the Stream State
    */
    ss_in_addr_tag   := io.ss_in_addr >> log2Ceil(LLB_NUM_BYTES)
    ss_in_llb_offset := io.ss_in_addr(log2Ceil(LLB_NUM_BYTES) - 1, 0)
    ss_out_trigger   := false.B
    
    

    

    /* The Load Request Queue will register every valid address
     * from the Stream State as a outstanding/pending request.
     * Since each table can hold multiple request for a matching
     * address tag, the module will prioritize inserting new
     * requests in open tables. Otherwise it will configure a
     * new table.
     */
    for (i <- 0 until LRQ_NUM_TABLES) {
        nr_free_table_vec(i)  := lrq_reg(i).address_tag === ss_in_addr_tag &&  
                                 lrq_config_tables(i) &&            
                                !lrq_reg(i).requests.forall(_.valid)            

        nr_requested_vec(i)   := lrq_reg(i).address_tag === ss_in_addr_tag &&  
                                 lrq_config_tables(i) &&
                                 lrq_reg(i).requested

        nr_empty_table_vec(i) := !lrq_config_tables(i)
    }

    nr_free_table_valid  := nr_free_table_vec.reduce(_ || _)
    nr_requested_valid   := nr_requested_vec.reduce(_ || _)
    nr_empty_table_valid := nr_empty_table_vec.reduce(_ || _)





    /* The Load FIFO is ready to enqueue the new address */
    /* 1. Will append to an existing table
     * 2. Will configure a new table
     * 3. Won't do anything, the LRQ is full and can't accept the request
     */
    when (io.ss_in_addr_valid && io.lf_in_ready) {

        /* There is an open table (table with free request slots) */
        when (nr_free_table_valid) {
            
            val table_idx     = Wire(UInt(log2Ceil(LRQ_NUM_TABLES).W))
            val request_idx   = Wire(UInt(log2Ceil(LRQ_NUM_REQUESTS).W))
            val request_empty = Wire(Vec(LRQ_NUM_REQUESTS, Bool()))

            /* Insert the new address in the first non-full table */
            table_idx := PriorityEncoder(nr_free_table_vec)
            for (i <- 0 until LRQ_NUM_REQUESTS) {
                request_empty(i) := !lrq_reg(table_idx).requests(i).valid                           
            }
            request_idx := PriorityEncoder(request_empty)                                           

            /* Update the registers with the new request information */
            lrq_reg(table_idx).requests(request_idx).offset_llb := ss_in_llb_offset
            lrq_reg(table_idx).requests(request_idx).offset_lf  := io.lf_in_ptr
            lrq_reg(table_idx).requests(request_idx).width_     := io.ss_in_width
            lrq_reg(table_idx).requests(request_idx).mmu_idx    := io.ss_in_mmu_idx
            lrq_reg(table_idx).requests(request_idx).valid      := true.B

            ss_out_trigger := false.B
            
        }
        /* There are non-configured tables. Configures a new one */
        .elsewhen (nr_empty_table_valid) {

            val table_idx = Wire(UInt(log2Ceil(LRQ_NUM_TABLES).W))

            /* Insert the new address in the first non-configured table */
            table_idx := PriorityEncoder(nr_empty_table_vec)

            /* Update the registers with the new request information */
            lrq_reg(table_idx).requests(0).offset_llb := ss_in_llb_offset
            lrq_reg(table_idx).requests(0).offset_lf  := io.lf_in_ptr
            lrq_reg(table_idx).requests(0).width_     := io.ss_in_width
            lrq_reg(table_idx).requests(0).mmu_idx    := io.ss_in_mmu_idx
            lrq_reg(table_idx).requests(0).valid      := true.B

            /* Configure the table and the others request entries */
            for (i <- 1 until LRQ_NUM_REQUESTS) {
                lrq_reg(table_idx).requests(i).offset_llb := DontCare
                lrq_reg(table_idx).requests(i).offset_lf  := DontCare
                lrq_reg(table_idx).requests(i).width_     := DontCare
                lrq_reg(table_idx).requests(i).mmu_idx    := DontCare
                lrq_reg(table_idx).requests(i).valid      := false.B
            }

            lrq_reg(table_idx).requested   := nr_requested_valid
            lrq_reg(table_idx).address_tag := ss_in_addr_tag
            
            ss_out_trigger := false.B

        }
        /* Needs to switch to a different stream because the current
         * request cannot be accepted by the Load MMU
         */
        .otherwise {                                                                             
            ss_out_trigger := true.B
        }

    }


    
    
    


    /* The Load Request Queue will be fed with valid data from
     * the Load Line Buffer to solve outstanding requests.
     * The LRQ finds the tables of the corresponding address tag
     * and solves one pending request per cycle
     */

     /* Default values of the internal wires regarding 
      * the request solving information
      */
    lf_out_req_width      := DontCare
    lf_out_req_mmu_idx    := DontCare
    lf_out_req_offset_lf  := DontCare
    lf_out_req_offset_llb := DontCare
    lf_out_req_valid      := false.B

    data_llb_out_clear    := false.B

    /* Search for tables in the LRQ with the same tag of the
     * row of data bytes provided by the LLB
     */
    for (i <- 0 until LRQ_NUM_TABLES) {
        nd_table_vec(i) := lrq_reg(i).address_tag === io.data_llb_in_addr_tag &&
                           lrq_config_tables(i)
    }
    nd_table_valid := nd_table_vec.reduce(_ || _)





    /* When there is valid data and valid outstanding requests, one
     * is selected and the correct data is copied to the Load FIFO
     */
    when (io.data_llb_in_valid) {

        /* The LRQ has tables with valid unsolved requests */
        when (nd_table_valid) {

            val table_idx       = Wire(UInt(log2Ceil(LRQ_NUM_TABLES).W))                
            val request_idx     = Wire(UInt(log2Ceil(LRQ_NUM_REQUESTS).W))
            val request_valid   = Wire(Vec(LRQ_NUM_REQUESTS, Bool()))                                        
            
            /* Fetch a request from the first valid table */
            table_idx := PriorityEncoder(nd_table_vec)
            for (i <- 0 until LRQ_NUM_REQUESTS) {
                request_valid(i) := lrq_reg(table_idx).requests(i).valid
            }
            request_idx := PriorityEncoder(request_valid)
            
            /* Update the wires with the request information, which will be processed by the Load FIFO */
            lf_out_req_offset_llb := lrq_reg(table_idx).requests(request_idx).offset_llb              
            lf_out_req_offset_lf  := lrq_reg(table_idx).requests(request_idx).offset_lf
            lf_out_req_width      := lrq_reg(table_idx).requests(request_idx).width_
            lf_out_req_mmu_idx    := lrq_reg(table_idx).requests(request_idx).mmu_idx
            lf_out_req_valid      := lrq_reg(table_idx).requests(request_idx).valid

            /* Free the resources of the selected request */
            lrq_reg(table_idx).requests(request_idx).offset_llb := DontCare                                
            lrq_reg(table_idx).requests(request_idx).offset_lf  := DontCare
            lrq_reg(table_idx).requests(request_idx).width_     := DontCare
            lrq_reg(table_idx).requests(request_idx).mmu_idx    := DontCare
            lrq_reg(table_idx).requests(request_idx).valid      := false.B

        }

        /* There are no requests, meaning the row of bytes can be discarded */
        .otherwise {
            data_llb_out_clear := true.B 
        }

    }





    /* An arbiter selects unrequested but valid memory addresses from
     * the Load Request Queue to the Load Line Buffer. When ready,
     * the Load Line Buffer will accept the address and configure
     * a new table
     */
	for (i <- 0 until LRQ_NUM_TABLES) {
	    arbiter_llb_input(i) := lrq_config_tables(i) && !lrq_reg(i).requested				
	}

	arbiter_llb_trigger := !lrq_config_tables(arbiter_llb_output) ||
                           (lrq_config_tables(arbiter_llb_output) && lrq_reg(arbiter_llb_output).requested)

	ARBITER_LLB.io.reset   := io.ctrl_reset
	ARBITER_LLB.io.trigger := arbiter_llb_trigger
	ARBITER_LLB.io.input   := arbiter_llb_input
	arbiter_llb_output     := ARBITER_LLB.io.output





    /* The Load Line Buffer is ready to receive new requests and 
     * the Load Request Queue has valid but unrequested addresses
     */
    req_llb_out_addr_tag := lrq_reg(arbiter_llb_output).address_tag
    req_llb_out_valid    := lrq_config_tables(arbiter_llb_output) && !lrq_reg(arbiter_llb_output).requested

    when (io.req_llb_in_ready && !arbiter_llb_trigger) {
        
        for (i <- 0 until LRQ_NUM_TABLES) {
            when (lrq_reg(i).address_tag === lrq_reg(arbiter_llb_output).address_tag && lrq_config_tables(i)) {
                lrq_reg(i).requested := true.B
            }
        }

    }




    /* A performance counter registers the number of cycles where
     * the Load Request Queue had valid and unrequested addresses
     * but the Load Line Buffer was not ready
     */
    when (!arbiter_llb_trigger && !io.req_llb_in_ready) {
        hpc_lmmu_stall_llb := hpc_lmmu_stall_llb + 1.U
    }





    /* When the reset signal is activated, the registers of
     * the module will be updated to their default values
     */
    when (io.ctrl_reset) {
        lrq_reg.foreach {lrq =>
            lrq.requests.foreach {r =>
                r.offset_llb := 0.U
                r.offset_lf  := 0.U
                r.width_     := 0.U
                r.mmu_idx    := 0.U
                r.valid      := false.B
            }

            lrq.address_tag := 0.U
            lrq.requested   := false.B
        }

        hpc_lmmu_stall_llb := 0.U
    }





    /* Connecting the output ports to some of 
     * the internal wires of the module
     */
    io.ss_out_trigger        := ss_out_trigger

    io.lf_out_req_width      := lf_out_req_width   
    io.lf_out_req_mmu_idx    := lf_out_req_mmu_idx   
    io.lf_out_req_offset_lf  := lf_out_req_offset_lf                                   
    io.lf_out_req_offset_llb := lf_out_req_offset_llb 
    io.lf_out_req_valid      := lf_out_req_valid 

    io.req_llb_out_valid     := req_llb_out_valid    
    io.req_llb_out_addr_tag  := req_llb_out_addr_tag   

    io.data_llb_out_clear    := data_llb_out_clear

    io.hpc_lmmu_stall_llb    := hpc_lmmu_stall_llb

}





/**
  * Verilog generator application
  */
object LoadRequestQueue_Verilog extends App {

    /* Define the parameters */
        val LRQ_NUM_REQUESTS  = 8
        val LRQ_NUM_TABLES    = 16
        val LLB_NUM_BYTES     = 16
        val LF_NUM_TABLES     = 4
        val LF_NUM_BYTES      = 16
        val STREAM_ID_WIDTH   = 5
        val ADDRESS_WIDTH     = 32
        val ADDRESS_TAG_WIDTH = ADDRESS_WIDTH - log2Ceil(LLB_NUM_BYTES)


    val path = "output/LoadRequestQueue/"

    
    /* Generate verilog */
    (new ChiselStage).emitVerilog(
        new LoadRequestQueue(
            LRQ_NUM_REQUESTS,
            LRQ_NUM_TABLES,
            LLB_NUM_BYTES,
            LF_NUM_TABLES, 
            LF_NUM_BYTES,
            STREAM_ID_WIDTH,
            ADDRESS_WIDTH,
            ADDRESS_TAG_WIDTH),
        Array("--target-dir", path))

}