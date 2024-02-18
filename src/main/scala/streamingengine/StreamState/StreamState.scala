package streamingengine

import chisel3._
import chisel3.util._





/**
  * 
  *
  * @param STREAM_OFFSET_WIDTH
  * @param STREAM_STRIDE_WIDTH
  * @param STREAM_SIZE_WIDTH
  */
class SS_DimensionDescriptor_Bundle(
    val STREAM_OFFSET_WIDTH: Int,
    val STREAM_STRIDE_WIDTH: Int, 
    val STREAM_SIZE_WIDTH:   Int)
extends Bundle {
    
    /* The set of wires that define the bundle */
    val offset     = UInt(STREAM_OFFSET_WIDTH.W)
    val stride     = UInt(STREAM_STRIDE_WIDTH.W)
    val size       = UInt(STREAM_SIZE_WIDTH.W)

    val acc_offset = UInt(STREAM_OFFSET_WIDTH.W)
    val acc_stride = UInt(STREAM_STRIDE_WIDTH.W)
    val acc_size   = UInt(STREAM_SIZE_WIDTH.W)

    val iterations = UInt(STREAM_SIZE_WIDTH.W)
    val configured = Bool()
    val vectorize  = Bool()
    val completed  = Bool()

}





/**
  * 
  *
  * @param STREAM_STRIDE_WIDTH
  * @param STREAM_SIZE_WIDTH
  */
class SS_ModifierDescriptor_Bundle(
    val STREAM_STRIDE_WIDTH: Int, 
    val STREAM_SIZE_WIDTH:   Int)
extends Bundle {
    
    /* The set of wires that define the bundle */
    val behaviour    = Bool()
    val displacement = UInt(STREAM_STRIDE_WIDTH.W)
    val size         = UInt(STREAM_SIZE_WIDTH.W)
    
    val iterations   = UInt(STREAM_SIZE_WIDTH.W)
    val configured   = Bool()

}





/**
  * 
  *
  * @param STREAM_NUM_DIMS
  * @param STREAM_NUM_MODS
  * @param SS_NUM_TABLES
  * @param STREAM_OFFSET_WIDTH
  * @param STREAM_STRIDE_WIDTH
  * @param STREAM_SIZE_WIDTH
  * @param STREAM_ID_WIDTH
  */
class SS_StreamTable_Bundle(
    val STREAM_NUM_DIMS:     Int,
    val STREAM_NUM_MODS:     Int,
    val SS_NUM_TABLES:       Int,
    val STREAM_OFFSET_WIDTH: Int,
    val STREAM_STRIDE_WIDTH: Int,
    val STREAM_SIZE_WIDTH:   Int,
    val STREAM_ID_WIDTH:     Int)
extends Bundle {
    
    /* The set of wires that define the bundle */
    val dimensions = Vec(STREAM_NUM_DIMS, new SS_DimensionDescriptor_Bundle(
                                            STREAM_OFFSET_WIDTH,
                                            STREAM_STRIDE_WIDTH,
                                            STREAM_SIZE_WIDTH))

    val modifiers = Vec(STREAM_NUM_DIMS, Vec(STREAM_NUM_MODS, new SS_ModifierDescriptor_Bundle(
                                                                STREAM_STRIDE_WIDTH, 
                                                                STREAM_SIZE_WIDTH)))
    
    val width_ = UInt(2.W)
    val stream = UInt(STREAM_ID_WIDTH.W)
    val type_  = Bool()

    val state  = UInt(2.W)
    val valid  = Bool()
    val ready  = Bool()
    
    val mmu_idx        = UInt(log2Ceil(SS_NUM_TABLES).W)
    val active_dim     = Vec(STREAM_NUM_DIMS, Bool())
    val mod_acc_offset = Vec(STREAM_NUM_DIMS, UInt(STREAM_OFFSET_WIDTH.W))

}





/**
  * 
  *
  * @param STREAM_NUM_DIMS     - number of dimensions the Stream Engine supports
  * @param STREAM_NUM_MODS     - number of mods each dimension supports
  * @param SS_NUM_TABLES       - number of streams the Streaming Engine supports (simultaneously)
  * @param STREAM_OFFSET_WIDTH - width of the offset field of a stream
  * @param STREAM_STRIDE_WIDTH - width of the stride field of a stream
  * @param STREAM_SIZE_WIDTH   - width of the size field of a stream
  * @param STREAM_ID_WIDTH     - width of the Stream ID signal
  */
class StreamState(
    val STREAM_NUM_DIMS:     Int,                       
    val STREAM_NUM_MODS:     Int,
    val SS_NUM_TABLES:       Int,
    val STREAM_OFFSET_WIDTH: Int,
    val STREAM_STRIDE_WIDTH: Int, 
    val STREAM_SIZE_WIDTH:   Int,
    val STREAM_ID_WIDTH:     Int)
