package streamingengine

import chisel3._
import chisel3.util._





/**
  * 
  *
  * @param LLB_NUM_BYTES
  * @param ADDRESS_TAG_WIDTH
  */
class LLB_Table_Bundle(
    val LLB_NUM_BYTES:     Int,
    val ADDRESS_TAG_WIDTH: Int)
extends Bundle {

    /* The set of wires that define the bundle */
    val bytes       	= Vec(LLB_NUM_BYTES, UInt(8.W))
    val address_tag 	= UInt(ADDRESS_TAG_WIDTH.W)
    val config       	= Bool()                                   
    val valid       	= Bool()                                  

}





/**
  * 
  *
  * @param LLB_NUM_TABLES    - number of Data Memory words the Load Line Buffer can hold simultaneously
  * @param LLB_NUM_BYTES     - number of bytes the Load Line Buffer data registers hold (must match Data Memory word size)
  * @param ADDRESS_WIDTH     - width of the addresses produced by the Streaming Engine
  * @param ADDRESS_TAG_WIDTH - width of the address tag (number of bits excluding the offset bits)
  * @param AXI_R_DATA_WIDTH  - width of the AXI RDATA bus
  */
class LoadLineBuffer(
    val LLB_NUM_TABLES:    Int,
    val LLB_NUM_BYTES:     Int,
    val ADDRESS_WIDTH:     Int,
    val ADDRESS_TAG_WIDTH: Int,
    val AXI_R_DATA_WIDTH:  Int)
