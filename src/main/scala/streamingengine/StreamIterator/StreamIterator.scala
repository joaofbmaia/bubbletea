package streamingengine

import chisel3._
import chisel3.util._




/**
  * 
  *
  * @param STREAM_NUM_DIMS     - number of dimensions the Stream Engine supports
  * @param STREAM_OFFSET_WIDTH - width of the offset field of a stream
  * @param STREAM_STRIDE_WIDTH - width of the stride field of a stream
  * @param STREAM_SIZE_WIDTH   - width of the size field of a stream
  */
class StreamIterator(
    val STREAM_NUM_DIMS:     Int,                    
    val STREAM_OFFSET_WIDTH: Int,
    val STREAM_STRIDE_WIDTH: Int, 
    val STREAM_SIZE_WIDTH:   Int)
extends Module {
    
    /* Internal parameters, calculated using external ones */
    val MAX_WIDTH = Seq(STREAM_OFFSET_WIDTH, STREAM_STRIDE_WIDTH, STREAM_SIZE_WIDTH).max
    
    

    /* The set of input and output ports of the module */
    val io = IO(new Bundle {

        val src_op1 = Input(UInt(MAX_WIDTH.W))
        val src_op2 = Input(UInt(MAX_WIDTH.W))
        
        val val_dim        = Input(UInt(log2Ceil(STREAM_NUM_DIMS).W))
        val val_mod        = Input(Bool())
        val val_width      = Input(UInt(2.W))
        val val_behaviour  = Input(Bool())
        val val_iterations = Input(UInt(STREAM_SIZE_WIDTH.W))
        val val_size       = Input(UInt(STREAM_SIZE_WIDTH.W))   
        val val_completed  = Input(Vec(STREAM_NUM_DIMS, Bool()))
        val val_configured = Input(Vec(STREAM_NUM_DIMS, Bool()))
        val val_last       = Output(Bool())
        
        val res_iterations   = Output(UInt(STREAM_SIZE_WIDTH.W))
        val res_accumulation = Output(UInt(MAX_WIDTH.W))
        
        val load_ena = Output(Bool())
        val load_dim = Output(Vec(STREAM_NUM_DIMS, Bool()))
    
    })


    
    /* Macro definitions */
    val BEHAVIOUR_INC = false.B
    val BEHAVIOUR_DEC = true.B





    /* Internal signal declarations */
    val src_op1 = Wire(SInt(MAX_WIDTH.W))
    val src_op2 = Wire(SInt(MAX_WIDTH.W))

    val load_ena = Wire(Bool())

    val val_last = Wire(Bool())

    val next_dim_valid = Wire(Vec(STREAM_NUM_DIMS, Bool()))
    val next_dim_oh    = Wire(Vec(STREAM_NUM_DIMS, Bool()))

    val next_iterations = Wire(SInt(STREAM_SIZE_WIDTH.W))
    val next_accumulation = Wire(SInt(MAX_WIDTH.W))





    /* The current number of iterations is by default decremented */
    next_iterations   := io.val_iterations.asSInt - 1.S
    next_accumulation := DontCare





    /* It is the last iteration when the next iterations value is lower than one */
    when (io.val_dim === 0.U) {
        val_last := io.val_iterations === 0.U || next_iterations < 1.S
    }
    .otherwise {
        val_last := io.val_iterations === 0.U || next_iterations <= 1.S
    }





    /* The Stream Iterator detects if the active state (dimension) of the
     * stream should be updated. This happens when the size is zero or
     * when the lowest dimension has completed all its iterations
     */
    load_ena := io.val_size === 0.U || (val_last && io.val_dim === 0.U)





    /* The next active state (dimension) is always determined according
     * to the configured and completed dimensions of the current stream
     */
    for (i <- 0 until STREAM_NUM_DIMS) {
        next_dim_valid(i) := !(i.U <= io.val_dim) && io.val_configured(i) && !io.val_completed(i) 
    }
    next_dim_oh := PriorityEncoderOH(next_dim_valid)





    /* Converting the input source operands to signed representations */
    src_op1 := io.src_op1.asSInt
    src_op2 := io.src_op2.asSInt

    



    /* When the second source operand is a stride, its value is shifted
     * according to the elements width. This way there is always memory
     * alignment of the data elements
     */
    when (!io.val_mod) {
        switch(io.val_width) {
            is("b00".U) {src_op2 := io.src_op2.asSInt}        // 1-byte elements
            is("b01".U) {src_op2 := io.src_op2.asSInt << 1}   // 2-byte elements
            is("b10".U) {src_op2 := io.src_op2.asSInt << 2}   // 4-byte elements
            is("b11".U) {src_op2 := io.src_op2.asSInt << 3}   // 8-byte elements
        }
    }
    .otherwise {
        src_op2 := io.src_op2.asSInt
    }





    /* Depending on the 'behaviour', the source operands are added or subtracted */
    switch (io.val_behaviour) {

        is (BEHAVIOUR_INC) {
            next_accumulation := src_op1 + src_op2  
        }

        is (BEHAVIOUR_DEC) {
            next_accumulation := src_op1 - src_op2  
        }

    }


    


    /* Connecting the output ports to some of 
     * the internal wires of the module
     */
    io.val_last := val_last

    io.load_ena := load_ena
    io.load_dim := next_dim_oh

    io.res_accumulation := next_accumulation.asUInt
    io.res_iterations   := next_iterations.asUInt

}
