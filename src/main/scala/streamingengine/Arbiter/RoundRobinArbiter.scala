package streamingengine

import chisel3._
import chisel3.util._





/**
  *
  * 
  * @param INPUT_WIDTH - width of the input signal
  */
class RoundRobinArbiter(
    val INPUT_WIDTH: Int) 
extends Module {

    /* The set of input and output ports of the module */
	val io = IO(new Bundle {

		val reset   = Input(Bool())
		val trigger = Input(Bool())
		val input   = Input(Vec(INPUT_WIDTH, Bool()))

		val output  = Output(UInt(log2Ceil(INPUT_WIDTH).W))

	})



	/* This arbiter registers the last selected bit index in
     * order to accomplish the round robin behaviour
     */
	val state_current = Reg(UInt(log2Ceil(INPUT_WIDTH).W))


    
    /* An internal wire that holds the next bit to be selected.
     * By default it is zero (when none of the input bits are set)
     */
    val next_output = Wire(UInt(log2Ceil(INPUT_WIDTH).W))
    next_output := 0.U
    
    
    
    /* The next output is determined every cycle and is equal
     * to the next valid index, starting from the index saved
     * as internal state
     */
    for (i <- INPUT_WIDTH to 1 by -1) {
        when (io.input((state_current + i.U) % INPUT_WIDTH.U)) {
            next_output := (state_current + i.U) % INPUT_WIDTH.U
        }
    }
    
        

    /* The trigger input port updates the selected bit */
    when (io.trigger) {
        state_current := next_output
    }
    


    /* The reset port selects a new bit ignoring
     * the internal state of the arbiter
     */ 
    when (io.reset) {
        state_current := PriorityEncoder(io.input)
	}



    /* Connect the output port to the selected bit */
    io.output := state_current

}
