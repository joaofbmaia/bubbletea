package simblocks

import chisel3._
import chisel3.util._

import scala.util.Random





/**
  * 
  *
  * @param STREAM_ADDR_WIDTH  - width of the addresses generated by the Streaming Engine
  * @param MEMORY_DATA_WIDTH  - width of the input and output data ports
  * @param MEMORY_ADDR_WIDTH  - width of the address port
  * @param AXI_R_DATA_WIDTH   - width of the AXI RDATA bus
  * @param AXI_W_DATA_WIDTH   - width of the AXI WDATA bus
  * @param MEMORY_READ_DELAY  - the amount of cycles a read request takes
  * @param MEMORY_WRITE_DELAY - the amount of cycles a write request takes
  */
class AxiMemory(
    val STREAM_ADDR_WIDTH:  Int,
    val MEMORY_DATA_WIDTH:  Int,
    val MEMORY_ADDR_WIDTH:  Int,
    val AXI_R_DATA_WIDTH:   Int,
    val AXI_W_DATA_WIDTH:   Int,
    val MEMORY_READ_DELAY:  Int,
    val MEMORY_WRITE_DELAY: Int)
extends Module {

    /* Internal parameters, calculated using external ones */
    val MEMORY_NUM_BYTES    = MEMORY_DATA_WIDTH / 8
    val MEMORY_OFFSET_WIDTH = log2Ceil(MEMORY_NUM_BYTES)

    val AXI_W_NUM_BYTES     = AXI_W_DATA_WIDTH / 8
    val AXI_W_OFFSET_WIDTH  = log2Ceil(AXI_W_NUM_BYTES)



    /* The set of input and output ports of the module */
    val io = IO(new Bundle {

        /* Control channel */
        val ctrl_reset = Input(Bool())

        /* External read channel */
        val read_enable = Input(Bool())
        val read_addr   = Input(UInt(MEMORY_ADDR_WIDTH.W))
        val read_data   = Output(UInt(MEMORY_DATA_WIDTH.W))

        /* External write channel */
        val write_enable = Input(Bool())
        val write_addr   = Input(UInt(MEMORY_ADDR_WIDTH.W))
        val write_data   = Input(UInt(MEMORY_DATA_WIDTH.W))
        val write_strb   = Input(UInt(MEMORY_NUM_BYTES.W))

        /* AXI Read Address channel */
        val ar_ready = Output(Bool())
        val ar_addr  = Input(UInt(STREAM_ADDR_WIDTH.W))
        val ar_valid = Input(Bool())
        val ar_len   = Input(UInt(8.W))
        val ar_size  = Input(UInt(3.W))
        val ar_burst = Input(UInt(2.W))

        /* AXI Read Data channel */
        val r_ready  = Input(Bool())
        val r_data   = Output(UInt(AXI_R_DATA_WIDTH.W))
        val r_valid  = Output(Bool())
        val r_last   = Output(Bool())
        val r_resp   = Output(UInt(2.W))

		/* AXI Write Address channel */
		val aw_ready = Output(Bool())
		val aw_valid = Input(Bool())
		val aw_addr  = Input(UInt(STREAM_ADDR_WIDTH.W))
        val aw_len   = Input(UInt(8.W)) // ARLEN[7:0]
        val aw_size  = Input(UInt(3.W)) // ARSIZE[2:0]
        val aw_burst = Input(UInt(2.W)) // ARBURST[1:0]

		/* AXI Write Data channel */
		val w_ready  = Output(Bool())
		val w_valid  = Input(Bool())
		val w_data   = Input(UInt(AXI_W_DATA_WIDTH.W))
		val w_strb   = Input(UInt((AXI_W_DATA_WIDTH / 8).W))
		val w_last   = Input(Bool())

		/* AXI Write Response channel */
		val b_resp   = Output(UInt(2.W))
		val b_valid  = Output(Bool())
		val b_ready  = Input(Bool()) 
    
    })
    


    /* This module has a memory controller which manages AXI
     * communication with the dual port memory. It requires
     * some registers to save the configuration values of 
     * the transactions that occur
     */
    val read_state_axi       = Reg(UInt(2.W))
    val read_delay_axi       = Reg(UInt(8.W))
    val read_len_axi         = Reg(UInt(8.W)) 
    val read_burst_count_axi = Reg(UInt(8.W))
    val read_data_axi        = Reg(UInt(MEMORY_DATA_WIDTH.W))

    val write_state_axi = Reg(UInt(2.W))
    val write_delay_axi = Reg(UInt(8.W))
    
    val aw_addr = Reg(UInt(STREAM_ADDR_WIDTH.W))
    val w_data  = Reg(UInt(AXI_W_DATA_WIDTH.W))
    val w_strb  = Reg(UInt((AXI_W_DATA_WIDTH / 8).W))
    


    

    /* Declaring internal wires */
    val read_data_axi_vec = Wire(Vec(MEMORY_NUM_BYTES, UInt(8.W)))
    
    val ar_ready = Wire(Bool())
    val r_data   = Wire(UInt(AXI_R_DATA_WIDTH.W))
    val r_valid  = Wire(Bool())
    val r_last   = Wire(Bool())
    val r_resp   = Wire(UInt(2.W))
    val aw_ready = Wire(Bool())
    val w_ready  = Wire(Bool())
    val b_resp   = Wire(UInt(2.W))
    val b_valid  = Wire(Bool())

    val ena_a 	= Wire(Bool())
    val we_a 	= Wire(UInt(MEMORY_NUM_BYTES.W))
    val addr_a 	= Wire(UInt(MEMORY_ADDR_WIDTH.W))
    val din_a 	= Wire(UInt(MEMORY_DATA_WIDTH.W))
    val ena_b 	= Wire(Bool())
    val we_b 	= Wire(UInt(MEMORY_NUM_BYTES.W))
    val addr_b 	= Wire(UInt(MEMORY_ADDR_WIDTH.W))
    val din_b 	= Wire(UInt(MEMORY_DATA_WIDTH.W))





    /* The data memory consists of a true dual port memory */
    val MEM = Module(new DualPortMemory(MEMORY_ADDR_WIDTH, MEMORY_DATA_WIDTH))

    /* Assigning the internal wires to dual port memory's ports */
    MEM.io.ena_a  := ena_a 
    MEM.io.we_a   := we_a
    MEM.io.addr_a := addr_a
    MEM.io.din_a  := din_a
    MEM.io.ena_b  := ena_b
    MEM.io.we_b   := we_b
    MEM.io.addr_b := addr_b
    MEM.io.din_b  := din_b





    /* This module provides the AXI protocol channels.
     * However, it has a read and a write port which can
     * be used for pre-configuration of the memory contents
     */

    /* Port-A is used for reading operations */
    when (io.read_enable) {
        MEM.io.ena_a  := true.B
        MEM.io.we_a   := 0.U
        MEM.io.addr_a := io.read_addr
        MEM.io.din_a  := DontCare
    }
    io.read_data := MEM.io.dout_a

    /* Port-B is used for writting operations */
    when (io.write_enable) {
        MEM.io.ena_b  := true.B
        MEM.io.we_b   := io.write_strb
        MEM.io.addr_b := io.write_addr
        MEM.io.din_b  := io.write_data
    }
    




    /* Defining the default values of the declared internal wires */
    ena_a  := false.B
    we_a   := false.B
    addr_a := DontCare
    din_a  := DontCare
    ena_b  := true.B
    we_b   := false.B
    addr_b := DontCare
    din_b  := DontCare

    for (i <- 0 until MEMORY_NUM_BYTES) {
        read_data_axi_vec(i) := read_data_axi((i + 1)*8 - 1, i*8)
    }
    
    ar_ready := DontCare
    r_data   := DontCare
    r_valid  := false.B
    r_last   := false.B
    r_resp   := DontCare
    
    aw_ready := false.B
    w_ready  := false.B
    b_resp   := DontCare
    b_valid  := false.B





    /* A finite state machine is controlling the AXI read
     * protocol operations.
     */
    switch(read_state_axi) {

        // MODE: Ready to receive read request
        is(0.U) {

            when(io.ar_valid) {
                ena_a 	    := true.B
                we_a 	    := 0.U
                addr_a      := io.ar_addr >> log2Ceil(MEMORY_NUM_BYTES) // obtain the memory row, ignore the offset
                din_a       := DontCare

                read_delay_axi          := MEMORY_READ_DELAY.U
                read_state_axi          := 1.U
                read_len_axi            := io.ar_len
                read_burst_count_axi    := 0.U
            }

            ar_ready    := true.B
            r_data      := DontCare
            r_valid     := false.B
            r_last      := false.B
            r_resp      := DontCare

        }

        // MODE: Simulate memory access latency
        is(1.U) {

            when(read_delay_axi === MEMORY_READ_DELAY.U) {
                read_data_axi := MEM.io.dout_a
            }

            when(read_delay_axi > 0.U) {
                read_delay_axi := read_delay_axi - 1.U
            }
            .otherwise {
                read_state_axi := 2.U
            }

            ena_a 	    := false.B
            we_a 	    := DontCare
            addr_a      := DontCare
            din_a       := DontCare

            ar_ready    := false.B
            r_data      := DontCare
            r_valid     := false.B
            r_last      := false.B
            r_resp      := DontCare

        }

        // MODE: Transfer beats/bursts of data
        is(2.U) {

            when(read_burst_count_axi < read_len_axi) {
                when (io.r_ready) {
                    read_burst_count_axi := read_burst_count_axi + 1.U
                }

                r_last := false.B
            }
            .otherwise {
                when (io.r_ready) {
                    read_state_axi := 0.U
                }

                r_last := true.B
            }

            ena_a 	    := false.B
            we_a 	    := DontCare
            addr_a      := DontCare
            din_a       := DontCare

            ar_ready    := false.B

            val byte_vec = Wire(Vec(AXI_R_DATA_WIDTH / 8, UInt(8.W)))
            for (i <- 0 until (AXI_R_DATA_WIDTH / 8)) {
                val index = Wire(UInt(MEMORY_ADDR_WIDTH.W))
                index := (read_burst_count_axi * (AXI_R_DATA_WIDTH / 8).U) + i.U
                byte_vec(i) := read_data_axi_vec(index)
            }

            r_data      := byte_vec.asUInt
            r_valid     := true.B
            r_resp      := "b00".U

        }

    }



    

    /* A finite state machine is controlling the AXI write
     * protocol operations.
     */
    switch(write_state_axi) {

        // MODE: Ready to receive new address
        is(0.U) {

            when(io.aw_valid) {
                write_state_axi := 1.U

                aw_addr := io.aw_addr
            }
    
            aw_ready    := true.B
            w_ready     := false.B
            b_resp      := DontCare
            b_valid     := false.B

            ena_b 	    := false.B
            we_b 	    := DontCare
            addr_b      := DontCare
            din_b       := DontCare

        }

        // MODE: Ready to receive write data
        is(1.U) {

            when(io.w_valid) {
                write_state_axi := 2.U

                w_data := io.w_data
                w_strb := io.w_strb

                write_delay_axi := MEMORY_WRITE_DELAY.U
            }

            aw_ready    := false.B
            w_ready     := true.B
            b_resp      := DontCare
            b_valid     := false.B

            ena_b 	    := false.B
            we_b 	    := DontCare
            addr_b      := DontCare
            din_b       := DontCare

        }

        // MODE: Simulating write latency
        is(2.U) {

            when(write_delay_axi > 0.U) {
                write_delay_axi := write_delay_axi - 1.U
                ena_b := false.B
            }
            .otherwise {
                write_state_axi := 3.U
                ena_b := true.B
            }


            val COUNT = MEMORY_NUM_BYTES / AXI_W_NUM_BYTES

            val we_b_vec  = Wire(Vec(COUNT, UInt((AXI_W_DATA_WIDTH / 8).W)))
            val din_b_vec = Wire(Vec(COUNT, UInt(AXI_W_DATA_WIDTH.W)))

            val offset = Wire(UInt((MEMORY_OFFSET_WIDTH - AXI_W_OFFSET_WIDTH).W))
            offset := aw_addr(MEMORY_OFFSET_WIDTH - 1, AXI_W_OFFSET_WIDTH)

            for (i <- 0 until COUNT) {
                when (i.U === offset) {
                    we_b_vec(i)  := w_strb
                    din_b_vec(i) := w_data
                }
                .otherwise {
                    we_b_vec(i)  := 0.U
                    din_b_vec(i) := 0.U
                }
            }

            we_b    := we_b_vec.asUInt
            din_b   := din_b_vec.asUInt

            addr_b  := aw_addr >> log2Ceil(MEMORY_NUM_BYTES) 

            aw_ready    := false.B
            w_ready     := false.B
            b_resp      := DontCare
            b_valid     := false.B

        }

        // MODE: Generate valid write response
        is(3.U) {

            when (io.b_ready) {
                write_state_axi := 0.U
            }

            aw_ready    := false.B
            w_ready     := false.B
            b_resp      := "b00".U
            b_valid     := true.B

            ena_b 	    := false.B
            we_b 	    := DontCare
            addr_b      := DontCare
            din_b       := DontCare
        }

    }





    /* When the reset signal is activated, the registers of
     * the module will be updated to their default values
     */
    when (io.ctrl_reset) {
        read_state_axi          := 0.U
        read_delay_axi          := 0.U
        read_len_axi            := 0.U
        read_burst_count_axi    := 0.U
        read_data_axi           := 0.U

        write_state_axi         := 0.U
        write_delay_axi         := 0.U
        aw_addr                 := 0.U
        w_data                  := 0.U
        w_strb                  := 0.U
    }





    /* Connecting the output ports of the module to
     * some of the internal wires
     */
    io.ar_ready     := ar_ready

    io.r_data       := r_data
    io.r_valid      := r_valid
    io.r_last       := r_last
    io.r_resp       := r_resp

    io.aw_ready     := aw_ready

    io.w_ready      := w_ready
    
    io.b_resp       := b_resp
    io.b_valid      := b_valid

}