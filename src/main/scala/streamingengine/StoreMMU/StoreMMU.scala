package streamingengine

import chisel3._
import chisel3.util._





/**
  * 
  *
  * @param ADDRESS_WIDTH
  */
class address_FIFOelement_Bundle(
	val ADDRESS_WIDTH: Int)
extends Bundle {

	/* The set of wires that define the bundle */
	val address = UInt(ADDRESS_WIDTH.W)
	val valid   = Bool()

}





/**
  * 
  */
class data_FIFOelement_Bundle
extends Bundle {

	/* The set of wires that define the bundle */
	val data  = UInt(8.W)
	val valid = Bool()

}





/**
  * 
  *
  * @param SMMU_NUM_ADDRESSES
  * @param SMMU_NUM_TABLES
  * @param STREAM_ID_WIDTH
  * @param ADDRESS_WIDTH
  * @param VEC_NUM_BYTES
  */
class storeFIFO_Bundle(
	val SMMU_NUM_ADDRESSES: Int,
	val SMMU_NUM_TABLES: 	Int,
	val STREAM_ID_WIDTH:	Int,
	val ADDRESS_WIDTH: 		Int, 
	val VEC_NUM_BYTES: 		Int)
extends Bundle {

	/* The set of wires that define the bundle */
    val addressFIFO = Vec(SMMU_NUM_ADDRESSES, 
						new address_FIFOelement_Bundle(ADDRESS_WIDTH))

	val dataFIFO    = Vec(VEC_NUM_BYTES,
						new data_FIFOelement_Bundle())

	val address_write_ptr = UInt(log2Ceil(SMMU_NUM_ADDRESSES).W)
	val address_read_ptr  = UInt(log2Ceil(SMMU_NUM_ADDRESSES).W)
	val data_read_ptr     = UInt(log2Ceil(VEC_NUM_BYTES).W)

	val ss_done = Bool()
	
	val stream = UInt(STREAM_ID_WIDTH.W)
	val width_ = UInt(2.W)
	val valid  = Bool()
}





/**
  * 
  *
  * @param SMMU_NUM_TABLES    - number of Store FIFOs supported
  * @param SMMU_NUM_ADDRESSES - size of the address queue of each Store FIFO
  * @param STREAM_ID_WIDTH	  - width of the Stream ID signal
  * @param ADDRESS_WIDTH      - width of addresses produced by the Streaming Engine
  * @param VEC_NUM_BYTES      - number of bytes in a vector
  * @param AXI_W_DATA_WIDTH   - width of the AXI WDATA bus
  */
class StoreMMU(	
		val SMMU_NUM_TABLES: 	Int,
		val SMMU_NUM_ADDRESSES:	Int,
		val STREAM_ID_WIDTH:	Int,
		val ADDRESS_WIDTH: 		Int,
		val VEC_NUM_BYTES: 		Int,
		val AXI_W_DATA_WIDTH: 	Int)
