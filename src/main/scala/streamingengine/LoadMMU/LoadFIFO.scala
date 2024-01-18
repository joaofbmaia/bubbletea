import chisel3._
import chisel3.util._
import chisel3.stage.ChiselStage





/**
  * 
  *
  * @param STREAM_NUM_DIMS
  * @param VEC_NUM_BYTES
  */
class loadVector_Bundle(
    val STREAM_NUM_DIMS: Int,
    val VEC_NUM_BYTES:   Int)
extends Bundle {

    /* The set of wires that define the bundle */
    val req_data      = Vec(VEC_NUM_BYTES, UInt(8.W))
    val req_predicate = Vec(VEC_NUM_BYTES, Bool())
    val req_valid     = Vec(VEC_NUM_BYTES, Bool())

    val reserved      = Bool()
    val completed     = UInt(STREAM_NUM_DIMS.W)

}





/**
  * 
  *
  * @param STREAM_NUM_DIMS
  * @param STREAM_ID_WIDTH
  * @param VEC_NUM_BYTES
  * @param LF_NUM_BYTES
  * @param ADDRESS_WIDTH
  */
class loadFIFO_Bundle(
        val STREAM_NUM_DIMS: Int,
        val STREAM_ID_WIDTH: Int,
        val VEC_NUM_BYTES:   Int,
        val LF_NUM_BYTES:    Int,
        val ADDRESS_WIDTH:   Int)
extends Bundle {

    /* Internal parameters, calculated using external ones */
    val LMMU_NUM_VECS = LF_NUM_BYTES / VEC_NUM_BYTES
    

    
    /* The set of wires that define the bundle */
    val vecs = Vec(LMMU_NUM_VECS, 
                    new loadVector_Bundle(
                        STREAM_NUM_DIMS, 
                        VEC_NUM_BYTES))
    
    val ptr_vec_read   = UInt(log2Ceil(LMMU_NUM_VECS).W)
    val idx_write_vec  = UInt(log2Ceil(LMMU_NUM_VECS).W)
	val idx_write_byte = UInt(log2Ceil(VEC_NUM_BYTES).W)

    val ss_done = Bool()

	val stream = UInt(STREAM_ID_WIDTH.W)
	val width_ = UInt(2.W)
	val valid  = Bool()

}




/**
  * 
  *
  * @param STREAM_NUM_DIMS  - number of dimensions the Stream Engine supports
  * @param STREAM_ID_WIDTH  - width of the Stream ID signal
  * @param VEC_NUM_BYTES    - number of bytes in a vector
  * @param LF_NUM_TABLES    - number of Load FIFOs supported
  * @param LF_NUM_BYTES     - number of bytes each Load FIFO supports
  * @param LLB_NUM_BYTES    - number of bytes the Load Line Buffer data registers hold (must match Data Memory word size)
  * @param ADDRESS_WIDTH    - width of addresses produced by the Streaming Engine
  * @param NUM_SRC_OPERANDS - number of source operands communicating with the Streaming Engine
  */
class LoadFIFO(val STREAM_NUM_DIMS:  Int,
               val STREAM_ID_WIDTH:  Int,
               val VEC_NUM_BYTES:    Int,
               val LF_NUM_TABLES:    Int,
               val LF_NUM_BYTES:     Int,
               val LLB_NUM_BYTES:    Int,
               val ADDRESS_WIDTH:    Int,
               val NUM_SRC_OPERANDS: Int)
