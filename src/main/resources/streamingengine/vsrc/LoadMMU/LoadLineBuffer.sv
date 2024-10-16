`timescale 1ns / 1ps

import engine_pkg::*;


module LoadLineBuffer (
    input logic clk_i,
    input ctrl_reset_i,

    /* Data request channel */
    input  logic                           req_lrq_valid_i,
    input  logic [ADDRESS_TAG_WIDTH-1 : 0] req_lrq_addr_tag_i,
    output logic                           req_lrq_ready_o,

    /* Request solver channel */
    input  logic                              data_clear_i,
    output logic [ADDRESS_TAG_WIDTH-1:0]      data_addr_tag_o,
    output logic [    LLB_NUM_BYTES-1:0][7:0] data_bytes_o,
    output logic                              data_valid_o,

    /* AXI Read Address channel */
    input  logic                       ar_ready_i,
    output logic [ AXI_ADDR_WIDTH-1:0] ar_addr_o,
    output logic                       ar_valid_o,
    output logic [  AXI_LEN_WIDTH-1:0] ar_len_o,
    output logic [ AXI_SIZE_WIDTH-1:0] ar_size_o,
    output logic [AXI_BURST_WIDTH-1:0] ar_burst_o,

    /* AXI Read Data channel */
    output logic r_ready_o,
    input logic [AXI_R_DATA_WIDTH-1:0] r_data_i,
    input logic r_valid_i,
    input logic r_last_i,
    input logic [1:0] r_resp_i,

    /* Performance Counters */
    output logic [63:0] hpc_ops_load_o

);

  localparam LLB_OFFSET_WIDTH = $clog2(LLB_NUM_BYTES);
  int i, j;

  /* Performance Counters */
  logic [63:0] hpc_ops_load;


  /* 
   * The Load Line Buffer has several tables containing
   * rows of bytes which will be loaded from the Data
   * Memory module
   */

  llb_table_bundle_t [LLB_NUM_TABLES-1:0] llb_tables;
  llb_table_bundle_t updated_table;

  /* 
       * Some registers are required for the transactions
       * with the memory system
       */

  lmmu_state_t load_state, next_load_state;
  /// logic half_llb_row, next_half_llb_row;
  logic load_okay, next_load_okay;

  logic [$clog2(LLB_NUM_BYTES)-1:0] load_ptr, next_load_ptr;

  /* Internal Signals */

  logic req_lrq_out_ready;

  logic [ADDRESS_TAG_WIDTH-1:0] data_lrq_out_addr_tag;
  logic [LLB_NUM_BYTES-1 : 0][7 : 0] data_lf_out_bytes;
  logic data_out_valid;

  logic [AXI_ADDR_WIDTH-1:0] axi_ar_addr;
  logic axi_ar_valid, next_axi_ar_valid;
  logic [  AXI_LEN_WIDTH-1:0] axi_ar_len;
  logic [ AXI_SIZE_WIDTH-1:0] axi_ar_size;
  logic [AXI_BURST_WIDTH-1:0] axi_ar_burst;
  logic axi_r_ready, next_axi_r_ready;

  logic [LLB_NUM_TABLES-1:0] nr_empty_tables_vec;
  logic [$clog2(LLB_NUM_TABLES)-1:0] nr_empty_table_idx;
  logic nr_empty_table_valid;






  /* An arbiter selects from the Load Line Buffer one configured
     * but not yet valid table. The selected table will initiate
     * a load request to the data memory
     */

  logic arbiter_dm_trigger;
  logic [LLB_NUM_TABLES-1:0] arbiter_dm_input;
  logic [$clog2(LLB_NUM_TABLES)-1:0] arbiter_dm_output;

  RoundRobinArbiter #(
      .INPUT_WIDTH(LLB_NUM_TABLES)
  ) arbiter_dm (
      .clk_i(clk_i),
      .rst_n_i(ctrl_reset_i),
      .trigger(arbiter_dm_trigger),
      .inputs(arbiter_dm_input),
      .output_idx(arbiter_dm_output)
  );

  /* An arbiter routes valid tables of the Load Line Buffer to
     * the Load Request Queue and Load FIFO. The data will be used
     * to resolve enqueued requests
     */

  logic arbiter_lrq_trigger;
  logic [LLB_NUM_TABLES-1:0] arbiter_lrq_input;
  logic [$clog2(LLB_NUM_TABLES)-1:0] arbiter_lrq_output;

  RoundRobinArbiter #(
      .INPUT_WIDTH(LLB_NUM_TABLES)
  ) arbiter_lqr (
      .clk_i(clk_i),
      .rst_n_i(ctrl_reset_i),
      .trigger(arbiter_lrq_trigger),
      .inputs(arbiter_lrq_input),
      .output_idx(arbiter_lrq_output)
  );



  always_ff @(posedge clk_i) begin
    /* When the reset signal is activated, the registers of
     * the module will be updated to their default values
     */
    if (ctrl_reset_i) begin

      axi_ar_len   <= ((LLB_NUM_BYTES * 8) / AXI_R_DATA_WIDTH) - 1;
      axi_ar_size  <= (AXI_SIZE_WIDTH)'($clog2(AXI_R_DATA_WIDTH / 8));
      axi_ar_burst <= 2'b01;


      for (int i = 0; i < LLB_NUM_TABLES; i++) begin
        for (int j = 0; j < LLB_NUM_BYTES; j++) begin
          llb_tables[i].bytes[j] <= 8'b0;
        end
        llb_tables[i].address_tag <= 0;
        llb_tables[i]._config <= 0;
        llb_tables[i].valid <= 0;
      end

      load_ptr <= 0;
      // half_llb_row <= 0;
      load_okay <= 0;
      load_state <= ARREADY;

      hpc_ops_load_o <= 0;
    end else begin
      hpc_ops_load_o <= hpc_ops_load;


      if (nr_empty_table_valid && req_lrq_valid_i) begin
        for (int j = 0; j < LLB_NUM_BYTES; j++) begin
          llb_tables[nr_empty_table_idx].bytes[j] <= 8'b1;
        end

        llb_tables[nr_empty_table_idx].address_tag <= req_lrq_addr_tag_i;
        llb_tables[nr_empty_table_idx]._config <= 1;
        llb_tables[nr_empty_table_idx].valid <= 0;
      end

      if (data_clear_i) begin
        /* The Load Request Queue has no valid and pending requests with
         * the address tag provided by the Load Line Buffer. The resources
         * and, therefore, deallocated
         */
        llb_tables[arbiter_lrq_output]._config <= 0;
        llb_tables[arbiter_lrq_output].valid <= 0;
        llb_tables[arbiter_lrq_output].address_tag <= 0;
      end

      if (load_state == RVALID) begin
        llb_tables[arbiter_dm_output] <= updated_table;
      end

      load_ptr   <= next_load_ptr;
      load_okay  <= next_load_okay;
      load_state <= next_load_state;


    end
  end








  /* 
   * An arbiter selects from the Load Line Buffer one configured
   * but not yet valid table. The selected table will initiate
   * a load request to the data memory
   */
  genvar k;
  generate
    for (k = 0; k < LLB_NUM_TABLES; k++) begin
      assign arbiter_dm_input[k] = llb_tables[k]._config && !llb_tables[k].valid;
    end
  endgenerate



  /* Change the selected table when the current one is not configured or
   * when it is configured and the data is valid, meaning the load request
   * has already been fulfilled
   */
  assign arbiter_dm_trigger = !llb_tables[arbiter_dm_output]._config || (llb_tables[arbiter_dm_output].valid && llb_tables[arbiter_dm_output]._config);

  /* An arbiter routes valid tables of the Load Line Buffer to
     * the Load Request Queue and Load FIFO. The data will be used
     * to resolve enqueued requests
     */
  always_comb begin
    for (int i = 0; i < LLB_NUM_TABLES; i++) begin
      arbiter_lrq_input[i] = llb_tables[i].valid && llb_tables[i]._config;
    end
  end


  assign arbiter_lrq_trigger = data_clear_i || !(llb_tables[arbiter_lrq_output].valid && llb_tables[arbiter_lrq_output]._config);

  assign req_lrq_ready_o = (nr_empty_table_valid && req_lrq_valid_i) ? 1 : 0;
  assign data_bytes_o = llb_tables[arbiter_lrq_output].bytes;
  assign data_addr_tag_o = llb_tables[arbiter_lrq_output].address_tag;
  assign data_valid_o = llb_tables[arbiter_lrq_output]._config && llb_tables[arbiter_lrq_output].valid;


  always_comb begin
    for (int i = 0; i < LLB_NUM_TABLES; i++) begin
      nr_empty_tables_vec[i] = !llb_tables[i]._config;
    end
  end
  always_comb begin
    nr_empty_table_idx = 0;
    foreach (nr_empty_tables_vec[i]) begin
      if (nr_empty_tables_vec[i]) begin
        nr_empty_table_idx = ($clog2(LLB_NUM_TABLES))'(i);
      end
    end
  end

  assign nr_empty_table_valid = |nr_empty_tables_vec;


  assign axi_ar_addr = load_state == ARREADY ? llb_tables[arbiter_dm_output].address_tag << LLB_OFFSET_WIDTH : '0;
  assign axi_ar_valid =  load_state == ARREADY ? llb_tables[arbiter_dm_output]._config & ~llb_tables[arbiter_dm_output].valid : 1'b0;
  assign axi_r_ready = load_state == RVALID ? 1 : 0;


  /* A finite state machine manages the AXI protocol
     * transactions. It transitions to different states
     * as data is being accepted and communicated with
     * the Data Memory
     */
  always_comb begin

    //next_half_llb_row = half_llb_row;
    next_load_okay = load_okay;
    next_load_state = load_state;


    updated_table = llb_tables[arbiter_dm_output];

    hpc_ops_load = hpc_ops_load_o;

    next_load_ptr = load_ptr;


    case (load_state)

      /* STATE: waiting for the ARREADY AXI signal */
      ARREADY: begin
        if (ar_ready_i && axi_ar_valid) begin
          // next_half_llb_row = 0;
          next_load_ptr = 0;
          next_load_okay = 1;
          next_load_state = RVALID;

          /* Memory controller accepted a new load request. Increment the
           * performance counter regarding the total number of load requests
           */

          hpc_ops_load = hpc_ops_load_o + 1;

        end

      end

      /* STATE: waiting for the RVALID AXI signals */
      RVALID: begin
        if (r_valid_i) begin



          // Transfer the data bytes to the respective table of the Load Line Buffer

          for (i = 0; i < AXI_R_DATA_WIDTH / 8; i = i + 1) begin
            updated_table.bytes[load_ptr+($clog2(LLB_NUM_BYTES))'(i)] = r_data_i[8*(i+1)-1-:8];
          end
          next_load_ptr = load_ptr + ($clog2(LLB_NUM_BYTES))'(AXI_R_DATA_WIDTH / 8);

          // if (!half_llb_row) begin
          //   
          // end else begin
          //   for (i = 0; i < AXI_R_DATA_WIDTH / 8; i = i + 1) begin
          //     updated_table.bytes[16+($clog2(LLB_NUM_BYTES))'(i)] = r_data_i[8*(i+1)-1-:8];
          //   end
          // end
          // next_half_llb_row = ~half_llb_row;

          // Detect if the current transaction was successful
          if (r_resp_i != 2'b00) begin
            next_load_okay = 0;
          end

          // When the last beat of the burst is sent the respective table of the Load Line Buffer is set to valid if there were no errors during data transactions
          if (r_last_i) begin
            if (load_okay && r_resp_i == 2'b00) begin
              updated_table.valid = 1;
            end
            next_load_state = ARREADY;
          end
        end
      end

    endcase
  end


  assign ar_addr_o  = axi_ar_addr;
  assign ar_valid_o = axi_ar_valid;
  assign ar_len_o   = axi_ar_len;
  assign ar_size_o  = axi_ar_size;
  assign ar_burst_o = axi_ar_burst;

  assign r_ready_o  = axi_r_ready;
endmodule