extends Module {
    
	/* Internal parameters, calculated using external ones */
    val VEC_WIDTH 		    = VEC_NUM_BYTES * 8
    
	val AXI_W_NUM_BYTES 	= AXI_W_DATA_WIDTH / 8
    val AXI_W_OFFSET_WIDTH  = log2Ceil(AXI_W_NUM_BYTES)
	val AXI_W_TAG_WIDTH		= ADDRESS_WIDTH - AXI_W_OFFSET_WIDTH



	/* The set of input and output ports of the module */
    val io = IO(new Bundle {

		/* Control channel */
        val ctrl_reset 	  			= Input(Bool())

		/* Configuration channel */
		val cfg_in_we 	   	  		= Input(Bool())
		val cfg_in_stream 	  		= Input(UInt(STREAM_ID_WIDTH.W))
        val cfg_in_width 	  		= Input(UInt(2.W)) 
		val cfg_out_ready  	 		= Output(Bool())
		val cfg_out_mmu_idx			= Output(UInt(log2Ceil(SMMU_NUM_TABLES).W))

		/* Destination operands data channel */
        val rd_in_valid          	= Input(Bool())	
        val rd_in_streamid       	= Input(UInt(STREAM_ID_WIDTH.W))
        val rd_in_vecdata   	  	= Input(UInt(VEC_WIDTH.W))
        val rd_in_predicate	  		= Input(UInt(VEC_NUM_BYTES.W))
        val rd_out_ready          	= Output(Bool())
        val rd_out_width           	= Output(UInt(2.W))

		/* Stream State channel */
        val ss_in_mmu_idx    		= Input(UInt(log2Ceil(SMMU_NUM_TABLES).W))
        val ss_in_addr   			= Input(UInt(ADDRESS_WIDTH.W))
		val ss_in_addr_valid     	= Input(Bool())
		val ss_in_last         		= Input(Bool())
		val ss_out_trigger 			= Output(Bool())	
		
		/* AXI Write Address Channel */
		val axi_aw_ready  			= Input(Bool())
		val axi_aw_valid  			= Output(Bool())
		val axi_aw_addr   			= Output(UInt(ADDRESS_WIDTH.W))
        val axi_aw_len              = Output(UInt(8.W)) // ARLEN[7:0]
        val axi_aw_size             = Output(UInt(3.W)) // ARSIZE[2:0]
        val axi_aw_burst            = Output(UInt(1.W)) // ARBURST[1:0]

		/* AXI Write Data Channel */
		val axi_w_ready   			= Input(Bool())
		val axi_w_valid   			= Output(Bool())
		val axi_w_data    			= Output(UInt(AXI_W_DATA_WIDTH.W))
		val axi_w_strb    			= Output(UInt((AXI_W_DATA_WIDTH / 8).W))
		val axi_w_last   			= Output(Bool())

		/* AXI Write Response Channel */
		val axi_b_resp  			= Input(UInt(2.W))
		val axi_b_valid 			= Input(Bool())
		val axi_b_ready 			= Output(Bool()) 

		/* Performance counters */
		val hpc_smmu_commit = Output(UInt(32.W))  
        val hpc_smmu_stall  = Output(UInt(32.W))
		val hpc_ops_store      = Output(UInt(16.W))

    })



	/* State nomenclature */
	val STATE_WAIT_PAIR		= 0.U
	val STATE_WAIT_AWREADY  = 1.U
	val STATE_WAIT_WREADY   = 2.U
	val STATE_WAIT_BVALID   = 3.U





	/* The Store MMU consists in multiple tables, each one
	 * holding information of a single stream. Each table
	 * has an address and a data FIFO. It also has registers
	 * to guarante correct order of writting operations.
	 * It has registers to hold paramenters required by the
	 * AXI protocol
	 */
	val sf_reg = Reg(Vec(SMMU_NUM_TABLES, 
						new storeFIFO_Bundle(
							SMMU_NUM_ADDRESSES,
							SMMU_NUM_TABLES, 
							STREAM_ID_WIDTH, 
							ADDRESS_WIDTH,
							VEC_NUM_BYTES)))
	
	val sq_index_reg  = Reg(Vec(SMMU_NUM_TABLES, UInt(log2Ceil(SMMU_NUM_TABLES).W)))
	val sq_valid_reg  = Reg(Vec(SMMU_NUM_TABLES, Bool()))

	val store_ptr_reg = Reg(UInt(SMMU_NUM_TABLES.W))
	val queue_ptr_reg = Reg(UInt(SMMU_NUM_TABLES.W))

	val store_state = Reg(UInt(2.W))

	val buffer_data		= Reg(Vec(AXI_W_NUM_BYTES, UInt(8.W)))
	val buffer_strb		= Reg(Vec(AXI_W_NUM_BYTES, UInt(1.W)))
	val buffer_addr_tag = Reg(UInt(AXI_W_TAG_WIDTH.W))
	val buffer_valid 	= Reg(Bool())

	/* Performance Counters */
	val hpc_smmu_commit = Reg(UInt(32.W))
	val hpc_smmu_stall  = Reg(UInt(32.W))
	val hpc_ops_store   = Reg(UInt(16.W))





	/* Internal wire declaration */
	val cfg_out_ready  	= Wire(Bool())
	val cfg_out_mmu_idx = Wire(UInt(log2Ceil(SMMU_NUM_TABLES).W))

	val cfg_empty_table_vec   = Wire(Vec(SMMU_NUM_TABLES, Bool()))
	val cfg_empty_table_idx   = Wire(UInt(log2Ceil(SMMU_NUM_TABLES).W))
	val cfg_empty_table_valid = Wire(Bool())

	val rd_out_ready = Wire(Bool())
	val rd_out_width = Wire(UInt(2.W))
	
	val table_idx = Wire(UInt(log2Ceil(SMMU_NUM_TABLES).W))
	val write_idx = Wire(UInt(log2Ceil(SMMU_NUM_ADDRESSES).W))

	val fu_streamid_comp = Wire(Vec(SMMU_NUM_TABLES, Bool()))
	val fu_idx 	 		 = Wire(UInt(log2Ceil(SMMU_NUM_TABLES).W))
	val fu_exists 		 = Wire(Bool())
	val fu_read_ptr		 = Wire(UInt(log2Ceil(VEC_NUM_BYTES).W))

	val ss_out_trigger   = Wire(Bool()) 

	val store_ptr_idx    = Wire(UInt(log2Ceil(SMMU_NUM_TABLES).W))
	val queue_ptr_idx    = Wire(UInt(log2Ceil(SMMU_NUM_TABLES).W))

	val store_r_addr_ptr = Wire(UInt(log2Ceil(SMMU_NUM_ADDRESSES).W))
	val store_r_data_ptr = Wire(UInt(log2Ceil(VEC_NUM_BYTES).W))
	val store_sel_idx	 = Wire(UInt(log2Ceil(SMMU_NUM_TABLES).W))

	val next_addr_valid  = Wire(Bool())
	val next_data_valid  = Wire(Bool())

	val next_addr		 = Wire(UInt(ADDRESS_WIDTH.W))
	val next_addr_tag 	 = Wire(UInt(AXI_W_TAG_WIDTH.W))
	val next_addr_offset = Wire(UInt(AXI_W_OFFSET_WIDTH.W))

	val axi_aw_valid = Wire(Bool())
	val axi_aw_addr	 = Wire(UInt(ADDRESS_WIDTH.W))
    val axi_aw_len	 = Wire(UInt(8.W)) // ARLEN[7:0]
    val axi_aw_size	 = Wire(UInt(3.W)) // ARSIZE[2:0]
    val axi_aw_burst = Wire(UInt(1.W)) // ARBURST[1:0]
	
	val axi_w_valid	 = Wire(Bool())
	val axi_w_data   = Wire(UInt(AXI_W_DATA_WIDTH.W))
	val axi_w_strb   = Wire(UInt((AXI_W_DATA_WIDTH / 8).W))
	val axi_w_last   = Wire(Bool())
	
	val axi_b_ready	 = Wire(Bool()) 





	/* The Store MMU keeps track of non-configured tables. When
	 * a configuration of a store stream arrives, the module
	 * selects one of them and initializes the registers
	 */
	for (i <- 0 until SMMU_NUM_TABLES) {
		cfg_empty_table_vec(i) := !sf_reg(i).valid
	}
	cfg_empty_table_idx   := PriorityEncoder(cfg_empty_table_vec)
	cfg_empty_table_valid := cfg_empty_table_vec.reduce(_ || _)
	


	/* There are non-configured tables and a new store
	 * stream is being configured
	 */
	when (io.cfg_in_we && cfg_empty_table_valid) {

		sf_reg(cfg_empty_table_idx).addressFIFO.foreach { x =>
			x.address := 0.U
			x.valid   := false.B	
		}

		sf_reg(cfg_empty_table_idx).dataFIFO.foreach { x =>
			x.data  := 0.U
			x.valid := false.B	
		}

		sf_reg(cfg_empty_table_idx).address_write_ptr 	:= 0.U
		sf_reg(cfg_empty_table_idx).address_read_ptr  	:= 0.U
		sf_reg(cfg_empty_table_idx).data_read_ptr		:= 0.U

		sf_reg(cfg_empty_table_idx).ss_done 			:= false.B

		sf_reg(cfg_empty_table_idx).stream 			:= io.cfg_in_stream
		sf_reg(cfg_empty_table_idx).width_ 			:= io.cfg_in_width
		sf_reg(cfg_empty_table_idx).valid  			:= true.B
	}

	cfg_out_ready 	:= cfg_empty_table_valid
	cfg_out_mmu_idx := cfg_empty_table_idx

	



	/* When the Stream State is providing new valid addresses, the
	 * Store MMU will check is that new address can be registered
	 * in the address FIFO of the Stream
	 */
	write_idx := sf_reg(io.ss_in_mmu_idx).address_write_ptr
	table_idx := io.ss_in_mmu_idx
	
	ss_out_trigger := sf_reg(table_idx).addressFIFO(write_idx).valid

	when (io.ss_in_addr_valid && !ss_out_trigger) {

		sf_reg(table_idx).addressFIFO(write_idx).address := io.ss_in_addr
		sf_reg(table_idx).addressFIFO(write_idx).valid   := true.B
		
		sf_reg(table_idx).address_write_ptr := write_idx + 1.U
	
		when (io.ss_in_last) {
			sf_reg(table_idx).ss_done := true.B
		}

	}
		




	/* The Store MMU will accept valid data vectors from the
	 * Function Unit. It does so by searching the table with
	 * the correct stream that is providing the data and copying
	 * it to the data FIFO
	 */
	for (i <- 0 until SMMU_NUM_TABLES) {											
		fu_streamid_comp(i) := sf_reg(i).stream === io.rd_in_streamid && sf_reg(i).valid
	}									

	fu_idx   	:= PriorityEncoder(fu_streamid_comp)
	fu_exists 	:= fu_streamid_comp.reduce(_ || _)
	fu_read_ptr := sf_reg(fu_idx).data_read_ptr

	rd_out_ready := !sf_reg(fu_idx).dataFIFO(fu_read_ptr).valid && fu_exists	
	rd_out_width := sf_reg(fu_idx).width_
	




	/* An handshake occurs, and the data is commited */
	when (io.rd_in_valid && rd_out_ready) {		

		/* Transfering the predicated bytes to the data FIFO
		 * and reseting the data read pointer
		 */
		for (i <- 0 until VEC_NUM_BYTES) {	
			when (io.rd_in_predicate(i)) {
				sf_reg(fu_idx).dataFIFO(i.U).data  := io.rd_in_vecdata(8 * (i + 1) - 1, 8 * i) 
				sf_reg(fu_idx).dataFIFO(i.U).valid := true.B
			}
			.otherwise {
				sf_reg(fu_idx).dataFIFO(i.U).valid := false.B
			}
		}

		sf_reg(fu_idx).data_read_ptr := 0.U
		


		/* The Store MMU guarantes correct order of writting
		 * operations between streams through a queue, which
		 * registers the order that streams provide data to 
		 * the Store MMU. When new valid data arrives, this 
		 * queue is updated
		 */
		for (i <- 0 until SMMU_NUM_TABLES) {
			when (queue_ptr_reg(i) === 1.U) {
				sq_index_reg(i) := fu_idx
				sq_valid_reg(i) := true.B
			}
		}
		queue_ptr_reg := Cat(queue_ptr_reg(SMMU_NUM_TABLES - 2, 0), queue_ptr_reg(SMMU_NUM_TABLES - 1)) // shift left (circular)

	}





	/* Fetch the next stream in the queue with valid data and
	 * obtain the address and data pointers of its queues
	 */
	store_ptr_idx := PriorityEncoder(store_ptr_reg)
	queue_ptr_idx := PriorityEncoder(queue_ptr_reg)

	store_sel_idx := sq_index_reg(store_ptr_idx)

	store_r_addr_ptr := sf_reg(store_sel_idx).address_read_ptr
	store_r_data_ptr := sf_reg(store_sel_idx).data_read_ptr





	/* Determine if there is a new valid pair of address and data */
	next_addr_valid  := sf_reg(store_sel_idx).addressFIFO(store_r_addr_ptr).valid && 
					    sf_reg(store_sel_idx).valid && 
					    sq_valid_reg(store_ptr_idx)

	next_data_valid	 := sf_reg(store_sel_idx).dataFIFO(store_r_data_ptr).valid && 
						sf_reg(store_sel_idx).valid && 
						sq_valid_reg(store_ptr_idx)

	/* Obtain the address tag and the offset within the data row */
	next_addr 	  	 := sf_reg(store_sel_idx).addressFIFO(store_r_addr_ptr).address.asUInt
	next_addr_tag 	 := next_addr >> AXI_W_OFFSET_WIDTH
	next_addr_offset := next_addr(AXI_W_OFFSET_WIDTH - 1, 0)





    /* The Store MMU communicates with the Data Memory
     * thought the AXI protocol. The default values
     * are here defined
     */
    axi_aw_valid	:= false.B
    axi_aw_addr		:= DontCare
    axi_aw_len		:= 0.U									// NUM_TRANSACTIONS = ARLEN + 1
    axi_aw_size		:= (log2Ceil(AXI_W_DATA_WIDTH / 8)).U	// BEAT SIZE IN BYTES = 2 ^ ARSIZE
    axi_aw_burst	:= "b01".U								// INCREMENTAL BURST
	
	axi_w_valid		:= false.B
	axi_w_data		:= DontCare
	axi_w_strb		:= DontCare
	axi_w_last		:= true.B
	
	axi_b_ready		:= false.B



    /* A finite state machine manages the AXI protocol
     * transactions. It transitions to different states
     * as data is being accepted and communicated with
     * the Data Memory
     */
	switch (store_state) {

		/* STATE: waiting for valid pairs of (address, data) */
		is (STATE_WAIT_PAIR) {
			
			when (next_addr_valid && next_data_valid) {

				/* There is a valid address being buffered and a different one was received */
				when (buffer_valid && buffer_addr_tag =/= next_addr_tag) {

					/* Jump to the next state, where the store request will be resolved */
					store_state := STATE_WAIT_AWREADY

				}
				.otherwise {

					/* Update the address and valid registers in case it is a new configuration */
					buffer_addr_tag := next_addr_tag
					buffer_valid 	:= true.B


					/* Update the data and strobe values */
					switch(sf_reg(store_sel_idx).width_) {
						is ("b00".U) {
							for (i <- 0 until AXI_W_NUM_BYTES by 1) {
								when (i.U === next_addr_offset) {
									for (j <- 0 until 1) {
										buffer_data(next_addr_offset + j.U) := sf_reg(store_sel_idx).dataFIFO(store_r_data_ptr + j.U).data
										buffer_strb(next_addr_offset + j.U) := 1.U
									}
								}
							}
						}

						is ("b01".U) {
							for (i <- 0 until AXI_W_NUM_BYTES by 2) {
								when (i.U === next_addr_offset) {
									for (j <- 0 until 2) {
										buffer_data(next_addr_offset + j.U) := sf_reg(store_sel_idx).dataFIFO(store_r_data_ptr + j.U).data
										buffer_strb(next_addr_offset + j.U) := 1.U
									}
								}
							}
						}
						
						is ("b10".U) {
							for (i <- 0 until AXI_W_NUM_BYTES by 4) {
								when (i.U === next_addr_offset) {
									for (j <- 0 until 4) {
										buffer_data(next_addr_offset + j.U) := sf_reg(store_sel_idx).dataFIFO(store_r_data_ptr + j.U).data
										buffer_strb(next_addr_offset + j.U) := 1.U
									}
								}
							}
						}

						is ("b11".U) {
							for (i <- 0 until AXI_W_NUM_BYTES by 8) {
								when (i.U === next_addr_offset) {
									for (j <- 0 until 8) {
										buffer_data(next_addr_offset + j.U) := sf_reg(store_sel_idx).dataFIFO(store_r_data_ptr + j.U).data
										buffer_strb(next_addr_offset + j.U) := 1.U
									}
								}
							}
						}
					}



					/* Clear the data entries from the FIFO */
					switch(sf_reg(store_sel_idx).width_) {
						is ("b00".U) {
							sf_reg(store_sel_idx).data_read_ptr := store_r_data_ptr + 1.U
							for (i <- 0 until 1) sf_reg(store_sel_idx).dataFIFO(store_r_data_ptr + i.U).valid := false.B
						}

						is ("b01".U) {
							sf_reg(store_sel_idx).data_read_ptr := store_r_data_ptr + 2.U
							for (i <- 0 until 2) sf_reg(store_sel_idx).dataFIFO(store_r_data_ptr + i.U).valid := false.B
						}

						is ("b10".U) {
							sf_reg(store_sel_idx).data_read_ptr := store_r_data_ptr + 4.U
							for (i <- 0 until 4) sf_reg(store_sel_idx).dataFIFO(store_r_data_ptr + i.U).valid := false.B
						}

						is ("b11".U) {
							sf_reg(store_sel_idx).data_read_ptr := store_r_data_ptr + 8.U
							for (i <- 0 until 8) sf_reg(store_sel_idx).dataFIFO(store_r_data_ptr + i.U).valid := false.B
						}
					}

					/* Clear the address entry from the FIFO */
					sf_reg(store_sel_idx).addressFIFO(store_r_addr_ptr).valid := false.B	
					sf_reg(store_sel_idx).address_read_ptr := store_r_addr_ptr + 1.U	



					/* Update the FIFO pointer in case the last pair was issued */
					when (!sf_reg(store_sel_idx).dataFIFO(store_r_data_ptr + 1.U).valid) {
						for (i <- 0 until SMMU_NUM_TABLES) {
							when (store_ptr_reg(i) === 1.U) {
								sq_index_reg(i) := 0.U
								sq_valid_reg(i) := false.B
							}
						}
						store_ptr_reg := Cat(store_ptr_reg(SMMU_NUM_TABLES - 2, 0), store_ptr_reg(SMMU_NUM_TABLES - 1)) // shift left (circular)
					}
				}
			}



			/* Eventually all pairs will be resolved. Free all resources
			 * and issue one final request in there is valid buffered data
			 */
			val clear_mmu_resources = Wire(Bool())
			clear_mmu_resources := sf_reg(store_sel_idx).ss_done && sf_reg(store_sel_idx).valid && !sf_reg(store_sel_idx).addressFIFO(store_r_addr_ptr + 1.U).valid
			
			when (clear_mmu_resources) {

				/* Free all allocated resources of the stream that finished */
				sf_reg(store_sel_idx).addressFIFO.foreach {x =>
					x.address := 0.U
					x.valid   := false.B
				}
				
				sf_reg(store_sel_idx).dataFIFO.foreach {x =>
					x.data  := 0.U
					x.valid := false.B
				}
				
				sf_reg(store_sel_idx).address_write_ptr := 0.U
				sf_reg(store_sel_idx).address_read_ptr  := 0.U
				sf_reg(store_sel_idx).data_read_ptr     := 0.U

				sf_reg(store_sel_idx).ss_done := false.B
				
				sf_reg(store_sel_idx).stream := 0.U
				sf_reg(store_sel_idx).width_ := 0.U
				sf_reg(store_sel_idx).valid  := false.B


				/* Trigger one last store request if there is valid buffered data */
				when (buffer_valid) {
					store_state := STATE_WAIT_AWREADY
				}

			}



			/* Reassignment of AXI internal signals */
			axi_aw_valid := false.B
			axi_aw_addr  := 0.U
			axi_w_valid  := false.B
			axi_w_data 	 := 0.U
			axi_w_strb	 := 0.U
			axi_b_ready  := false.B

		}



		/* STATE: waiting for AWREADY */
		is (STATE_WAIT_AWREADY) {

			when (io.axi_aw_ready) {
				store_state := STATE_WAIT_WREADY

				/* Memory controller accepted a new load request. Increment the
                 * performance counter regarding the total number of load requests
                 */
				hpc_ops_store := hpc_ops_store + 1.U
			}



			/* Reassignment of AXI internal signals */
			axi_aw_valid := true.B
			axi_aw_addr  := buffer_addr_tag << AXI_W_OFFSET_WIDTH
			axi_w_valid  := false.B
			axi_w_data 	 := 0.U
			axi_w_strb	 := 0.U
			axi_b_ready  := false.B

		}



		/* STATE: waiting for WREADY */
		is (STATE_WAIT_WREADY) {

			when (io.axi_w_ready) {
				store_state := STATE_WAIT_BVALID
			}
			


			/* Reassignment of AXI internal signals */
			axi_aw_valid := false.B
			axi_aw_addr  := 0.U
			axi_w_valid  := true.B 
			axi_w_data   := buffer_data.reverse.reduce(Cat(_,_))
			axi_w_strb   := buffer_strb.reverse.reduce(Cat(_,_))
			axi_b_ready  := false.B
		}



		/* STATE: waiting for BVALID */
		is (STATE_WAIT_BVALID) {

			when (io.axi_b_valid) {

				when (io.axi_b_resp === "b00".U) {
					
					/* Free the buffers when no errors occurred */
					buffer_data.foreach {x => x := 0.U}
					buffer_strb.foreach {x => x := 0.U}
					buffer_addr_tag := 0.U
					buffer_valid 	:= false.B

					store_state := STATE_WAIT_PAIR

				}
				.otherwise {

					/* Repeat the store request when an error occurred */
					store_state := STATE_WAIT_AWREADY

				}

			}



			/* Reassignment of AXI internal signals */
			axi_aw_valid := false.B
			axi_aw_addr  := 0.U
			axi_w_valid  := false.B
			axi_w_data 	 := 0.U
			axi_w_strb	 := 0.U
			axi_b_ready  := true.B

		}

	}





	/* Increment the hardware performance counters
	 * when a new valid address is being provided
	 */
    when (io.ss_in_addr_valid) {

        when (ss_out_trigger) {
            hpc_smmu_stall := hpc_smmu_stall + 1.U
        }
        .otherwise {
            hpc_smmu_commit := hpc_smmu_commit + 1.U
        }

    }
	




    /* When the reset signal is activated, the registers of
     * the module will be updated to their default values
     */
	when (io.ctrl_reset) {
		
		sf_reg.foreach {y =>

			y.addressFIFO.foreach {x =>
				x.address := 0.U
				x.valid   := false.B
			}
			
			y.dataFIFO.foreach {x =>
				x.data  := 0.U
				x.valid := false.B
			}
			
			y.address_write_ptr := 0.U
			y.address_read_ptr  := 0.U
			y.data_read_ptr     := 0.U

			y.ss_done := false.B
			
			y.stream := 0.U
			y.width_ := 0.U
			y.valid  := false.B

		}

		sq_index_reg.foreach {x => x := 0.U}
		sq_valid_reg.foreach {x => x := false.B}

		store_ptr_reg := 1.U
		queue_ptr_reg := 1.U

		store_state := STATE_WAIT_PAIR

		buffer_data.foreach {x => x := 0.U}
		buffer_strb.foreach {x => x := 0.U}
		buffer_addr_tag := 0.U
		buffer_valid 	:= false.B

		hpc_smmu_commit := 0.U
		hpc_smmu_stall  := 0.U
		hpc_ops_store 	:= 0.U

	}





    /* Connecting the output ports to some of 
     * the internal wires of the module
     */
	io.cfg_out_ready   := cfg_out_ready
	io.cfg_out_mmu_idx := cfg_out_mmu_idx

	io.rd_out_ready    := rd_out_ready
	io.rd_out_width    := rd_out_width

	io.ss_out_trigger  := Mux(io.ss_in_addr_valid, ss_out_trigger, false.B)

    io.axi_aw_valid	:= axi_aw_valid
    io.axi_aw_addr	:= axi_aw_addr
    io.axi_aw_len	:= axi_aw_len
    io.axi_aw_size	:= axi_aw_size
    io.axi_aw_burst	:= axi_aw_burst

	io.axi_w_valid	:= axi_w_valid
	io.axi_w_data	:= axi_w_data
	io.axi_w_strb	:= axi_w_strb
	io.axi_w_last	:= axi_w_last
	
	io.axi_b_ready 	:= axi_b_ready

    io.hpc_smmu_commit := hpc_smmu_commit
    io.hpc_smmu_stall  := hpc_smmu_stall
	io.hpc_ops_store   := hpc_ops_store

}