extends Module {

    /* Internal parameters, calculated using external ones */
    val WIDTH_MAX = Seq(STREAM_OFFSET_WIDTH, STREAM_STRIDE_WIDTH, STREAM_SIZE_WIDTH).max



    /* The set of input and output ports of the module */
    val io = IO(new Bundle {

        /* Control channel */
        val ctrl_reset     = Input(Bool())

        /* Configuration channel */
        val cfg_in_ack           = Input(Bool()) 
        val cfg_in_we            = Input(Bool()) 
        val cfg_in_width         = Input(UInt(2.W))
        val cfg_in_stream        = Input(UInt(STREAM_ID_WIDTH.W)) 
        val cfg_in_type          = Input(Bool())
        val cfg_vec_we           = Input(Bool())
        val cfg_vec_idx          = Input(UInt(log2Ceil(STREAM_NUM_DIMS).W))
        val cfg_dim_we           = Input(Bool())
        val cfg_dim_idx          = Input(UInt(log2Ceil(STREAM_NUM_DIMS).W)) 
        val cfg_dim_offset       = Input(UInt(STREAM_OFFSET_WIDTH.W))
        val cfg_dim_stride       = Input(UInt(STREAM_STRIDE_WIDTH.W))
        val cfg_dim_size         = Input(UInt(STREAM_SIZE_WIDTH.W))
        val cfg_mod_we           = Input(Bool())
        val cfg_mod_idx          = Input(UInt(log2Ceil(STREAM_NUM_DIMS).W)) 
        val cfg_mod_target       = Input(UInt(2.W))
        val cfg_mod_behaviour    = Input(Bool())
        val cfg_mod_displacement = Input(UInt(STREAM_STRIDE_WIDTH.W))
        val cfg_mod_size         = Input(UInt(STREAM_SIZE_WIDTH.W))
        val cfg_in_lmmu_idx      = Input(UInt(log2Ceil(SS_NUM_TABLES).W))
        val cfg_in_smmu_idx      = Input(UInt(log2Ceil(SS_NUM_TABLES).W))
        val cfg_out_ready        = Output(Bool()) 
        
        /* Stream Iterator channel */
        val si_in_iterations   = Input(UInt(STREAM_SIZE_WIDTH.W))
        val si_in_accumulation = Input(UInt(WIDTH_MAX.W))
        val si_in_last         = Input(Bool())
        val si_in_load_ena     = Input(Bool())
        val si_in_load_dim     = Input(Vec(STREAM_NUM_DIMS, Bool()))
        val si_out_op1         = Output(UInt(WIDTH_MAX.W))
        val si_out_op2         = Output(UInt(WIDTH_MAX.W))
        val si_out_mod         = Output(Bool())
        val si_out_dim         = Output(UInt(log2Ceil(STREAM_NUM_DIMS).W))
        val si_out_width       = Output(UInt(2.W))
        val si_out_behaviour   = Output(Bool())
        val si_out_iterations  = Output(UInt(STREAM_SIZE_WIDTH.W))
        val si_out_size        = Output(UInt(STREAM_SIZE_WIDTH.W))
        val si_out_completed   = Output(Vec(STREAM_NUM_DIMS, Bool()))
        val si_out_configured  = Output(Vec(STREAM_NUM_DIMS, Bool()))
        
        /* Load/Store MMU channel */
        val mmu_in_trigger     = Input(Bool())
        val mmu_out_mmu_idx    = Output(UInt(log2Ceil(SS_NUM_TABLES).W))
        val mmu_out_stream     = Output(UInt(STREAM_ID_WIDTH.W))
        val mmu_out_valid      = Output(Bool())
        val mmu_out_addr       = Output(UInt(STREAM_OFFSET_WIDTH.W))
        val mmu_out_addr_valid = Output(Bool())
        val mmu_out_width      = Output(UInt(2.W))
        val mmu_out_completed  = Output(UInt(STREAM_NUM_DIMS.W))
        val mmu_out_vectorize  = Output(Bool())
        val mmu_out_last       = Output(Bool())
        val mmu_out_type       = Output(Bool())

        /* Performance counters */
        val hpc_ss_desc = Output(UInt(32.W))

    })



    /* Macro definitions */
    val TARGET_SIZE   = "b00".U
    val TARGET_STRIDE = "b01".U
    val TARGET_OFFSET = "b10".U

    val BEHAVIOUR_INC = false.B
    val BEHAVIOUR_DEC = true.B

    val STREAM_TYPE_LOAD  = true.B
    val STREAM_TYPE_STORE = false.B

    val STATE_ADDRESS = 0.U
    val STATE_ITERATE = 1.U
    val STATE_APPLY   = 2.U
    val STATE_UPDATE  = 3.U

    val ITER_DIM        = 0.U
    val ITER_MOD_SIZE   = 1.U
    val ITER_MOD_STRIDE = 2.U
    val ITER_MOD_OFFSET = 3.U

    val APPL_DIM_OFFSET = 0.U
    val APPL_MOD_OFFSET = 1.U





    val st_reg = Reg(Vec(SS_NUM_TABLES, 
                        new SS_StreamTable_Bundle(
                            STREAM_NUM_DIMS,
                            STREAM_NUM_MODS,
                            SS_NUM_TABLES,
                            STREAM_OFFSET_WIDTH, 
                            STREAM_STRIDE_WIDTH, 
                            STREAM_SIZE_WIDTH,
                            STREAM_ID_WIDTH)))




    val state_iter_reg = Reg(Vec(4, Bool()))
    val state_appl_reg = Reg(Vec(2, Bool()))

    /* Performance counter registers */
    val hpc_ss_desc = Reg(UInt(32.W))





    /* Definition of internal wires */
    val cfg_empty_tables = Wire(Vec(SS_NUM_TABLES, Bool()))
    val cfg_empty_ready  = Wire(Bool())
    val cfg_empty_idx    = Wire(UInt(log2Ceil(SS_NUM_TABLES).W))

    val cfg_append_table = Wire(Vec(SS_NUM_TABLES, Bool()))
    val cfg_append_valid = Wire(Bool())
    val cfg_append_idx   = Wire(UInt(log2Ceil(SS_NUM_TABLES).W))

    val dim_curr_vec = Wire(Vec(STREAM_NUM_DIMS, Bool()))
    val dim_next_vec = Wire(Vec(STREAM_NUM_DIMS, Bool()))

    val dim_curr_idx = Wire(UInt(log2Ceil(STREAM_NUM_DIMS).W))
    val dim_next_idx = Wire(UInt(log2Ceil(STREAM_NUM_DIMS).W))

    val si_out_op1        = Wire(UInt(WIDTH_MAX.W))
    val si_out_op2        = Wire(UInt(WIDTH_MAX.W))
    val si_out_mod        = Wire(Bool())
    val si_out_dim        = Wire(UInt(log2Ceil(STREAM_NUM_DIMS).W))
    val si_out_width      = Wire(UInt(2.W))
    val si_out_behaviour  = Wire(Bool())
    val si_out_iterations = Wire(UInt(STREAM_SIZE_WIDTH.W))
    val si_out_size       = Wire(UInt(STREAM_SIZE_WIDTH.W))
    val si_out_completed  = Wire(Vec(STREAM_NUM_DIMS, Bool()))
    val si_out_configured = Wire(Vec(STREAM_NUM_DIMS, Bool()))

    val mmu_out_stream        = Wire(UInt(STREAM_ID_WIDTH.W))
    val mmu_out_valid         = Wire(Bool())
    val mmu_out_type          = Wire(Bool())
    val mmu_out_width         = Wire(UInt(2.W))
    val mmu_out_mmu_idx       = Wire(UInt(log2Ceil(SS_NUM_TABLES).W))
    val mmu_out_addr          = Wire(UInt(STREAM_OFFSET_WIDTH.W))
    val mmu_out_addr_valid    = Wire(Bool())
    val mmu_out_completed_vec = Wire(Vec(STREAM_NUM_DIMS, Bool()))
    val mmu_out_completed     = Wire(UInt(STREAM_NUM_DIMS.W))
    val mmu_out_vectorize_vec = Wire(Vec(STREAM_NUM_DIMS, Bool()))
    val mmu_out_vectorize     = Wire(Bool())
    val mmu_out_last_vec      = Wire(Vec(STREAM_NUM_DIMS, Bool()))
    val mmu_out_last          = Wire(Bool())




    val vect_sizeZero_index         = Wire(UInt(log2Ceil(STREAM_NUM_DIMS).W)) // todo
    val vect_sizeZero_valid         = Wire(Bool())
    val forceComplete               = Wire(Vec(STREAM_NUM_DIMS, Bool()))


    val st_idx = Wire(UInt(log2Ceil(SS_NUM_TABLES).W))
    val st     = Wire(new SS_StreamTable_Bundle(
                        STREAM_NUM_DIMS,
                        STREAM_NUM_MODS,
                        SS_NUM_TABLES,
                        STREAM_OFFSET_WIDTH, 
                        STREAM_STRIDE_WIDTH, 
                        STREAM_SIZE_WIDTH,
                        STREAM_ID_WIDTH)) 


                        
    /* An arbiter selects one configured stream to process. The
     * selected stream will generate addresses for the MMUs
     */             
    val ARBITER_MMU = Module(new RoundRobinArbiter(SS_NUM_TABLES))
    val arbiter_mmu_input   = Wire(Vec(SS_NUM_TABLES, Bool()))
    val arbiter_mmu_trigger = Wire(Bool())


    








    /* The Stream State searches for non-configured tables and
     * selects the first it finds for when a new stream configuration
     * takes places
     */
    for (i <- 0 until SS_NUM_TABLES) {
        cfg_empty_tables(i) := !st_reg(i).valid
    }

    cfg_empty_ready := cfg_empty_tables.reduce(_ || _) 
    cfg_empty_idx := PriorityEncoder(cfg_empty_tables) 


    
    /* When there are non-configured (empty) tables and the
     * processing unit is providing a new valid configuration
     * instruction, a new table is configured
     */
    when (io.cfg_in_we && cfg_empty_ready) {

        /* Update the stream header parameters */
        st_reg(cfg_empty_idx).width_ := io.cfg_in_width
        st_reg(cfg_empty_idx).stream := io.cfg_in_stream
        st_reg(cfg_empty_idx).type_  := io.cfg_in_type
    


        /* When the stream has a cfg.vec instruction associated,
         * it marks the corresponding dimensions
         */
        when (io.cfg_vec_we) {
            for (i <- 0 until STREAM_NUM_DIMS) {
                when (i.U <= io.cfg_vec_idx) {
                    st_reg(cfg_empty_idx).dimensions(i).vectorize := true.B
                }
            }
        }


        /* During the configuration of a new table, reset all 
         * parameter fields of the descriptors
         */
        st_reg(cfg_empty_idx).dimensions.foreach {d =>
            d.offset     := 0.U
            d.stride     := 0.U
            d.size       := 0.U
            d.acc_offset := 0.U
            d.acc_stride := 0.U
            d.acc_size   := 0.U
            d.iterations := 0.U
            d.configured := false.B
            d.completed  := false.B
        }
            
        st_reg(cfg_empty_idx).modifiers.foreach {m =>
            m.foreach {x =>
                x.behaviour    := BEHAVIOUR_INC
                x.displacement := 0.U
                x.size         := 0.U
                x.iterations   := 0.U
                x.configured   := false.B
            }
        }
        

        st_reg(cfg_empty_idx).mod_acc_offset.foreach {x => x := false.B}
        st_reg(cfg_empty_idx).active_dim.foreach {x => x := false.B}
        st_reg(cfg_empty_idx).valid := true.B
        st_reg(cfg_empty_idx).ready := false.B



        /* Depending on the stream type, the index on the MMU tables
         * is saved in the Stream State
         */
        switch (io.cfg_in_type) {
            is (STREAM_TYPE_LOAD)  {st_reg(cfg_empty_idx).mmu_idx := io.cfg_in_lmmu_idx}
            is (STREAM_TYPE_STORE) {st_reg(cfg_empty_idx).mmu_idx := io.cfg_in_smmu_idx}
        }

    }
    
    






    /* After configuration of a new table, more configuration instruction
     * arrive with information regarding the descriptors of the stream
     */
    for (i <- 0 until SS_NUM_TABLES) {
        cfg_append_table(i) := st_reg(i).stream === io.cfg_in_stream && st_reg(i).valid
    }
    cfg_append_idx   := PriorityEncoder(cfg_append_table)
    cfg_append_valid := cfg_append_table.reduce(_ || _)



    /* Append the new information by updating the descriptor registers
     * of the Stream State
     */
    when (cfg_append_valid) {

        /* Configuring a new dimension descriptor */
        when (io.cfg_dim_we) {
            st_reg(cfg_append_idx).dimensions(io.cfg_dim_idx).offset     := io.cfg_dim_offset
            st_reg(cfg_append_idx).dimensions(io.cfg_dim_idx).stride     := io.cfg_dim_stride
            st_reg(cfg_append_idx).dimensions(io.cfg_dim_idx).size       := io.cfg_dim_size
            st_reg(cfg_append_idx).dimensions(io.cfg_dim_idx).acc_offset := 0.U
            st_reg(cfg_append_idx).dimensions(io.cfg_dim_idx).acc_stride := 0.U
            st_reg(cfg_append_idx).dimensions(io.cfg_dim_idx).acc_size   := 0.U
            st_reg(cfg_append_idx).dimensions(io.cfg_dim_idx).iterations := 0.U
            st_reg(cfg_append_idx).dimensions(io.cfg_dim_idx).configured := true.B
            st_reg(cfg_append_idx).dimensions(io.cfg_dim_idx).completed  := false.B
        }



        /* Configuring a new static modifier */
        when (io.cfg_mod_we) {
            st_reg(cfg_append_idx).modifiers(io.cfg_mod_idx)(io.cfg_mod_target).behaviour    := io.cfg_mod_behaviour
            st_reg(cfg_append_idx).modifiers(io.cfg_mod_idx)(io.cfg_mod_target).displacement := io.cfg_mod_displacement
            st_reg(cfg_append_idx).modifiers(io.cfg_mod_idx)(io.cfg_mod_target).size         := io.cfg_mod_size
            st_reg(cfg_append_idx).modifiers(io.cfg_mod_idx)(io.cfg_mod_target).iterations   := io.cfg_mod_size
            st_reg(cfg_append_idx).modifiers(io.cfg_mod_idx)(io.cfg_mod_target).configured   := true.B 
        }
        
        

        /* When the final configuration signal is provided, an acknowledge
         * signal is received to inform that the current stream will now be
         * ready to be iterated/processed
         */
        when (io.cfg_in_ack) {
            
            /* Mark stream as ready to be processed */
            st_reg(cfg_append_idx).ready := true.B 
            

            /* Initialize the accumulation fields */
            st_reg(cfg_append_idx).dimensions.foreach {d =>
                d.acc_offset  := d.offset
                d.acc_stride  := d.stride 
                d.acc_size    := d.size  
                d.iterations := d.size 
            }
            

            /* Initialize the number of iterations of all modifiers */
            st_reg(cfg_append_idx).modifiers.foreach {y => y.foreach {x => x.iterations := x.size}}
            


            /* Determine the highest configured dimension, which
             * will be the active one when the stream receives the
             * acknowledge signal
             */
            val last_cfg_idx = Wire(UInt(STREAM_NUM_DIMS.W))
            last_cfg_idx := DontCare
            
            for (i <- 0 until STREAM_NUM_DIMS) {
                when(st_reg(cfg_append_idx).dimensions(i).configured) {
                    last_cfg_idx := i.U
                }
                
                st_reg(cfg_append_idx).active_dim(i) := i.U === last_cfg_idx
            }
            

            


            /* Detect if, in the moment the stream receives the acknowledge
             * signal, there are already dimensions with size zero. In those
             * cases, mark that dimension as completed
             */
            val ack_complete_dims   = Wire(Vec(STREAM_NUM_DIMS, Bool()))
            val ack_size_zero_idx   = Wire(UInt(log2Ceil(STREAM_NUM_DIMS).W))
            val ack_size_zero_valid = Wire(Bool())

            ack_size_zero_idx := 0.U
            ack_size_zero_valid := false.B

            for (i <- 0 until STREAM_NUM_DIMS) {
                when (st_reg(cfg_append_idx).dimensions(i).size < 1.U && st_reg(cfg_append_idx).dimensions(i).configured) {
                    ack_size_zero_valid := true.B
                    ack_size_zero_idx   := i.U 
                }

                ack_complete_dims(i) := i.U <= ack_size_zero_idx && ack_size_zero_valid
            }

            // Set the complete flags of dimensions according to theirs size fields
            for (i <- 0 until STREAM_NUM_DIMS) {
                
                when (ack_complete_dims(i)) {
                    st_reg(cfg_append_idx).dimensions(i).completed := true.B
                }
                .otherwise {
                    st_reg(cfg_append_idx).dimensions(i).completed := st_reg(cfg_append_idx).dimensions(i).size <= 1.U 
                }
            }
            

            
            /* Determine the initial operating state of the finite
             * state machine. When there is only one dimension it
             * is ready to generate addresses. Otherwise it must first
             * update all the offset from the highest dimension until
             * the lowest
             */
            when (last_cfg_idx === 0.U) {
                st_reg(cfg_append_idx).state := STATE_ADDRESS
            }
            .otherwise {
                st_reg(cfg_append_idx).state := STATE_UPDATE
            }

        }

    }









    /* The Stream State uses an Arbiter that selects 
     * valid and ready streams to generate new addresses
     * for the MMUs. When the MMUs cannot accept a given
     * address, the arbiter triggers to a new stream
     */
    for (i <- 0 until SS_NUM_TABLES) {
        arbiter_mmu_input(i) := st_reg(i).ready
    }


    ARBITER_MMU.io.reset   := io.ctrl_reset
	ARBITER_MMU.io.trigger := arbiter_mmu_trigger
	ARBITER_MMU.io.input   := arbiter_mmu_input
    
    st_idx := ARBITER_MMU.io.output
    st     := st_reg(st_idx)

	
    /* The address generated cannot be commited/accepted by the
     * MMUs or the current stream table selected by the arbiter
     * is not a valid and ready one. The arbiter is triggered to
     * select another stream
     */ 
    arbiter_mmu_trigger := io.mmu_in_trigger || !st.ready || !st.valid 





    /* Defining the default values for some of the
     * declared internal wires
     */
    dim_curr_vec := st.active_dim
    dim_next_vec := VecInit(st.active_dim.tail :+ false.B)

    dim_curr_idx := PriorityEncoder(dim_curr_vec)
    dim_next_idx := PriorityEncoder(dim_next_vec)


    /* Default values of the internal signals that will
     * connect to the Stream Iterator
     */ 
    si_out_op1        := DontCare
    si_out_op2        := DontCare
    si_out_behaviour  := DontCare
    si_out_iterations := DontCare
    si_out_size       := 1.U
    si_out_mod        := DontCare
    si_out_dim        := DontCare
    si_out_width      := DontCare

    for (i <- 0 until STREAM_NUM_DIMS) {
        si_out_completed(i)  := st.dimensions(i).completed
        si_out_configured(i) := st.dimensions(i).configured
    }



    /* Default values of the internal signals that will
     * connect to the MMUs
     */ 
    mmu_out_stream     := st.stream
    mmu_out_valid      := st.active_dim(0) && st.ready && (mmu_out_addr_valid || mmu_out_vectorize)
    mmu_out_type       := st.type_
    mmu_out_width      := st.width_
    mmu_out_mmu_idx    := st.mmu_idx

    mmu_out_addr       := st.dimensions(0).acc_offset
    mmu_out_addr_valid := st.active_dim(0) && st.dimensions(0).acc_size.asSInt > 0.S && st.dimensions(0).acc_offset.asSInt >= 0.S && st.ready

    
    for (i <- 0 until STREAM_NUM_DIMS) {
        mmu_out_completed_vec(i) := st.dimensions(i).completed
        mmu_out_vectorize_vec(i) := !st.dimensions(i).configured || !st.dimensions(i).vectorize || (st.dimensions(i).vectorize && st.dimensions(i).completed)
        mmu_out_last_vec(i) := !st.dimensions(i).configured || (st.dimensions(i).configured && st.dimensions(i).completed)
    }


    mmu_out_completed  := mmu_out_completed_vec.asUInt


    when (vect_sizeZero_valid) {
        mmu_out_vectorize := mmu_out_vectorize_vec.reduce(_ && _) && st.dimensions(dim_curr_idx).acc_size === 0.U
        mmu_out_last := mmu_out_last_vec.reduce(_ && _) && st.dimensions(dim_curr_idx).acc_size === 0.U
    }
    .otherwise {
        mmu_out_vectorize_vec(0) := !st.dimensions(0).configured || !st.dimensions(0).vectorize || (st.dimensions(0).vectorize && (st.active_dim(0) && io.si_in_last))
        mmu_out_vectorize := mmu_out_vectorize_vec.reduce(_ && _)

        mmu_out_last_vec(0) := io.si_in_last && st.active_dim(0)
        mmu_out_last := mmu_out_last_vec.reduce(_ && _)
    }


    // Detect if there is any dimension with acc_size zero and obtain the index of the last one
    vect_sizeZero_valid := false.B
    vect_sizeZero_index := 0.U

    for (i <- 0 until STREAM_NUM_DIMS) {

        when (st.ready && st.dimensions(i.U).acc_size < 1.U && st.dimensions(i).configured) {
            vect_sizeZero_valid := true.B
            vect_sizeZero_index := i.U 
        }

        forceComplete(i) := i.U <= vect_sizeZero_index && vect_sizeZero_valid
    }










    /* The Stream State will analyse the current state of the
     * selected stream (selected by the arbiter) and will process
     * it accordingly
     */
    when (!arbiter_mmu_trigger) {

        switch(st.state) {


            /* Selected stream is currently in the lowest dimension descriptor
             * and is generating valid addresses
             */
            is (STATE_ADDRESS) {
                
                /* Its stops generating new addresses when all the iterations
                 * of the lowest dimension have been processed. Jump to the
                 * iteration of descriptors state and determine which descriptors
                 * should be iterated
                 */
                when (io.si_in_load_ena) {

                    val load_dim_idx = Wire(UInt(log2Ceil(STREAM_NUM_DIMS).W))
                    load_dim_idx := PriorityEncoder(io.si_in_load_dim)

                    st_reg(st_idx).state := STATE_ITERATE
                    st_reg(st_idx).active_dim := io.si_in_load_dim

                    /* Determine which descriptors are configured and schedule them for iteration */
                    state_iter_reg(ITER_DIM)        := st.dimensions(load_dim_idx).configured
                    state_iter_reg(ITER_MOD_SIZE)   := st.modifiers(load_dim_idx)(TARGET_SIZE).configured
                    state_iter_reg(ITER_MOD_STRIDE) := st.modifiers(load_dim_idx)(TARGET_STRIDE).configured
                    state_iter_reg(ITER_MOD_OFFSET) := st.modifiers(load_dim_idx)(TARGET_OFFSET).configured

                }


                
                /* Commit the new results */
                st_reg(st_idx).dimensions(dim_curr_idx).acc_offset  := io.si_in_accumulation
                st_reg(st_idx).dimensions(dim_curr_idx).iterations := io.si_in_iterations
                
                /* Mark the dimension as completed in the last iteration */
                when (io.si_in_last) {
                    st_reg(st_idx).dimensions(dim_curr_idx).completed := true.B
                }



                /* Update wires that connect to the Stream Iterator */
                si_out_op1        := st.dimensions(dim_curr_idx).acc_offset
                si_out_op2        := st.dimensions(dim_curr_idx).acc_stride
                si_out_iterations := st.dimensions(dim_curr_idx).iterations
                si_out_behaviour  := BEHAVIOUR_INC
                si_out_size       := st.dimensions(dim_curr_idx).acc_size
                si_out_mod        := false.B
                si_out_dim        := dim_curr_idx
                si_out_width      := st.width_

            }



            /* Selected stream is updating the descriptors of an higher 
             * dimension. All configured dimension and modifier descriptors
             * are iterated. This includes iterating the dimension descriptor
             * and all the three modifiers. The offset modifiers requires
             * an extra step to first increment the accumulated offset and then
             * modifying it in the lower dimension
             */
            is (STATE_ITERATE) {
                
                val iter_option_idx  = Wire(UInt(log2Ceil(4).W))
                val iter_option_null = Wire(Bool())

                iter_option_idx  := PriorityEncoder(state_iter_reg)
                iter_option_null := !state_iter_reg.reduce(_ || _)

                

                /* When all descriptors have been iterated, jump to the
                 * the state where the changes are applied across the lower
                 * dimensions
                 */
                when (iter_option_null) {

                    st_reg(st_idx).state := STATE_APPLY

                    state_appl_reg(APPL_DIM_OFFSET) := true.B
                    state_appl_reg(APPL_MOD_OFFSET) := st.modifiers(dim_curr_idx)(TARGET_OFFSET).configured



                    /* COMPLETE FLAGS todo*/
                    // reset completed flags
                    for (i <- 0 until STREAM_NUM_DIMS) {
                        
                        when (forceComplete(i)) {
                            st_reg(st_idx).dimensions(i.U).completed := true.B
                        }
                        .elsewhen (i.U < dim_curr_idx) {
                            st_reg(st_idx).dimensions(i.U).completed := st.dimensions(i.U).acc_size <= 1.U
                        }
                    }

                }
                .otherwise {
                
                    /* Selecting one configured descriptor and iterate it */
                    switch (iter_option_idx) {

                        is (ITER_DIM) {

                            state_iter_reg(ITER_DIM) := false.B
                            


                            /* Commit the new results */
                            st_reg(st_idx).dimensions(dim_curr_idx).acc_offset  := io.si_in_accumulation
                            st_reg(st_idx).dimensions(dim_curr_idx).iterations := io.si_in_iterations
                            
                            /* Mark the dimension as completed in the last iteration */
                            when (io.si_in_last) {
                                st_reg(st_idx).dimensions(dim_curr_idx).completed := true.B
                            }
                            


                            /* Update wires that connect to the Stream Iterator */
                            si_out_op1        := st.dimensions(dim_curr_idx).acc_offset
                            si_out_op2        := st.dimensions(dim_curr_idx).acc_stride
                            si_out_iterations := st.dimensions(dim_curr_idx).iterations
                            si_out_behaviour  := BEHAVIOUR_INC
                            si_out_size       := st.dimensions(dim_curr_idx).acc_size
                            si_out_mod        := false.B

                        }



                        is (ITER_MOD_SIZE) {
                            
                            state_iter_reg(ITER_MOD_SIZE) := false.B



                            /* Commit the new results */
                            when (st_reg(st_idx).modifiers(dim_curr_idx)(TARGET_SIZE).iterations === 0.U) {
                                st_reg(st_idx).dimensions(dim_next_idx).acc_size                := st.dimensions(dim_next_idx).size
                                st_reg(st_idx).modifiers(dim_curr_idx)(TARGET_SIZE).iterations := st.modifiers(dim_curr_idx)(TARGET_SIZE).size
                            }
                            .otherwise {
                                st_reg(st_idx).dimensions(dim_next_idx).acc_size                := io.si_in_accumulation
                                st_reg(st_idx).modifiers(dim_curr_idx)(TARGET_SIZE).iterations := io.si_in_iterations
                            }



                            /* Update wires that connect to the Stream Iterator */
                            si_out_op1        := st.dimensions(dim_next_idx).acc_size
                            si_out_op2        := st.modifiers(dim_curr_idx)(TARGET_SIZE).displacement
                            si_out_iterations := st.modifiers(dim_curr_idx)(TARGET_SIZE).iterations
                            si_out_behaviour  := st.modifiers(dim_curr_idx)(TARGET_SIZE).behaviour
                            si_out_size       := st.modifiers(dim_curr_idx)(TARGET_SIZE).size
                            si_out_mod        := true.B

                        }



                        is (ITER_MOD_STRIDE) {

                            state_iter_reg(ITER_MOD_STRIDE) := false.B

                            
                            
                            /* Commit the new results */
                            when (st_reg(st_idx).modifiers(dim_curr_idx)(TARGET_STRIDE).iterations === 0.U) {
                                st_reg(st_idx).dimensions(dim_next_idx).acc_stride                := st.dimensions(dim_next_idx).stride
                                st_reg(st_idx).modifiers(dim_curr_idx)(TARGET_STRIDE).iterations := st.modifiers(dim_curr_idx)(TARGET_STRIDE).size

                            }
                            .otherwise {
                                st_reg(st_idx).dimensions(dim_next_idx).acc_stride                := io.si_in_accumulation
                                st_reg(st_idx).modifiers(dim_curr_idx)(TARGET_STRIDE).iterations := io.si_in_iterations
                            }



                            /* Update wires that connect to the Stream Iterator */
                            si_out_op1        := st.dimensions(dim_next_idx).acc_stride
                            si_out_op2        := st.modifiers(dim_curr_idx)(TARGET_STRIDE).displacement
                            si_out_iterations := st.modifiers(dim_curr_idx)(TARGET_STRIDE).iterations
                            si_out_behaviour  := st.modifiers(dim_curr_idx)(TARGET_STRIDE).behaviour
                            si_out_size       := st.modifiers(dim_curr_idx)(TARGET_STRIDE).size
                            si_out_mod        := true.B
                            
                        }



                        is (ITER_MOD_OFFSET) {

                            state_iter_reg(ITER_MOD_OFFSET) := false.B


                            
                            /* Commit the new results */
                            when (st_reg(st_idx).modifiers(dim_curr_idx)(TARGET_OFFSET).iterations === 0.U) {
                                st_reg(st_idx).mod_acc_offset(dim_curr_idx)                  := 0.U 
                                st_reg(st_idx).modifiers(dim_curr_idx)(TARGET_OFFSET).iterations := st.modifiers(dim_curr_idx)(TARGET_OFFSET).size
                            }
                            .otherwise {
                                st_reg(st_idx).mod_acc_offset(dim_curr_idx)                  := io.si_in_accumulation
                                st_reg(st_idx).modifiers(dim_curr_idx)(TARGET_OFFSET).iterations := io.si_in_iterations
                            }



                            /* Update wires that connect to the Stream Iterator */
                            si_out_op1        := st.mod_acc_offset(dim_curr_idx)
                            si_out_op2        := st.modifiers(dim_curr_idx)(TARGET_OFFSET).displacement
                            si_out_iterations := st.modifiers(dim_curr_idx)(TARGET_OFFSET).iterations
                            si_out_behaviour  := st.modifiers(dim_curr_idx)(TARGET_OFFSET).behaviour
                            si_out_size       := st.modifiers(dim_curr_idx)(TARGET_OFFSET).size
                            si_out_mod        := true.B

                        }

                    }

                }

                /* Update wires that connect to the Stream Iterator */
                si_out_dim        := dim_curr_idx
                si_out_width      := st.width_
                si_out_size       := st.dimensions(dim_curr_idx).acc_size

            }


            /* The dimension being iterated directly impacts the lower dimension */
            is (STATE_APPLY) {
                
                val jump_state = Wire(Bool())
                jump_state :=  state_appl_reg(APPL_DIM_OFFSET) && !state_appl_reg(APPL_MOD_OFFSET) ||
                              !state_appl_reg(APPL_DIM_OFFSET) &&  state_appl_reg(APPL_MOD_OFFSET)



                when (jump_state) {
                    st_reg(st_idx).state := Mux(st.active_dim(1), STATE_ADDRESS, STATE_UPDATE)
                    st_reg(st_idx).active_dim := dim_next_vec
                }




                st_reg(st_idx).dimensions(dim_next_idx).acc_offset  := io.si_in_accumulation

                when (state_appl_reg(APPL_DIM_OFFSET)) {

                    for (i <- 0 until STREAM_NUM_MODS) {
                        st_reg(st_idx).modifiers(dim_next_idx)(i).iterations := st.modifiers(dim_next_idx)(i).size
                    }

                    st_reg(st_idx).dimensions(dim_next_idx).iterations := st.dimensions(dim_next_idx).acc_size
                    state_appl_reg(APPL_DIM_OFFSET) := false.B

                    /* Update wires that connect to the Stream Iterator */
                    si_out_op1        := st.dimensions(dim_curr_idx).acc_offset
                    si_out_op2        := st.dimensions(dim_next_idx).offset
                    si_out_iterations := DontCare
                    si_out_behaviour  := BEHAVIOUR_INC
                    si_out_size       := st.dimensions(dim_curr_idx).acc_size
                    si_out_mod        := DontCare
                    si_out_dim        := DontCare
                    si_out_width      := DontCare

                }
                .elsewhen (state_appl_reg(APPL_MOD_OFFSET)) {


                    /* Update wires that connect to the Stream Iterator */
                    si_out_op1        := st.mod_acc_offset(dim_curr_idx)
                    si_out_op2        := st.dimensions(dim_next_idx).acc_offset
                    si_out_iterations := DontCare
                    si_out_behaviour  := BEHAVIOUR_INC
                    si_out_size       := st.dimensions(dim_curr_idx).acc_size
                    si_out_mod        := DontCare
                    si_out_dim        := DontCare
                    si_out_width      := DontCare
                }

            }



            /* The streaming paradigm requires the acc_size and acc_strides to be
             * reset if the dimension isn't a direct lower neighboor of the one
             * that just got iterated
             */
            is (STATE_UPDATE) {


            
                when (io.si_in_load_ena) {
                    st_reg(st_idx).active_dim := io.si_in_load_dim
                }
                .otherwise {
                    st_reg(st_idx).active_dim := dim_next_vec
                }




                
                /* When the active dimension is the one right above the ground
                 * dimension, the finite state machine jumps to the state
                 * where addresses start being produced
                 */
                when (io.si_in_load_ena) {
                    st_reg(st_idx).state := STATE_ITERATE
                }
                .elsewhen (st.active_dim(1)) {
                    st_reg(st_idx).state := STATE_ADDRESS
                }







                /* Commit the new results and reset the iteration count of the lower dimension */
                st_reg(st_idx).dimensions(dim_next_idx).acc_offset := io.si_in_accumulation

                for (i <- 0 until STREAM_NUM_MODS) {
                    st_reg(st_idx).modifiers(dim_next_idx)(i).iterations := st.modifiers(dim_next_idx)(i).size
                }
                
                
                st_reg(st_idx).dimensions(dim_next_idx).iterations := st.dimensions(dim_next_idx).size

                st_reg(st_idx).mod_acc_offset(dim_curr_idx)   := 0.U
                st_reg(st_idx).dimensions(dim_next_idx).acc_size   := st.dimensions(dim_next_idx).size
                st_reg(st_idx).dimensions(dim_next_idx).acc_stride := st.dimensions(dim_next_idx).stride



                

                /* Update wires that connect to the Stream Iterator */
                si_out_op1        := st.dimensions(dim_curr_idx).acc_offset
                si_out_op2        := st.dimensions(dim_next_idx).offset
                si_out_iterations := DontCare
                si_out_behaviour  := BEHAVIOUR_INC
                si_out_size       := st.dimensions(dim_curr_idx).acc_size
                si_out_mod        := DontCare
                si_out_dim        := DontCare
                si_out_width      := DontCare

            }

        }

    }





    when (!arbiter_mmu_trigger && mmu_out_last) {

        st_reg(st_idx).dimensions.foreach {d =>
            d.offset     := 0.U
            d.stride     := 0.U
            d.size       := 0.U
            d.acc_offset := 0.U
            d.acc_stride := 0.U
            d.acc_size   := 0.U
            d.iterations := 0.U
            d.configured := false.B
            d.completed  := false.B
        }
            
        st_reg(st_idx).modifiers.foreach {m =>
            m.foreach {x =>
                x.behaviour    := BEHAVIOUR_INC
                x.displacement := 0.U
                x.size         := 0.U
                x.iterations   := 0.U
                x.configured   := false.B
            }
        }

        st_reg(st_idx).width_ := 0.U
        st_reg(st_idx).stream := 0.U
        st_reg(st_idx).type_  := false.B

        st_reg(st_idx).valid := false.B
        st_reg(st_idx).ready := false.B
        
        st_reg(st_idx).mmu_idx := 0.U
        st_reg(st_idx).active_dim.foreach {x => x := false.B}
        st_reg(st_idx).mod_acc_offset.foreach {x => x := false.B}
        
    }






    /* When the selected stream is valid and the active
     * dimension isn't the lowest, the performance counter
     * regarding the number of cycles iteration higher 
     * descriptors is incremented
     */
    when (!arbiter_mmu_trigger && !st.active_dim(0)) {
        hpc_ss_desc := hpc_ss_desc + 1.U
    }


    


    /* The reset signal was activated and all registers
     * will be updated to the default values
     */
    when (io.ctrl_reset) {

        st_reg.foreach {st =>
            st.dimensions.foreach {d =>
                d.offset     := 0.U
                d.stride     := 0.U
                d.size       := 0.U
                d.acc_offset := 0.U
                d.acc_stride := 0.U
                d.acc_size   := 0.U
                d.iterations := 0.U
                d.configured := false.B
                d.vectorize  := false.B
                d.completed  := false.B
            }
            
            st.modifiers.foreach {y =>
                y.foreach {x =>
                    x.behaviour    := BEHAVIOUR_INC
                    x.displacement := 0.U
                    x.size         := 0.U
                    x.iterations   := 0.U
                    x.configured   := false.B
                }
            }

            st.width_ := 0.U
            st.stream := 0.U
            st.type_  := false.B
            
            st.valid := false.B
            st.ready := false.B
            
            st.mmu_idx := 0.U
            st.mod_acc_offset.foreach {x => x := 0.U}
            st.active_dim.foreach {x => x := false.B}
        }

        state_iter_reg.foreach {x => x := false.B}
        state_appl_reg.foreach {x => x := false.B}

        hpc_ss_desc := 0.U

    }





    /* Connecting the output ports of the module to
     * some of the internal wires
     */
    io.cfg_out_ready := cfg_empty_ready

    io.si_out_completed  := si_out_completed
    io.si_out_configured := si_out_configured
    io.si_out_op1        := si_out_op1
    io.si_out_op2        := si_out_op2
    io.si_out_mod        := si_out_mod
    io.si_out_dim        := si_out_dim
    io.si_out_width      := si_out_width
    io.si_out_behaviour  := si_out_behaviour
    io.si_out_iterations := si_out_iterations
    io.si_out_size       := si_out_size

    io.mmu_out_mmu_idx    := mmu_out_mmu_idx
    io.mmu_out_stream     := mmu_out_stream
    io.mmu_out_valid      := mmu_out_valid
    io.mmu_out_addr       := mmu_out_addr
    io.mmu_out_addr_valid := mmu_out_addr_valid
    io.mmu_out_width      := mmu_out_width
    io.mmu_out_completed  := mmu_out_completed
    io.mmu_out_vectorize  := mmu_out_vectorize
    io.mmu_out_last       := mmu_out_last
    io.mmu_out_type       := mmu_out_type

    io.hpc_ss_desc := hpc_ss_desc

}