extends Module {

    /* Internal parameters, calculated using external ones */
    val LLB_OFFSET_WIDTH = ADDRESS_WIDTH - ADDRESS_TAG_WIDTH



    /* The set of input and output ports of the module */
    val io = IO(new Bundle {

        /* Control channel */
        val ctrl_reset = Input(Bool())
        
        /* Data request channel */
        val req_lrq_in_valid     = Input(Bool())
        val req_lrq_in_addr_tag  = Input(UInt(ADDRESS_TAG_WIDTH.W))
        val req_lrq_out_ready    = Output(Bool())
        
        /* Request solver channel */
        val data_lrq_in_clear     = Input(Bool())
        val data_lrq_out_addr_tag = Output(UInt(ADDRESS_TAG_WIDTH.W))
        val data_lf_out_bytes     = Output(Vec(LLB_NUM_BYTES, UInt(8.W)))
        val data_out_valid        = Output(Bool()) 

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
        val hpc_ops_load = Output(UInt(16.W)) 

    })



    /* State nomenclature */
	val STATE_WAIT_ARREADY = 0.U
	val STATE_WAIT_RVALID  = 1.U





    /* The Load Line Buffer has several tables containing
     * rows of bytes which will be loaded from the Data
     * Memory module
     */
    val llb_reg    = Reg(Vec(LLB_NUM_TABLES, new LLB_Table_Bundle (LLB_NUM_BYTES, ADDRESS_TAG_WIDTH)))

    /* Some registers are required for the transactions
     * with the memory system
     */
    val load_state = Reg(UInt(1.W))
    val load_ptr   = Reg(UInt(log2Ceil(LLB_NUM_BYTES).W))
    val load_okay  = Reg(Bool())

    /* Hardware Performance Counter: number of load requests */
    val hpc_ops_load = Reg(UInt(16.W)) 





    /* Internal wire declaration */                                                 
    val req_lrq_out_ready       = Wire(Bool())

    val data_lrq_out_addr_tag   = Wire(UInt(ADDRESS_TAG_WIDTH.W))
    val data_lf_out_bytes       = Wire(Vec(LLB_NUM_BYTES, UInt(8.W)))
    val data_out_valid          = Wire(Bool()) 

    val axi_ar_addr             = Wire(UInt(ADDRESS_WIDTH.W))
    val axi_ar_valid            = Wire(Bool())
    val axi_ar_len              = Wire(UInt(8.W)) // ARLEN[7:0]
    val axi_ar_size             = Wire(UInt(3.W)) // ARSIZE[2:0]
    val axi_ar_burst            = Wire(UInt(1.W)) // ARBURST[1:0]
    val axi_r_ready             = Wire(Bool())

    val nr_empty_table_vec   = Wire(Vec(LLB_NUM_TABLES, Bool()))
    val nr_empty_table_idx   = Wire(UInt(log2Ceil(LLB_NUM_TABLES).W))
    val nr_empty_table_valid = Wire(Bool())     



    /* An arbiter selects from the Load Line Buffer one configured
     * but not yet valid table. The selected table will initiate
     * a load request to the data memory
     */
	val ARBITER_DM = Module(new RoundRobinArbiter(LLB_NUM_TABLES))
    val arbiter_dm_trigger = Wire(Bool())
	val arbiter_dm_input   = Wire(Vec(LLB_NUM_TABLES, Bool()))
	val arbiter_dm_output  = Wire(UInt(log2Ceil(LLB_NUM_TABLES).W))

    
    
    /* An arbiter routes valid tables of the Load Line Buffer to
     * the Load Request Queue and Load FIFO. The data will be used
     * to resolve enqueued requests
     */
	val ARBITER_LRQ = Module(new RoundRobinArbiter(LLB_NUM_TABLES))
    val arbiter_lrq_trigger = Wire(Bool())
	val arbiter_lrq_input   = Wire(Vec(LLB_NUM_TABLES, Bool()))
	val arbiter_lrq_output  = Wire(UInt(log2Ceil(LLB_NUM_TABLES).W))





    




    /* An arbiter selects from the Load Line Buffer one configured
     * but not yet valid table. The selected table will initiate
     * a load request to the data memory
     */

	for (i <- 0 until LLB_NUM_TABLES) {
	    arbiter_dm_input(i) := llb_reg(i).config && !llb_reg(i).valid			
	}

    /* Change the selected table when the current one is not configured or
     * when it is configured and the data is valid, meaning the load request
     * has already been fulfilled
     */
    arbiter_dm_trigger := !llb_reg(arbiter_dm_output).config || (llb_reg(arbiter_dm_output).config && llb_reg(arbiter_dm_output).valid)

	ARBITER_DM.io.reset   := io.ctrl_reset
	ARBITER_DM.io.input   := arbiter_dm_input
	ARBITER_DM.io.trigger := arbiter_dm_trigger
	arbiter_dm_output     := ARBITER_DM.io.output





    /* The Load Line Buffer communicates with the Data
     * Memory thought the AXI protocol. The default values
     * are here defined
     */
    axi_ar_addr  := DontCare
    axi_ar_valid := false.B
    axi_ar_len   := (((LLB_NUM_BYTES * 8) / AXI_R_DATA_WIDTH) - 1).U   // NUM_TRANSACTIONS = ARLEN + 1
    axi_ar_size  := (log2Ceil(AXI_R_DATA_WIDTH / 8)).U                 // BEAT SIZE IN BYTES = 2 ^ ARSIZE
    axi_ar_burst := "b01".U                                            // INCREMENTAL BURST
    axi_r_ready  := false.B



    /* A finite state machine manages the AXI protocol
     * transactions. It transitions to different states
     * as data is being accepted and communicated with
     * the Data Memory
     */
	switch (load_state) {

        /* STATE: waiting for the ARREADY AXI signal */
		is (STATE_WAIT_ARREADY) {
			
			when (io.axi_ar_ready && axi_ar_valid) {
                load_ptr   := 0.U
                load_okay  := true.B
                load_state := STATE_WAIT_RVALID

                /* Memory controller accepted a new load request. Increment the
                 * performance counter regarding the total number of load requests
                 */
                hpc_ops_load := hpc_ops_load + 1.U
            }



            /* Reassignment of AXI internal signals */
            axi_ar_addr  := llb_reg(arbiter_dm_output).address_tag.asUInt << LLB_OFFSET_WIDTH
            axi_ar_valid := llb_reg(arbiter_dm_output).config && !llb_reg(arbiter_dm_output).valid
            axi_r_ready  := false.B

		}


        /* STATE: waiting for the RVALID AXI signals */
		is (STATE_WAIT_RVALID) {

            when (io.axi_r_valid) {

                /* Transfer the data bytes to the respective table of
                 * the Load Line Buffer
                 */
                for (i <- 0 until (AXI_R_DATA_WIDTH / 8)) {
                    llb_reg(arbiter_dm_output).bytes(load_ptr + i.U) := io.axi_r_data(8 * (i + 1) - 1, 8 * i)
                }
                load_ptr := load_ptr + (AXI_R_DATA_WIDTH / 8).U



                /* Detect if the current transaction was successful */
                when (io.axi_r_resp =/= "b00".U) {                           
                    load_okay := false.B
                }



                /* When the last beat of the burst is sent the respective
                 * table of the Load Line Buffer is set to valid if there
                 * were no errors during data transactions */
                when (io.axi_r_last) {
                    when (load_okay && io.axi_r_resp === "b00".U) {
                        llb_reg(arbiter_dm_output).valid := true.B
                    }
                    
                    load_state := STATE_WAIT_ARREADY
                }
                  
            }



            /* Reassignment of AXI internal signals */
            axi_ar_addr  := DontCare
            axi_ar_valid := false.B
            axi_r_ready  := true.B

		}

	}





    /* Search for empty (non-configured) tables of the Load Line Buffer */
    for (i <- 0 until LLB_NUM_TABLES) {
        nr_empty_table_vec(i) := !llb_reg(i).config
    }
    nr_empty_table_idx   := PriorityEncoder(nr_empty_table_vec)
    nr_empty_table_valid := nr_empty_table_vec.reduce(_ || _)



    /* The Load Request Queue is providing a new address which has
     * not yet been requested to the memory system. The Load Line
     * Buffer accepts the request by configuring one empty table
     */
    when (nr_empty_table_valid && io.req_lrq_in_valid) {
        llb_reg(nr_empty_table_idx).bytes.foreach {x => x := 0.U}
        llb_reg(nr_empty_table_idx).address_tag := io.req_lrq_in_addr_tag
        llb_reg(nr_empty_table_idx).config      := true.B
        llb_reg(nr_empty_table_idx).valid       := false.B

        req_lrq_out_ready := true.B
    }
    .otherwise {
        req_lrq_out_ready := false.B
    }





    /* An arbiter routes valid tables of the Load Line Buffer to
     * the Load Request Queue and Load FIFO. The data will be used
     * to resolve enqueued requests
     */

	for (i <- 0 until LLB_NUM_TABLES) {
	    arbiter_lrq_input(i) := llb_reg(i).config && llb_reg(i).valid			
	}

    arbiter_lrq_trigger := io.data_lrq_in_clear || !(llb_reg(arbiter_lrq_output).config && llb_reg(arbiter_lrq_output).valid)

	ARBITER_LRQ.io.reset    := io.ctrl_reset
	ARBITER_LRQ.io.input    := arbiter_lrq_input
	ARBITER_LRQ.io.trigger  := arbiter_lrq_trigger
	arbiter_lrq_output      := ARBITER_LRQ.io.output

    data_lf_out_bytes     := llb_reg(arbiter_lrq_output).bytes
    data_lrq_out_addr_tag := llb_reg(arbiter_lrq_output).address_tag
    data_out_valid        := llb_reg(arbiter_lrq_output).config && llb_reg(arbiter_lrq_output).valid

    /* The Load Request Queue has no valid and pending requests with
     * the address tag provided by the Load Line Buffer. The resources
     * and, therefore, deallocated
     */
    when (io.data_lrq_in_clear) {
        llb_reg(arbiter_lrq_output).config      := false.B
        llb_reg(arbiter_lrq_output).valid       := false.B
        llb_reg(arbiter_lrq_output).address_tag := 0.U
    }





    /* When the reset signal is activated, the registers of
     * the module will be updated to their default values
     */
    when (io.ctrl_reset) {

        llb_reg.foreach {llb =>
            llb.bytes.foreach {x => x := 0.U}
            llb.address_tag := 0.U
            llb.config      := false.B
            llb.valid       := false.B
        }

        load_ptr   := 0.U
        load_okay  := false.B
        load_state := 0.U         

        hpc_ops_load := 0.U
    }





    /* Connecting the output ports to some of 
     * the internal wires of the module
     */
    io.req_lrq_out_ready     := req_lrq_out_ready

    io.data_lrq_out_addr_tag := data_lrq_out_addr_tag
    io.data_lf_out_bytes     := data_lf_out_bytes
    io.data_out_valid        := data_out_valid

    io.axi_ar_addr  := axi_ar_addr
    io.axi_ar_valid := axi_ar_valid
    io.axi_ar_len   := axi_ar_len
    io.axi_ar_size  := axi_ar_size
    io.axi_ar_burst := axi_ar_burst

    io.axi_r_ready  := axi_r_ready

    io.hpc_ops_load := hpc_ops_load

}
