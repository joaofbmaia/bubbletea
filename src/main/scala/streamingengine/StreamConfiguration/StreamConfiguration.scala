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
class SC_DimensionDescriptor_Bundle(
    val STREAM_OFFSET_WIDTH:    Int,
    val STREAM_STRIDE_WIDTH:    Int, 
    val STREAM_SIZE_WIDTH:      Int)
extends Bundle {
    
    /* The set of wires that define the bundle */
    val offset = UInt(STREAM_OFFSET_WIDTH.W)
    val stride = UInt(STREAM_STRIDE_WIDTH.W)
    val size   = UInt(STREAM_SIZE_WIDTH.W)
}





/**
  * 
  *
  * @param STREAM_NUM_DIMS
  * @param STREAM_STRIDE_WIDTH
  * @param STREAM_SIZE_WIDTH
  */
class SC_ModifierDescriptor_Bundle( 
    val STREAM_NUM_DIMS:        Int,
    val STREAM_STRIDE_WIDTH:    Int, 
    val STREAM_SIZE_WIDTH:      Int)
extends Bundle {
    
    /* The set of wires that define the bundle */
    val target       = UInt(2.W)
    val behaviour    = Bool()
    val displacement = UInt(STREAM_STRIDE_WIDTH.W)
    val size         = UInt(STREAM_SIZE_WIDTH.W)
    val dimension    = UInt(log2Ceil(STREAM_NUM_DIMS).W)
}





/**
  * 
  *
  * @param STREAM_NUM_DIMS     - number of dimensions the Stream Engine supports
  * @param STREAM_NUM_MODS     - number of mods each dimension supports
  * @param STREAM_OFFSET_WIDTH - width of the offset field of a stream
  * @param STREAM_STRIDE_WIDTH - width of the stride field of a stream
  * @param STREAM_SIZE_WIDTH   - width of the size field of a stream
  * @param STREAM_ID_WIDTH     - width of the Stream ID signal
  */
class StreamConfiguration(  
    val STREAM_NUM_DIMS:        Int,                       
    val STREAM_NUM_MODS:        Int,  
    val STREAM_OFFSET_WIDTH:    Int,
    val STREAM_STRIDE_WIDTH:    Int, 
    val STREAM_SIZE_WIDTH:      Int,
    val STREAM_ID_WIDTH:        Int)