extends Module {
    
    /* Internal parameters, calculated using external ones */
    val VEC_WIDTH     = VEC_NUM_BYTES * 8
    val LMMU_NUM_VECS = LF_NUM_BYTES / VEC_NUM_BYTES



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
        val ss_in_addr_valid = Input(Bool())
        val ss_in_completed  = Input(UInt(STREAM_NUM_DIMS.W))
        val ss_in_vectorize  = Input(Bool())
		val ss_in_last       = Input(Bool())
        val ss_out_trigger   = Output(Bool())
		
        /* Load Request Queue channel */
        val lrq_in_ready = Input(Bool())
        val lrq_out_ptr  = Output(UInt(log2Ceil(LF_NUM_BYTES).W))
        
        /* Request solver channel */
        val data_llb_in_bytes      = Input(Vec(LLB_NUM_BYTES, UInt(8.W)))
        val data_lrq_in_width      = Input(UInt(2.W))
        val data_lrq_in_mmu_idx    = Input(UInt(log2Ceil(LF_NUM_TABLES).W))
        val data_lrq_in_offset_lf  = Input(UInt(log2Ceil(LF_NUM_BYTES).W))
        val data_lrq_in_offset_llb = Input(UInt(log2Ceil(LLB_NUM_BYTES).W))
        val data_lrq_in_valid      = Input(Bool())

        /* Source operands data channel */
        val rs_in_streamid   = Input(Vec(NUM_SRC_OPERANDS, UInt(STREAM_ID_WIDTH.W)))
        val rs_in_ready      = Input(Vec(NUM_SRC_OPERANDS, Bool()))
        val rs_out_valid     = Output(Vec(NUM_SRC_OPERANDS, Bool()))
        val rs_out_done      = Output(Vec(NUM_SRC_OPERANDS, Bool()))
        val rs_out_vecdata   = Output(Vec(NUM_SRC_OPERANDS, UInt(VEC_WIDTH.W)))
        val rs_out_predicate = Output(Vec(NUM_SRC_OPERANDS, UInt(VEC_NUM_BYTES.W)))
        val rs_out_completed = Output(Vec(NUM_SRC_OPERANDS, UInt(STREAM_NUM_DIMS.W)))

    })  



    /* The Load FIFO module is a collection of FIFOs and each
     * hold information of a single load stream. This includes
     * the addresses of the memory access pattern and the respective
     * data elements. Meta-information regarding each stream is
     * also saved and some internal pointers are required aswell
     */
    val lf_reg = Reg(Vec(LF_NUM_TABLES, new loadFIFO_Bundle(
                                            STREAM_NUM_DIMS,
                                            STREAM_ID_WIDTH,
                                            VEC_NUM_BYTES,
                                            LF_NUM_BYTES,
                                            ADDRESS_WIDTH)))

    



    /* Internal wire declaration */
    val ss_out_trigger = Wire(Bool())

    val lrq_out_ptr = Wire(UInt(log2Ceil(LF_NUM_BYTES).W))
    
	val cfg_empty_table_vec   = Wire(Vec(LF_NUM_TABLES, Bool()))
	val cfg_empty_table_idx   = Wire(UInt(log2Ceil(LF_NUM_TABLES).W))
	val cfg_empty_table_valid = Wire(Bool())

    val cfg_out_ready  	= Wire(Bool())
	val cfg_out_mmu_idx = Wire(UInt(log2Ceil(LF_NUM_TABLES).W))

    val table_idx           = Wire(UInt(log2Ceil(LF_NUM_TABLES).W))    
    val idx_write_byte      = Wire(UInt(log2Ceil(VEC_NUM_BYTES).W))
    val idx_write_vec       = Wire(UInt(log2Ceil(LMMU_NUM_VECS).W))
    val next_idx_write_byte = Wire(UInt(log2Ceil(VEC_NUM_BYTES).W))
    val next_idx_write_vec  = Wire(UInt(log2Ceil(LMMU_NUM_VECS).W))

    val req_idx_fifo = Wire(UInt(log2Ceil(LF_NUM_TABLES).W))
    val req_idx_byte = Wire(UInt(log2Ceil(VEC_NUM_BYTES).W))
    val req_idx_vec  = Wire(UInt(log2Ceil(LMMU_NUM_VECS).W))

    val rs_out_valid     = Wire(Vec(NUM_SRC_OPERANDS, Bool()))
    val rs_out_done      = Wire(Vec(NUM_SRC_OPERANDS, Bool()))
    val rs_out_vecdata   = Wire(Vec(NUM_SRC_OPERANDS, UInt(VEC_WIDTH.W)))
    val rs_out_predicate = Wire(Vec(NUM_SRC_OPERANDS, UInt(VEC_NUM_BYTES.W)))
    val rs_out_completed = Wire(Vec(NUM_SRC_OPERANDS, UInt(STREAM_NUM_DIMS.W)))






	/* The Load FIFO module keeps track of non-configured FIFOs.
	 * When a configuration of a load stream arrives, the module
	 * selects one of the tables and initializes the registers
	 */
	for (i <- 0 until LF_NUM_TABLES) {
		cfg_empty_table_vec(i) := !lf_reg(i).valid
	}
	cfg_empty_table_idx   := PriorityEncoder(cfg_empty_table_vec)
	cfg_empty_table_valid := cfg_empty_table_vec.reduce(_ || _)
	


	/* There are non-configured FIFOs and a new load
	 * stream is being configured
	 */
	when (io.cfg_in_we && cfg_empty_table_valid) {

        lf_reg(cfg_empty_table_idx).vecs.foreach {v =>
                v.req_data.foreach      {_ => 0.U}
                v.req_predicate.foreach {_ => false.B}
                v.req_valid.foreach     {_ => true.B}    

                v.reserved  := false.B
                v.completed := 0.U
        }

        lf_reg(cfg_empty_table_idx).ptr_vec_read   := 0.U
        lf_reg(cfg_empty_table_idx).idx_write_vec  := 0.U
        lf_reg(cfg_empty_table_idx).idx_write_byte := 0.U

        lf_reg(cfg_empty_table_idx).ss_done := false.B
        lf_reg(cfg_empty_table_idx).stream  := io.cfg_in_stream
        lf_reg(cfg_empty_table_idx).width_  := io.cfg_in_width
        lf_reg(cfg_empty_table_idx).valid   := true.B
	}
    
	cfg_out_ready 	:= cfg_empty_table_valid
	cfg_out_mmu_idx := cfg_empty_table_idx





    /* The Load FIFO will be receiving valid addresses from the 
     * Stream State and will save them in the respetive load FIFO.
     * Since an address is associated with a memory load request,
     * that address can only be accepted if the request can also
     * be accepted in the Load Request Queue module
     */    
	table_idx           := io.ss_in_mmu_idx

    idx_write_byte      := lf_reg(table_idx).idx_write_byte
    idx_write_vec       := lf_reg(table_idx).idx_write_vec

    next_idx_write_vec  := DontCare
    next_idx_write_byte := DontCare

	ss_out_trigger := !lf_reg(table_idx).valid || (lf_reg(table_idx).valid && lf_reg(table_idx).vecs(idx_write_vec).reserved)

    lrq_out_ptr    := idx_write_vec ## idx_write_byte 

    

    /* The address is valid and both the Load FIFO and the
     * Load Request Queue are ready to accept the address
     */
    when (io.ss_in_valid && io.lrq_in_ready && !ss_out_trigger) {
        
        /* Define how the address write pointer is updated */
        when (io.ss_in_vectorize) {         
            
            /* When the Stream State signals vectorization, the
             * current vector is closed by advancing the address
             * pointer to the next vector, not allowing this way
             * the predication of more bytes in the current vector
             */
            next_idx_write_vec  := idx_write_vec + 1.U
            next_idx_write_byte := 0.U

        }
        .elsewhen (io.ss_in_addr_valid) {

            /* When there is no vectorization, just increment the
             * address write pointer according to the element width
             */
            switch(lf_reg(table_idx).width_) {
                is("b00".U) {next_idx_write_byte := idx_write_byte + 1.U}
                is("b01".U) {next_idx_write_byte := idx_write_byte + 2.U}
                is("b10".U) {next_idx_write_byte := idx_write_byte + 4.U}
                is("b11".U) {next_idx_write_byte := idx_write_byte + 8.U}
            }

            /* A new vector will be complete when the byte pointer
             * reaches zero. Updates the vector pointer accordingly
             */
            when (next_idx_write_byte === 0.U) {
                next_idx_write_vec := idx_write_vec + 1.U
            }
            .otherwise {
                next_idx_write_vec := idx_write_vec
            }

        }
        .otherwise {    

            next_idx_write_vec  := idx_write_vec
            next_idx_write_byte := idx_write_byte

        }



        /* Reserve the incoming address in the respective Load FIFO */
        when (io.ss_in_addr_valid) {        
            
            switch(lf_reg(table_idx).width_) {

                is("b00".U) { 
                    for (i <- 0 until 1) {
                        lf_reg(table_idx).vecs(idx_write_vec).req_predicate(idx_write_byte + i.U) := true.B
                        lf_reg(table_idx).vecs(idx_write_vec).req_valid(idx_write_byte + i.U) := false.B
                    }
                }

                is("b01".U) {
                    for (i <- 0 until 2) {
                        lf_reg(table_idx).vecs(idx_write_vec).req_predicate(idx_write_byte + i.U) := true.B
                        lf_reg(table_idx).vecs(idx_write_vec).req_valid(idx_write_byte + i.U) := false.B
                    }
                }

                is("b10".U) {
                    for (i <- 0 until 4) {
                        lf_reg(table_idx).vecs(idx_write_vec).req_predicate(idx_write_byte + i.U) := true.B
                        lf_reg(table_idx).vecs(idx_write_vec).req_valid(idx_write_byte + i.U) := false.B
                    }
                }

                is("b11".U) {
                    for (i <- 0 until 8) {
                        lf_reg(table_idx).vecs(idx_write_vec).req_predicate(idx_write_byte + i.U) := true.B
                        lf_reg(table_idx).vecs(idx_write_vec).req_valid(idx_write_byte + i.U) := false.B
                    }
                }

            }
    
        }

        lf_reg(table_idx).idx_write_vec  := next_idx_write_vec
        lf_reg(table_idx).idx_write_byte := next_idx_write_byte



        /* The state of completeness of each dimension of the memory access pattern
         * needs to be saved for branch control purposes (in the processing unit)
         */
        val vec_completed = Wire(UInt(STREAM_NUM_DIMS.W))
        vec_completed := lf_reg(table_idx).vecs(idx_write_vec).completed
        lf_reg(table_idx).vecs(idx_write_vec).completed := vec_completed | io.ss_in_completed



        /* Mark the current vector as reserved */
        when (next_idx_write_byte === 0.U) {
            lf_reg(table_idx).vecs(idx_write_vec).reserved := true.B
        }



        /* The last address will eventually be provided by
         * the Stream State, which needs to be registered
         */
        when (io.ss_in_last) {
            lf_reg(table_idx).ss_done := true.B
        }

    }
    




    /* As new valid data is being requested, so the outstanding requests
     * are solved by writting the correct data into the reserved load FIFO
     * slots
     */
    req_idx_fifo := io.data_lrq_in_mmu_idx
    req_idx_byte := io.data_lrq_in_offset_lf(log2Ceil(VEC_NUM_BYTES)-1, 0)
    req_idx_vec  := io.data_lrq_in_offset_lf >> log2Ceil(VEC_NUM_BYTES)

    when(io.data_lrq_in_valid) {

        switch(io.data_lrq_in_width) {
            is("b00".U) { 
                for (i <- 0 until 1) {
                    lf_reg(req_idx_fifo).vecs(req_idx_vec).req_data(req_idx_byte + i.U)  := io.data_llb_in_bytes(io.data_lrq_in_offset_llb + i.U)
                    lf_reg(req_idx_fifo).vecs(req_idx_vec).req_valid(req_idx_byte + i.U) := true.B
                }
            }

            is("b01".U) { 
                for (i <- 0 until 2) {
                    lf_reg(req_idx_fifo).vecs(req_idx_vec).req_data(req_idx_byte + i.U)  := io.data_llb_in_bytes(io.data_lrq_in_offset_llb + i.U)
                    lf_reg(req_idx_fifo).vecs(req_idx_vec).req_valid(req_idx_byte + i.U) := true.B
                }
            }

            is("b10".U) { 
                for (i <- 0 until 4) {
                    lf_reg(req_idx_fifo).vecs(req_idx_vec).req_data(req_idx_byte + i.U)  := io.data_llb_in_bytes(io.data_lrq_in_offset_llb + i.U)
                    lf_reg(req_idx_fifo).vecs(req_idx_vec).req_valid(req_idx_byte + i.U) := true.B
                }
            }

            is("b11".U) { 
                for (i <- 0 until 8) {
                    lf_reg(req_idx_fifo).vecs(req_idx_vec).req_data(req_idx_byte + i.U)  := io.data_llb_in_bytes(io.data_lrq_in_offset_llb + i.U)
                    lf_reg(req_idx_fifo).vecs(req_idx_vec).req_valid(req_idx_byte + i.U) := true.B
                }
            }
        }

    }

    



    /* The Load FIFO module communicates with the processing
     * unit by transfering vectors of data through an handshake
     * protocol
     */
    rs_out_valid.foreach     {x => x := false.B}
    rs_out_done.foreach      {x => x := false.B}
    rs_out_vecdata.foreach   {x => x := DontCare}
    rs_out_predicate.foreach {x => x := DontCare}
    rs_out_completed.foreach {x => x := DontCare}
    


    /* The Load FIFO establishes the communication for each
     * of the source operand through the respective ports
     */
    for (i <- 0 until NUM_SRC_OPERANDS) {    
        
        /* Some auxiliar internal wires */
        val rs_mask    = Wire(Vec(LF_NUM_TABLES, Bool()))
        val rs_idx     = Wire(UInt(log2Ceil(LF_NUM_TABLES).W))
        val rs_ptr_vec = Wire(UInt(log2Ceil(LF_NUM_BYTES/VEC_NUM_BYTES).W))
        val rs_valid   = Wire(Bool())                           
        


        /* For each source channel port, obtain information
         * regarding the readiness of the vector data
         */
        for (j <- 0 until LF_NUM_TABLES) {
            rs_mask(j)  := lf_reg(j).stream === io.rs_in_streamid(i) && lf_reg(j).valid
        }

        rs_idx := PriorityEncoder(rs_mask)

        rs_valid := lf_reg(rs_idx).vecs(rs_ptr_vec).reserved && 
                    lf_reg(rs_idx).vecs(rs_ptr_vec).req_valid.reduce(_ && _) && 
                    lf_reg(rs_idx).valid

        rs_ptr_vec := lf_reg(rs_idx).ptr_vec_read
                        
        rs_out_valid(i)     := rs_valid
        rs_out_vecdata(i)   := lf_reg(rs_idx).vecs(rs_ptr_vec).req_data.asUInt
        rs_out_predicate(i) := lf_reg(rs_idx).vecs(rs_ptr_vec).req_predicate.asUInt
        rs_out_completed(i) := lf_reg(rs_idx).vecs(rs_ptr_vec).completed

    



        /* When the handshake is asserted, clear the vector being 
         * consumed and update the read pointers
         */
        when (io.rs_in_ready(i) && rs_valid) {

            for (j <- 0 until VEC_NUM_BYTES) {
                lf_reg(rs_idx).vecs(rs_ptr_vec).req_data(j)      := DontCare
                lf_reg(rs_idx).vecs(rs_ptr_vec).req_predicate(j) := false.B
                lf_reg(rs_idx).vecs(rs_ptr_vec).req_valid(j)     := true.B
            }

            lf_reg(rs_idx).vecs(rs_ptr_vec).reserved := false.B
            lf_reg(rs_idx).vecs(rs_ptr_vec).completed := 0.U
            lf_reg(rs_idx).ptr_vec_read := rs_ptr_vec + 1.U



            /* Eventually all data will be consumed. When that 
             * happens, free the load FIFO 
			 */
            val clear_mmu_resources = Wire(Bool())
            clear_mmu_resources := lf_reg(rs_idx).ss_done && !lf_reg(rs_idx).vecs(rs_ptr_vec + 1.U).reserved

            when (clear_mmu_resources) {
                lf_reg(rs_idx).vecs.foreach {v =>
                        v.req_data.foreach      {x => x := 0.U}
                        v.req_predicate.foreach {x => x := false.B}
                        v.req_valid.foreach     {x => x := true.B}    

                        v.reserved := false.B
                        v.completed := 0.U
                }

                lf_reg(rs_idx).ptr_vec_read   := 0.U
                lf_reg(rs_idx).idx_write_vec  := 0.U
                lf_reg(rs_idx).idx_write_byte := 0.U
                lf_reg(rs_idx).ss_done        := false.B
                lf_reg(rs_idx).stream         := 0.U
                lf_reg(rs_idx).width_         := 0.U
                lf_reg(rs_idx).valid          := false.B

                /* Signal that the stream has ended */
                rs_out_done(i) := true.B
            }
            .otherwise {
                rs_out_done(i) := false.B
            }

        }

    }





    /* When the reset signal is activated, the registers of
     * the module will be updated to their default values
     */
	when (io.ctrl_reset) {
		lf_reg.foreach {lf =>

            lf.vecs.foreach {v =>
                v.req_data.foreach      {x => x := 0.U}
                v.req_predicate.foreach {x => x := false.B}
                v.req_valid.foreach     {x => x := true.B}    

                v.reserved  := false.B
                v.completed := 0.U
            }

            lf.ptr_vec_read   := 0.U
            lf.idx_write_vec  := 0.U
            lf.idx_write_byte := 0.U

            lf.ss_done := false.B
            lf.stream  := 0.U
            lf.width_  := 0.U
            lf.valid   := false.B

        }
	}





    /* Connecting the output ports to some of 
     * the internal wires of the module
     */
    io.cfg_out_ready   := cfg_out_ready
    io.cfg_out_mmu_idx := cfg_out_mmu_idx

    io.ss_out_trigger := ss_out_trigger

    io.lrq_out_ptr := lrq_out_ptr

    io.rs_out_valid     := rs_out_valid
    io.rs_out_done      := rs_out_done
    io.rs_out_vecdata   := rs_out_vecdata
    io.rs_out_predicate := rs_out_predicate
    io.rs_out_completed := rs_out_completed

}





/**
  * Verilog generator application
  */
object LoadFIFO_Verilog extends App {

    /* Define the parameters */
    val STREAM_NUM_DIMS  = 8
    val STREAM_ID_WIDTH  = 5
    val VEC_NUM_BYTES	 = 32
    val LF_NUM_TABLES    = 4
    val LF_NUM_BYTES     = 32
    val LLB_NUM_BYTES    = 32
    val ADDRESS_WIDTH    = 32
    val NUM_SRC_OPERANDS = 2
    

    val path = "output/LoadFIFO/"

    
    /* Generate verilog */
    (new ChiselStage).emitVerilog(
        new LoadFIFO(
            STREAM_NUM_DIMS,
            STREAM_ID_WIDTH,
            VEC_NUM_BYTES,
            LF_NUM_TABLES, 
            LF_NUM_BYTES,    
            LLB_NUM_BYTES,
            ADDRESS_WIDTH,
            NUM_SRC_OPERANDS),
        Array("--target-dir", path))

}