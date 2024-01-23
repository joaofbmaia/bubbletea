package simblocks

import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxResource





/**
  * 
  *
  * @param ADDR_WIDTH - width of the address port
  * @param DATA_WIDTH - width of the input and output data ports
  * @param NUM_BYTES  - number of bytes of the data word (must match the data witdh)
  */
class BlackBoxMemory(
	val ADDR_WIDTH: Int, 
	val DATA_WIDTH: Int, 
	val NUM_BYTES:  Int)
extends BlackBox(
	Map("ADDR_WIDTH" -> ADDR_WIDTH, 
		"DATA_WIDTH" -> DATA_WIDTH, 
		"NUM_BYTES"  -> NUM_BYTES))		 
with HasBlackBoxResource {

	/* The set of input and output ports of the module */
	val io = IO(new Bundle {

		val clkA 	= Input(Clock())
		val enaA 	= Input(Bool())
		val weA 	= Input(UInt(NUM_BYTES.W))
		val addrA 	= Input(UInt(ADDR_WIDTH.W))
		val dinA 	= Input(UInt(DATA_WIDTH.W))
		val doutA 	= Output(UInt(DATA_WIDTH.W))

		val clkB 	= Input(Clock())
		val enaB 	= Input(Bool())
		val weB 	= Input(UInt(NUM_BYTES.W))
		val addrB 	= Input(UInt(ADDR_WIDTH.W))
		val dinB 	= Input(UInt(DATA_WIDTH.W))
		val doutB 	= Output(UInt(DATA_WIDTH.W))

	})

	addResource("/BlackBoxMemory.v")

}





/**
  * 
  *
  * @param ADDR_WIDTH - width of the address port
  * @param DATA_WIDTH - width of the input and output data ports
  */
class DualPortMemory(
	val ADDR_WIDTH: Int, 
	val DATA_WIDTH: Int) 
extends Module {

	/* Internal parameters, calculated using external ones */
	val NUM_BYTES = DATA_WIDTH / 8



	/* The set of input and output ports of the module */
	val io = IO(new Bundle {
		val ena_a 	= Input(Bool())
		val we_a 	= Input(UInt(NUM_BYTES.W))
		val addr_a 	= Input(UInt(ADDR_WIDTH.W))
		val din_a 	= Input(UInt(DATA_WIDTH.W))
		val dout_a 	= Output(UInt(DATA_WIDTH.W))

		val ena_b 	= Input(Bool())
		val we_b 	= Input(UInt(NUM_BYTES.W))
		val addr_b 	= Input(UInt(ADDR_WIDTH.W))
		val din_b 	= Input(UInt(DATA_WIDTH.W))
		val dout_b 	= Output(UInt(DATA_WIDTH.W))
	})

	val BBMEM = Module(new BlackBoxMemory(ADDR_WIDTH, DATA_WIDTH, NUM_BYTES))

	BBMEM.io.clkA 	:= clock
	BBMEM.io.enaA 	:= io.ena_a
	BBMEM.io.weA 	:= io.we_a
	BBMEM.io.addrA 	:= io.addr_a
	BBMEM.io.dinA 	:= io.din_a
	io.dout_a 		:= BBMEM.io.doutA

	BBMEM.io.clkB 	:= clock
	BBMEM.io.enaB 	:= io.ena_b
	BBMEM.io.weB 	:= io.we_b
	BBMEM.io.addrB 	:= io.addr_b
	BBMEM.io.dinB 	:= io.din_b
	io.dout_b 		:= BBMEM.io.doutB

}