extends Module {  

    /* Internal parameters, calculated using external ones */
    val SC_MAX_NUM_MODS = STREAM_NUM_MODS * (STREAM_NUM_DIMS - 1)



    /* The set of input and output ports of the module */
    val io = IO(new Bundle {

        /* Control channel */
        val ctrl_reset              = Input(Bool())

        /* Processing unit channel */
        val cpu_in_valid            = Input(Bool())    
        val cpu_in_sta 			    = Input(Bool())               
        val cpu_in_end 			    = Input(Bool())                                        
        val cpu_in_type             = Input(Bool())                              
        val cpu_in_width            = Input(UInt(2.W))
        val cpu_in_stream           = Input(UInt(STREAM_ID_WIDTH.W))
        val cpu_in_mod              = Input(Bool())                                               
        val cpu_in_vec              = Input(Bool())                                       
        val cpu_in_dim_offset       = Input(UInt(STREAM_OFFSET_WIDTH.W))
        val cpu_in_dim_stride       = Input(UInt(STREAM_STRIDE_WIDTH.W))
        val cpu_in_dim_size         = Input(UInt(STREAM_SIZE_WIDTH.W))
        val cpu_in_mod_target       = Input(UInt(2.W))
        val cpu_in_mod_behaviour    = Input(Bool())
        val cpu_in_mod_displacement = Input(UInt(STREAM_STRIDE_WIDTH.W))
        val cpu_in_mod_size         = Input(UInt(STREAM_SIZE_WIDTH.W))
        val cpu_out_ready           = Output(Bool())                                               

        /* Streaming Engine channel */
        val se_in_ready_load        = Input(Bool())                                     
        val se_in_ready_store       = Input(Bool())                                          
        val se_out_valid            = Output(Bool())                                               
        val se_out_ack              = Output(Bool())
        val se_out_vec_we           = Output(Bool())
        val se_out_vec_idx          = Output(UInt(log2Ceil(STREAM_NUM_DIMS).W))
        val se_out_cfg_we           = Output(Bool())
        val se_out_cfg_type         = Output(Bool())
        val se_out_cfg_width        = Output(UInt(2.W))
        val se_out_cfg_stream       = Output(UInt(STREAM_ID_WIDTH.W))
        val se_out_dim_we           = Output(Bool())
        val se_out_dim_idx          = Output(UInt(log2Ceil(STREAM_NUM_DIMS).W))                 
        val se_out_dim_offset       = Output(UInt(STREAM_OFFSET_WIDTH.W))
        val se_out_dim_stride       = Output(UInt(STREAM_STRIDE_WIDTH.W))
        val se_out_dim_size         = Output(UInt(STREAM_SIZE_WIDTH.W))
        val se_out_mod_we           = Output(Bool())
        val se_out_mod_idx          = Output(UInt(log2Ceil(STREAM_NUM_DIMS).W))               
        val se_out_mod_target       = Output(UInt(2.W))
        val se_out_mod_behaviour    = Output(Bool())
        val se_out_mod_displacement = Output(UInt(STREAM_STRIDE_WIDTH.W))
        val se_out_mod_size         = Output(UInt(STREAM_SIZE_WIDTH.W))

    })



    /* Macro definitions */
    val STATE_CONFIG = 0.U
    val STATE_WAIT   = 1.U
    val STATE_COMMIT = 2.U

    val BEHAVIOUR_INC = false.B
    val BEHAVIOUR_DEC = true.B





    /* Internal registers declarations. The Stream Configuration
     * module will receiving multiple separated configuration 
     * instructions that together define a memory access pattern.
     * The information provided by each instruction is stored
     * in internal registers and then used to configure the Stream
     * State with a new stream
     */
    val dims_reg = Reg(Vec(STREAM_NUM_DIMS, 
                        new SC_DimensionDescriptor_Bundle(
                            STREAM_OFFSET_WIDTH, 
                            STREAM_STRIDE_WIDTH, 
                            STREAM_SIZE_WIDTH)))

    val mods_reg = Reg(Vec(SC_MAX_NUM_MODS,
                        new SC_ModifierDescriptor_Bundle(
                            STREAM_NUM_DIMS,
                            STREAM_STRIDE_WIDTH, 
                            STREAM_SIZE_WIDTH)))

    val next_write_dim = Reg(Vec(STREAM_NUM_DIMS + 1, Bool())) 
    val next_write_mod = Reg(Vec(SC_MAX_NUM_MODS + 1, Bool())) 
    
    val width_reg  = Reg(UInt(2.W))
    val stream_reg = Reg(UInt(STREAM_ID_WIDTH.W))
    val type_reg   = Reg(Bool())

    val vec_ena_reg = Reg(Bool()) 
    val vec_dim_reg = Reg(UInt(log2Ceil(STREAM_NUM_DIMS).W)) 

    val state_reg = Reg(UInt(2.W))





    /* Internal wire declaration */
    val last_dim_oh  = Wire(Vec(STREAM_NUM_DIMS + 1, Bool()))
    val last_dim_idx = Wire(UInt(log2Ceil(STREAM_NUM_DIMS).W))

    val last_mod_oh  = Wire(Vec(SC_MAX_NUM_MODS + 1, Bool()))
    val last_mod_idx = Wire(UInt(log2Ceil(SC_MAX_NUM_MODS).W))

    val se_in_ready = Wire(Bool())

    val cpu_out_ready = Wire(Bool())   

    val se_out_valid = Wire(Bool())                                               
    val se_out_ack   = Wire(Bool())

    val se_out_vec_we  = Wire(Bool())
    val se_out_vec_idx = Wire(UInt(log2Ceil(STREAM_NUM_DIMS).W))

    val se_out_cfg_we     = Wire(Bool())
    val se_out_cfg_type   = Wire(Bool())
    val se_out_cfg_width  = Wire(UInt(2.W))
    val se_out_cfg_stream = Wire(UInt(STREAM_ID_WIDTH.W))

    val se_out_dim_we     = Wire(Bool())
    val se_out_dim_idx    = Wire(UInt(log2Ceil(STREAM_NUM_DIMS).W))                 
    val se_out_dim_offset = Wire(UInt(STREAM_OFFSET_WIDTH.W))
    val se_out_dim_stride = Wire(UInt(STREAM_STRIDE_WIDTH.W))
    val se_out_dim_size   = Wire(UInt(STREAM_SIZE_WIDTH.W))

    val se_out_mod_we           = Wire(Bool())
    val se_out_mod_idx          = Wire(UInt(log2Ceil(STREAM_NUM_DIMS).W))               
    val se_out_mod_target       = Wire(UInt(2.W))
    val se_out_mod_behaviour    = Wire(Bool())
    val se_out_mod_displacement = Wire(UInt(STREAM_STRIDE_WIDTH.W))
    val se_out_mod_size         = Wire(UInt(STREAM_SIZE_WIDTH.W))





    /* Internal wire value definition */
    last_dim_oh  := VecInit(next_write_dim.tail :+ false.B)
    last_dim_idx := PriorityEncoder(last_dim_oh)

    last_mod_oh  := VecInit(next_write_mod.tail :+ false.B)
    last_mod_idx := PriorityEncoder(last_mod_oh)

    se_in_ready := (io.se_in_ready_load && type_reg) || (io.se_in_ready_store && !type_reg) 

    cpu_out_ready := false.B

    se_out_valid := false.B
    se_out_ack   := false.B

    se_out_vec_we  := false.B
    se_out_vec_idx := DontCare

    se_out_cfg_we     := false.B
    se_out_cfg_width  := DontCare
    se_out_cfg_stream := DontCare
    se_out_cfg_type   := DontCare

    se_out_dim_we     := false.B
    se_out_dim_idx    := DontCare
    se_out_dim_offset := DontCare
    se_out_dim_stride := DontCare
    se_out_dim_size   := DontCare

    se_out_mod_we           := false.B
    se_out_mod_idx          := DontCare
    se_out_mod_target       := DontCare 
    se_out_mod_behaviour    := DontCare
    se_out_mod_displacement := DontCare
    se_out_mod_size         := DontCare



    

    /* A finite state machine controls the stream configuration.
     * In the first state the processing unit feeds the Streaming
     * Engine with configuration instructions, describing the 
     * memory access pattern. In the second the Stream Configuration
     * module waits until the Streaming Engine is ready to receive
     * the information. In the third, that information is transfered
     */
    switch (state_reg) {

        /* STATE: receiving configuration instructions from
         * the processing unit
         */
        is (STATE_CONFIG) {

            /* The processing unit is providing a new valid
             * configuration instruction
             */
            when (io.cpu_in_valid) {
                
                /* It is the first configuration instruction,
                 * save the header information */
                when (io.cpu_in_sta) {
                    type_reg   := io.cpu_in_type
                    width_reg  := io.cpu_in_width
                    stream_reg := io.cpu_in_stream
                }



                /* It is the last configuration instruction,
                 * transition to the the STATE_WAIT state
                 */
                when (io.cpu_in_end) { 
                    state_reg := STATE_WAIT

                    /* Currently vectorizes in the last instruction
                     * if the vectorization instruction was not called
                     */
                    when (!vec_ena_reg) {
                        vec_ena_reg := true.B
                        vec_dim_reg := PriorityEncoder(next_write_dim)
                    }
                }

                

                /* The cfg.vec instruction will vectorize the vectors
                 * produced in the until now defined dimension
                 */
                when (io.cpu_in_vec) {
                    when (!vec_ena_reg) {
                        vec_ena_reg := true.B
                        vec_dim_reg := last_dim_idx
                    }
                }



                /* If not a cfg.vec instruction, a dimension or modifiers
                 * is being attached to the current configuration
                 */
                .otherwise {

                    /* Add a new modifier descriptor */
                    when (io.cpu_in_mod) {  
                        for (i <- 0 until SC_MAX_NUM_MODS) {
                            when (next_write_mod(i)) {
                                mods_reg(i).target       := io.cpu_in_mod_target
                                mods_reg(i).behaviour    := io.cpu_in_mod_behaviour
                                mods_reg(i).displacement := io.cpu_in_mod_displacement
                                mods_reg(i).size         := io.cpu_in_mod_size
                                mods_reg(i).dimension    := last_dim_idx
                            }
                        }

                        next_write_mod := VecInit(false.B +: next_write_mod.init)
                    }

                    /* Add a new dimension descriptor */
                    .otherwise {
                        for (i <- 0 until STREAM_NUM_DIMS) {
                            when (next_write_dim(i)) {
                                dims_reg(i).offset := io.cpu_in_dim_offset
                                dims_reg(i).stride := io.cpu_in_dim_stride
                                dims_reg(i).size   := io.cpu_in_dim_size
                            }
                        }

                        next_write_dim := VecInit(false.B +: next_write_dim.init)
                    }

                }

            }



            /* Reassignment of the output internal wires */
            cpu_out_ready := true.B

            se_out_valid := false.B
            se_out_ack   := false.B

            se_out_vec_we  := false.B
            se_out_vec_idx := DontCare

            se_out_cfg_we     := false.B
            se_out_cfg_width  := DontCare
            se_out_cfg_stream := DontCare
            se_out_cfg_type   := DontCare

            se_out_dim_we     := false.B
            se_out_dim_idx    := DontCare
            se_out_dim_offset := DontCare
            se_out_dim_stride := DontCare
            se_out_dim_size   := DontCare

            se_out_mod_we           := false.B
            se_out_mod_idx          := DontCare
            se_out_mod_target       := DontCare 
            se_out_mod_behaviour    := DontCare
            se_out_mod_displacement := DontCare
            se_out_mod_size         := DontCare

        }



        /* STATE: waiting until the rest of the Streaming
         * Engine is ready to receive the new configuration
         */       
        is (STATE_WAIT) {

            when (se_in_ready) {
                state_reg := STATE_COMMIT
            }



            /* Reassignment of the output internal wires */
            cpu_out_ready := false.B

            se_out_valid := true.B
            se_out_ack   := false.B

            se_out_vec_we  := vec_ena_reg
            se_out_vec_idx := vec_dim_reg

            se_out_cfg_we     := true.B
            se_out_cfg_width  := width_reg
            se_out_cfg_stream := stream_reg
            se_out_cfg_type   := type_reg

            se_out_dim_we     := false.B
            se_out_dim_idx    := DontCare
            se_out_dim_offset := DontCare
            se_out_dim_stride := DontCare
            se_out_dim_size   := DontCare

            se_out_mod_we           := false.B
            se_out_mod_idx          := DontCare
            se_out_mod_target       := DontCare 
            se_out_mod_behaviour    := DontCare
            se_out_mod_displacement := DontCare
            se_out_mod_size         := DontCare
        }



        /* STATE: transfering the configuration parameters of 
         * the stream to the Stream State
         */       
        is (STATE_COMMIT) {

            /* Update the descriptor points as the information is
             * being transfered to the Stream State
             */
            when (!next_write_dim(0)) {
                next_write_dim := VecInit(next_write_dim.tail :+ false.B)
            }

            when (!next_write_mod(0)) {
                next_write_mod := VecInit(next_write_mod.tail :+ false.B)
            }



            /* When all information has been sent to the Stream
             * State, reset the internal registers and transition
             * back to the first state
             */
            when (next_write_dim(0) && next_write_mod(0)) {
                state_reg := STATE_CONFIG

                dims_reg.foreach {x =>
                    x.offset := 0.U
                    x.stride := 0.U  
                    x.size   := 0.U      
                }

                mods_reg.foreach {x =>
                    x.target       := 0.U
                    x.behaviour    := BEHAVIOUR_INC
                    x.displacement := 0.U 
                    x.size         := 0.U
                    x.dimension    := 0.U 
                }

                vec_ena_reg := false.B
                vec_dim_reg := 0.U

                width_reg  := 0.U
                stream_reg := 0.U
                type_reg   := false.B

            }



            /* Reassignment of the output internal wires */
            cpu_out_ready := false.B

            se_out_valid := true.B
            se_out_ack   := next_write_dim(0) && next_write_mod(0)

            se_out_vec_we  := false.B
            se_out_vec_idx := DontCare

            se_out_cfg_we     := false.B
            se_out_cfg_width  := DontCare
            se_out_cfg_stream := stream_reg
            se_out_cfg_type   := DontCare

            se_out_dim_we     := !next_write_dim(0) 
            se_out_dim_idx    := last_dim_idx
            se_out_dim_offset := dims_reg(last_dim_idx).offset
            se_out_dim_stride := dims_reg(last_dim_idx).stride
            se_out_dim_size   := dims_reg(last_dim_idx).size

            se_out_mod_we           := !next_write_mod(0)
            se_out_mod_idx          := mods_reg(last_mod_idx).dimension
            se_out_mod_target       := mods_reg(last_mod_idx).target
            se_out_mod_behaviour    := mods_reg(last_mod_idx).behaviour
            se_out_mod_displacement := mods_reg(last_mod_idx).displacement
            se_out_mod_size         := mods_reg(last_mod_idx).size

        }

    }


    

    
    /* When the reset signal is activated, the registers of
     * the module will be updated to their default values
     */
    when (io.ctrl_reset) {

        dims_reg.foreach { x =>
            x.offset := 0.U
            x.stride := 0.U  
            x.size   := 0.U      
        }

        mods_reg.foreach { x =>
            x.target       := 0.U
            x.behaviour    := BEHAVIOUR_INC
            x.displacement := 0.U
            x.size         := 0.U
            x.dimension    := 0.U 
        }

        next_write_dim.foreach{x => x := false.B}
        next_write_dim(0) := true.B

        next_write_mod.foreach{x => x := false.B}
        next_write_mod(0) := true.B

        vec_ena_reg := false.B
        vec_dim_reg := 0.U

        width_reg  := 0.U
        stream_reg := 0.U
        type_reg   := false.B

        state_reg := 0.U

    }



    

    /* Connecting the output ports to some of 
     * the internal wires of the module
     */
    io.cpu_out_ready            := cpu_out_ready

    io.se_out_valid             := se_out_valid
    io.se_out_ack               := se_out_ack

    io.se_out_vec_we            := se_out_vec_we
    io.se_out_vec_idx           := se_out_vec_idx

    io.se_out_cfg_we            := se_out_cfg_we
    io.se_out_cfg_width         := se_out_cfg_width
    io.se_out_cfg_stream        := se_out_cfg_stream
    io.se_out_cfg_type          := se_out_cfg_type

    io.se_out_dim_we            := se_out_dim_we
    io.se_out_dim_idx           := se_out_dim_idx
    io.se_out_dim_offset        := se_out_dim_offset
    io.se_out_dim_stride        := se_out_dim_stride
    io.se_out_dim_size          := se_out_dim_size

    io.se_out_mod_we            := se_out_mod_we
    io.se_out_mod_idx           := se_out_mod_idx
    io.se_out_mod_target        := se_out_mod_target
    io.se_out_mod_behaviour     := se_out_mod_behaviour
    io.se_out_mod_displacement  := se_out_mod_displacement
    io.se_out_mod_size          := se_out_mod_size

}
