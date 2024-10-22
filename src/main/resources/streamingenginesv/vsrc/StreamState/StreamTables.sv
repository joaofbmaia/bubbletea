`timescale 1ns / 1ps

import engine_pkg::*;
module StreamTables #(
    parameter STREAM_NUM_DIMS = 4,
    parameter STREAM_NUM_MODS = 3,
    parameter SS_NUM_TABLES   = 4
) (
    input logic clk_i,
    input logic ctrl_reset_i,

    input logic new_cfg_i,     // Start of new configuration
    input logic append_cfg_i, // Append to current configuration
    input logic end_ack_i,     // End of Configuration Acknowledgement

    // Initial Stream Config
    input logic [1:0] width_i,
    input logic [STREAM_ID_WIDTH-1:0] stream_i,
    input stream_type_t type_i,
    input logic vec_we_i,
    input logic [$clog2(STREAM_NUM_DIMS)-1:0] vec_idx_i,
    output logic empty_ready_o,

    // Stream Configuration
    input logic dim_we,
    input logic [$clog2(STREAM_NUM_DIMS)-1:0] dim_idx,
    input logic [STREAM_OFFSET_WIDTH-1 : 0] dim_offset,
    input logic [STREAM_STRIDE_WIDTH-1 : 0] dim_stride,
    input logic [STREAM_SIZE_WIDTH-1 : 0] dim_size,

    input logic mod_we,
    input logic [$clog2(STREAM_NUM_DIMS)-1:0] mod_idx,
    input logic [1:0] mod_target,
    input logic [MOD_BEHAVIOUR_WIDTH-1:0] mod_behaviour,
    input logic [STREAM_STRIDE_WIDTH-1 : 0] mod_displacement,
    input logic [STREAM_SIZE_WIDTH-1 : 0] mod_size,

    input logic [$clog2(SS_NUM_TABLES)-1:0] lmmu_idx,
    input logic [$clog2(SS_NUM_TABLES)-1:0] smmu_idx,


    // Stream Update
    input stream_table_t table_i,
    input logic update_table_i,

    input logic mmu_trigger_i,
    input logic mmu_last_i,
    output logic [$clog2(SS_NUM_TABLES)-1:0] mmu_idx_o,

    // Stream Tables
    output stream_table_t table_o,
    output logic operate_table_o
);

  stream_table_t tables[SS_NUM_TABLES-1:0];
  logic [$clog2(SS_NUM_TABLES)-1:0] st_idx;

  logic [$clog2(SS_NUM_TABLES)-1 : 0] empty_table_idx;
  logic cfg_empty_ready;

  logic [$clog2(SS_NUM_TABLES)-1 : 0] append_table_idx;
  logic cfg_append_ready;


  logic [STREAM_NUM_DIMS-1 : 0] last_cfg_dim;

  logic [STREAM_NUM_DIMS-1 : 0] completed_dim;

  logic [SS_NUM_TABLES-1:0] arbiter_inputs;
  logic mmu_trigger;


  always_comb begin
    empty_table_idx  = 0;
    cfg_empty_ready  = 0;

    append_table_idx = 0;
    cfg_append_ready = 0;

    foreach (tables[i]) begin
      /* The Stream State searches for non-configured tables and
       * selects the first it finds for when a new stream configuration
       * takes places
       */
      if (tables[i].valid == 0) begin
        empty_table_idx = $clog2(SS_NUM_TABLES)'(i);
        cfg_empty_ready = 1;
      end

      /* After configuration of a new table, more configuration instruction
       * arrive with information regarding the descriptors of the stream
       */
      if (tables[i].stream == stream_i && tables[i].valid) begin
        append_table_idx = $clog2(SS_NUM_TABLES)'(i);
        cfg_append_ready = 1;
      end
    end
  end

  assign empty_ready_o = cfg_empty_ready;


  /* Determine the highest configured dimension, which
   * will be the active one when the stream receives the
   * acknowledge signal
   */

  always_comb begin
    last_cfg_dim = 0;
    for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
      if (tables[append_table_idx].descriptors[i].configured) begin
        last_cfg_dim = STREAM_NUM_DIMS'(i);
      end
    end
  end


  /* Detect if, in the moment the stream receives the acknowledge
   * signal, there are already dimensions with size zero. In those
   * cases, mark that dimension as completed
   */

  always_comb begin
    completed_dim = 0;
    for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
      completed_dim[i] = (tables[append_table_idx].descriptors[i].size < 1 && tables[append_table_idx].descriptors[i].configured);
    end
  end

  genvar k;

  for (k = 0; k < SS_NUM_TABLES; k++) begin
    assign arbiter_inputs[k] = tables[k].ready;
  end

  RoundRobinArbiter #(
      .INPUT_WIDTH(SS_NUM_TABLES)
  ) rr_arbiter (
      .clk_i(clk_i),
      .rst_n_i(ctrl_reset_i),
      .trigger(mmu_trigger),
      .inputs(arbiter_inputs),
      .output_idx(st_idx)
  );

  assign mmu_trigger = mmu_trigger_i || !tables[st_idx].ready || !tables[st_idx].valid;
  assign operate_table_o = !mmu_trigger;

  assign table_o = tables[st_idx];
  assign mmu_idx_o = tables[st_idx].mmu_idx;


  always_ff @(posedge clk_i) begin

    if (ctrl_reset_i) begin
      for (int i = 0; i < SS_NUM_TABLES; i++) begin
        tables[i].valid <= 0;
        tables[i].ready <= 0;
      end
    end else if (new_cfg_i && cfg_empty_ready) begin : new_cfg

      /* Update the stream header parameters */
      tables[empty_table_idx].width  <= width_i;
      tables[empty_table_idx].stream <= stream_i;
      tables[empty_table_idx]._type  <= type_i;

      /* When the stream has a cfg.vec instruction associated,
         * it marks the corresponding dimensions
         */
      if (vec_we_i) begin
        for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
          if (i <= vec_idx_i) begin
            tables[empty_table_idx].descriptors[i].vectorize <= 1;
          end
        end
      end

      /* During the configuration of a new table, reset all 
       * parameter fields of the descriptors
       */
      for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
        tables[empty_table_idx].descriptors[i].offset <= 0;
        tables[empty_table_idx].descriptors[i].stride <= 0;
        tables[empty_table_idx].descriptors[i].size <= 0;
        tables[empty_table_idx].descriptors[i].acc_offset <= 0;
        tables[empty_table_idx].descriptors[i].acc_stride <= 0;
        tables[empty_table_idx].descriptors[i].acc_size <= 0;
        tables[empty_table_idx].descriptors[i].iterations <= 0;
        tables[empty_table_idx].descriptors[i].configured <= 0;
        tables[empty_table_idx].descriptors[i].complete <= 0;
        tables[empty_table_idx].descriptors[i].vectorize <= 0;
      end

      for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
        for (int j = 0; j < STREAM_NUM_MODS; j++) begin
          tables[empty_table_idx].modifiers[i][j].behaviour <= 0;
          tables[empty_table_idx].modifiers[i][j].displacement <= 0;
          tables[empty_table_idx].modifiers[i][j].size <= 0;
          tables[empty_table_idx].modifiers[i][j].iterations <= 0;
          tables[empty_table_idx].modifiers[i][j].configured <= 0;
        end
      end

      for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
        tables[empty_table_idx].mod_acc_offset[i] <= 0;
      end

      for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
        tables[empty_table_idx].active_dim[i] <= 0;
      end

      tables[empty_table_idx].valid <= 1;
      tables[empty_table_idx].ready <= 0;

      /* Depending on the stream type, the index on the MMU tables
       * is saved in the Stream State
       */
      if (type_i == LOAD) begin
        tables[empty_table_idx].mmu_idx <= lmmu_idx;
      end else begin
        tables[empty_table_idx].mmu_idx <= smmu_idx;
      end
    end


    /* Append the new information by updating the descriptor registers
     * of the Stream State
     */
    if (append_cfg_i && cfg_append_ready) begin : append_cfg

      /* Configuring a new dimension descriptor */
      if (dim_we) begin
        tables[append_table_idx].descriptors[dim_idx].offset <= dim_offset;
        tables[append_table_idx].descriptors[dim_idx].stride <= dim_stride;
        tables[append_table_idx].descriptors[dim_idx].size <= dim_size;
        tables[append_table_idx].descriptors[dim_idx].acc_offset <= 0;
        tables[append_table_idx].descriptors[dim_idx].acc_stride <= 0;
        tables[append_table_idx].descriptors[dim_idx].acc_size <= 0;
        tables[append_table_idx].descriptors[dim_idx].iterations <= 0;
        tables[append_table_idx].descriptors[dim_idx].configured <= 1;
        tables[append_table_idx].descriptors[dim_idx].complete <= 0;
      end

      /* Configuring a new static modifier */
      if (mod_we) begin
        tables[append_table_idx].modifiers[mod_idx][mod_target].behaviour <= mod_behaviour;
        tables[append_table_idx].modifiers[mod_idx][mod_target].displacement <= mod_displacement;
        tables[append_table_idx].modifiers[mod_idx][mod_target].size <= mod_size;
        tables[append_table_idx].modifiers[mod_idx][mod_target].iterations <= mod_size;
        tables[append_table_idx].modifiers[mod_idx][mod_target].configured <= 1;
      end

      /* When the final configuration signal is provided, an acknowledge
       * signal is received to inform that the current stream will now be
       * ready to be iterated/processed
       */
      if (end_ack_i) begin
        /* Mark stream as ready to be processed */
        tables[append_table_idx].ready <= 1;

        /* Initialize the accumulation fields */
        for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
          tables[append_table_idx].descriptors[i].acc_offset <= tables[append_table_idx].descriptors[i].offset;
          tables[append_table_idx].descriptors[i].acc_stride <= tables[append_table_idx].descriptors[i].stride;
          tables[append_table_idx].descriptors[i].acc_size <= tables[append_table_idx].descriptors[i].size;
          tables[append_table_idx].descriptors[i].iterations <= tables[append_table_idx].descriptors[i].size;
        end

        /* Initialize the number of iterations of all modifiers */
        for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
          for (int j = 0; j < STREAM_NUM_MODS; j++) begin
            tables[append_table_idx].modifiers[i][j].iterations <= tables[append_table_idx].modifiers[i][j].size;
          end
        end

        for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
          tables[append_table_idx].active_dim[i] <= STREAM_NUM_DIMS'(i) == last_cfg_dim;
        end



        // Set the complete flags of dimensions according to theirs size fields

        for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
          tables[append_table_idx].descriptors[i].complete <= completed_dim[i];
        end

        if (last_cfg_dim == 0) begin
          tables[append_table_idx].state <= ADDRESS;
        end else begin
          tables[append_table_idx].state <= UPDATE;
        end
      end
    end

    if (update_table_i) begin : update_table
      tables[st_idx] <= table_i;
    end

    if (!mmu_trigger && mmu_last_i) begin : clear_table
      for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
        tables[st_idx].descriptors[i].offset <= '0;
        tables[st_idx].descriptors[i].stride <= '0;
        tables[st_idx].descriptors[i].size <= '0;
        tables[st_idx].descriptors[i].acc_offset <= '0;
        tables[st_idx].descriptors[i].acc_stride <= '0;
        tables[st_idx].descriptors[i].acc_size <= '0;
        tables[st_idx].descriptors[i].iterations <= '0;
        tables[st_idx].descriptors[i].configured <= '0;
        tables[st_idx].descriptors[i].complete <= '0;
        tables[st_idx].descriptors[i].vectorize <= '0;
      end

      for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
        for (int j = 0; j < STREAM_NUM_MODS; j++) begin
          tables[st_idx].modifiers[i][j].behaviour <= '0;
          tables[st_idx].modifiers[i][j].displacement <= '0;
          tables[st_idx].modifiers[i][j].size <= '0;
          tables[st_idx].modifiers[i][j].iterations <= '0;
          tables[st_idx].modifiers[i][j].configured <= '0;
        end
      end

      tables[st_idx].width  <= '0;
      tables[st_idx].stream <= '0;
      tables[st_idx]._type  <= STORE;

      tables[st_idx].valid  <= '0;
      tables[st_idx].ready  <= '0;

      for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
        tables[st_idx].active_dim[i] <= '0;
      end
      for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
        tables[st_idx].mod_acc_offset[i] <= '0;
      end
    end




  end


endmodule
