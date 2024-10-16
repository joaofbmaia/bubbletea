// `include "Engine/Arbiter/RoundRobinArbiter.sv"
// `include "Engine/StreamTables.sv"
`timescale 1ns / 1ps

import engine_pkg::*;
module StreamState #(
    parameter STREAM_NUM_DIMS = 4,
    parameter STREAM_NUM_MODS = 3,
    parameter SS_NUM_TABLES = 4,
    parameter STREAM_OFFSET_WIDTH = 32,
    parameter STREAM_STRIDE_WIDTH = 32,
    parameter STREAM_SIZE_WIDTH = 32,
    parameter STREAM_ID_WIDTH = 5
) (
    input logic clk_i,
    input logic ctrl_reset_i,

    /* Configuration Channel */
    input logic cfg_ack_i,  // End of Configuration Acknowledgement
    input logic cfg_new_i,  // Configuration Write Enable
    input logic cfg_append_i,  // Append to current configuration
    input logic [1:0] cfg_width_i,
    input logic [STREAM_ID_WIDTH-1:0] cfg_stream_i,  // Stream ID
    input stream_type_t cfg_type_i,  // Stream Load or Store
    input logic cfg_vec_we_i,  // Vectorization Write Enable                            
    input logic [$clog2(STREAM_NUM_DIMS)-1:0] cfg_vec_idx_i,  // Vectorization Index
    input logic cfg_dim_we_i,  // Dimension Write Enable
    input logic [$clog2(STREAM_NUM_DIMS)-1:0] cfg_dim_idx_i,  // Dimension Index
    input logic [STREAM_OFFSET_WIDTH-1 : 0] cfg_dim_offset_i,  // Dimension Offset
    input logic [STREAM_STRIDE_WIDTH-1 : 0] cfg_dim_stride_i,  // Dimension Stride
    input logic [STREAM_SIZE_WIDTH-1 : 0] cfg_dim_size_i,  // Dimension Size
    input logic cfg_mod_we_i,  // Modifier Write Enable
    input logic [$clog2(STREAM_NUM_DIMS)-1:0] cfg_mod_idx_i,  // Modifier Index
    input logic [1:0] cfg_mod_target_i,  // Modifier Target
    input logic [MOD_BEHAVIOUR_WIDTH-1:0] cfg_mod_behaviour_i,  // Modifier Behaviour (Add/Sub)
    input logic [STREAM_STRIDE_WIDTH-1 : 0] cfg_mod_displacement_i,  // Modifier Displacement
    input logic [STREAM_SIZE_WIDTH-1 : 0] cfg_mod_size_i,  // Modifier Size
    input logic [$clog2(SS_NUM_TABLES)-1:0] cfg_lmmu_idx_i,  // Load MMU Index
    input logic [$clog2(SS_NUM_TABLES)-1:0] cfg_smmu_idx_i,  // Store MMU Index

    output logic cfg_ready_o,  // Configuration Ready for address generation

    /* Memory Management Unit Channel */
    input logic mmu_trigger_i,
    output logic [$clog2(SS_NUM_TABLES)-1:0] mmu_idx_o,
    output logic [STREAM_ID_WIDTH-1:0] mmu_stream_o,
    output logic mmu_valid_o,
    output logic [STREAM_OFFSET_WIDTH-1:0] mmu_addr_o,
    output logic mmu_addr_valid_o,
    output logic [1:0] mmu_width_o,
    output logic [STREAM_NUM_DIMS-1:0] mmu_completed_o,
    output logic mmu_vectorize_o,
    output logic mmu_last_o,
    output logic mmu_type_o,

    /* Stream Iterator channel */
    input  logic [      STREAM_SIZE_WIDTH-1:0] si_res_iterations_i,
    input  logic [              WIDTH_MAX-1:0] si_res_accumulation_i,
    input  logic                               si_last_i,
    input  logic                               si_load_ena_i,
    input  logic [        STREAM_NUM_DIMS-1:0] si_load_dim_i,
    output logic [              WIDTH_MAX-1:0] si_op1_o,
    output logic [              WIDTH_MAX-1:0] si_op2_o,
    output logic                               si_mod_o,
    output logic [$clog2(STREAM_NUM_DIMS)-1:0] si_dim_o,
    output logic [                        1:0] si_width_o,
    output logic                               si_behaviour_o,
    output logic [      STREAM_SIZE_WIDTH-1:0] si_iterations_o,
    output logic [      STREAM_SIZE_WIDTH-1:0] si_size_o,
    output logic [        STREAM_NUM_DIMS-1:0] si_completed_o,
    output logic [        STREAM_NUM_DIMS-1:0] si_configured_o,

    output logic [63:0] hpc_ss_desc_o
);

  stream_table_t updated_st, st;
  logic update_table, operate_table;

  logic [STREAM_NUM_DIMS-1:0] dim_next_vec, dim_curr_vec;
  logic [$clog2(STREAM_NUM_DIMS)-1:0] dim_next_idx, dim_curr_idx, dim_load_idx;


  logic jump_state;
  logic [3:0] state_iter_reg, next_state_iter;
  logic [1:0] state_appl_reg, next_state_appl;

  logic [1:0] iter_option_idx;
  logic iter_option_null;


  logic [63:0] hpc_ss_desc_reg;

  logic [STREAM_NUM_DIMS-1:0] mmu_completed_vec, mmu_vectorize_vec, mmu_last_vec, forceComplete;
  logic vect_sizeZero_valid;
  int   vect_sizeZero_index;
  logic arbiter_mmu_trigger;

  assign arbiter_mmu_trigger = mmu_trigger_i || !st.ready || !st.valid;

  always_comb begin
    hpc_ss_desc_reg = (arbiter_mmu_trigger == 0 && st.active_dim[0] == 0) ? hpc_ss_desc_o + 1 : hpc_ss_desc_o;
  end


  assign mmu_stream_o = st.stream;
  assign mmu_valid_o = st.active_dim[0] && (mmu_addr_valid_o || mmu_vectorize_o);
  assign mmu_type_o = st._type;
  assign mmu_width_o = st.width;

  assign mmu_addr_o = st.descriptors[0].acc_offset;
  assign mmu_addr_valid_o = st.active_dim[0] && $signed(
      st.descriptors[0].acc_size
  ) > 0 && $signed(
      st.descriptors[0].acc_offset
  ) >= 0 && st.ready;


  always_comb begin
    for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
      mmu_completed_vec[i] = st.descriptors[i].complete;
      mmu_vectorize_vec[i] = !st.descriptors[i].configured || !st.descriptors[i].vectorize || (st.descriptors[i].vectorize &&  st.descriptors[i].complete);
      mmu_last_vec[i] = !st.descriptors[i].configured || (st.descriptors[i].configured && st.descriptors[i].complete);
    end

    if (vect_sizeZero_valid) begin
      mmu_vectorize_o = &mmu_vectorize_vec && st.descriptors[dim_curr_idx].acc_size == 0;
      mmu_last_o = &mmu_last_vec && st.descriptors[dim_curr_idx].acc_size == 0;
    end else begin
      mmu_vectorize_vec[0] = !st.descriptors[0].configured || !st.descriptors[0].vectorize || (st.descriptors[0].vectorize &&  (st.active_dim[0] && si_last_i));
      mmu_vectorize_o = &mmu_vectorize_vec;

      mmu_last_vec[0] = si_last_i && st.active_dim[0];
      mmu_last_o = &mmu_last_vec;
    end
  end

  assign mmu_completed_o = mmu_completed_vec;

  always_comb begin
    forceComplete = 0;

    vect_sizeZero_valid = 0;
    vect_sizeZero_index = 0;
    for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
      if (st.ready && st.descriptors[i].acc_size < 1 && st.descriptors[i].configured) begin
        vect_sizeZero_valid = 1;
        vect_sizeZero_index = i;
        break;
      end
      forceComplete[i] = i <= vect_sizeZero_index && vect_sizeZero_valid;
    end

  end



  StreamTables #(
      .STREAM_NUM_DIMS(STREAM_NUM_DIMS),
      .STREAM_NUM_MODS(STREAM_NUM_MODS),
      .SS_NUM_TABLES  (SS_NUM_TABLES)
  ) stream_tables (
      .clk_i(clk_i),
      .ctrl_reset_i(ctrl_reset_i),
      .new_cfg_i(cfg_new_i),
      .append_cfg_i(cfg_append_i),
      .end_ack_i(cfg_ack_i),

      .width_i(cfg_width_i),
      .stream_i(cfg_stream_i),
      .type_i(cfg_type_i),
      .vec_we_i(cfg_vec_we_i),
      .vec_idx_i(cfg_vec_idx_i),
      .empty_ready_o(cfg_ready_o),

      .dim_we(cfg_dim_we_i),
      .dim_idx(cfg_dim_idx_i),
      .dim_offset(cfg_dim_offset_i),
      .dim_stride(cfg_dim_stride_i),
      .dim_size(cfg_dim_size_i),

      .mod_we(cfg_mod_we_i),
      .mod_idx(cfg_mod_idx_i),
      .mod_target(cfg_mod_target_i),
      .mod_behaviour(cfg_mod_behaviour_i),
      .mod_displacement(cfg_mod_displacement_i),
      .mod_size(cfg_mod_size_i),

      .lmmu_idx(cfg_lmmu_idx_i),
      .smmu_idx(cfg_smmu_idx_i),

      .table_i(updated_st),
      .update_table_i(update_table),
      .table_o(st),
      .operate_table_o(operate_table),

      .mmu_trigger_i(mmu_trigger_i),
      .mmu_last_i(mmu_last_o),
      .mmu_idx_o(mmu_idx_o)
  );





  /* Defining the default values for some of the
   * declared internal wires
   */
  assign dim_curr_vec = st.active_dim;
  assign dim_next_vec = {1'b0, st.active_dim[STREAM_NUM_DIMS-1:1]};

  always_comb begin
    dim_curr_idx = ($clog2(STREAM_NUM_DIMS))'(STREAM_NUM_DIMS - 1);
    foreach (dim_curr_vec[i]) begin
      if (dim_curr_vec[i]) begin
        dim_curr_idx = ($clog2(STREAM_NUM_DIMS))'(i);
      end
    end
  end

  always_comb begin
    dim_next_idx = ($clog2(STREAM_NUM_DIMS))'(STREAM_NUM_DIMS - 1);
    foreach (dim_next_vec[i]) begin
      if (dim_next_vec[i]) begin
        dim_next_idx = ($clog2(STREAM_NUM_DIMS))'(i);
      end
    end
  end

  assign jump_state = state_appl_reg[APPL_DIM_OFFSET] && !state_appl_reg[APPL_MOD_OFFSET] ||
                      !state_appl_reg[APPL_DIM_OFFSET] && state_appl_reg[APPL_MOD_OFFSET];

  always_comb begin
    dim_load_idx = ($clog2(STREAM_NUM_DIMS))'(STREAM_NUM_DIMS - 1);
    foreach (si_load_dim_i[i]) begin
      if (si_load_dim_i[i]) begin
        dim_load_idx = ($clog2(STREAM_NUM_DIMS))'(i);
      end
    end
  end


  always_comb begin
    iter_option_idx  = 3;
    iter_option_null = 1;
    foreach (state_iter_reg[i]) begin
      if (state_iter_reg[i]) begin
        iter_option_idx  = ($clog2(4))'(i);
        iter_option_null = 0;
      end
    end
  end

  always_ff @(posedge clk_i) begin
    state_iter_reg <= next_state_iter;
    state_appl_reg <= next_state_appl;

    if (ctrl_reset_i) begin
      state_iter_reg <= 0;
      state_appl_reg <= 0;

      hpc_ss_desc_o  <= 0;
    end else begin
      hpc_ss_desc_o <= hpc_ss_desc_reg;
    end
  end

  for (genvar i = 0; i < STREAM_NUM_DIMS; i++) begin
    assign si_completed_o[i]  = st.descriptors[i].complete;
    assign si_configured_o[i] = st.descriptors[i].configured;
  end


  always_comb begin
    updated_st = st;

    si_op1_o = 0;
    si_op2_o = 0;
    si_iterations_o = 0;
    si_behaviour_o = 0;
    si_size_o = 1;
    si_mod_o = 0;
    si_dim_o = dim_curr_idx;
    si_width_o = 0;

    update_table = 0;


    next_state_iter = state_iter_reg;
    next_state_appl = state_appl_reg;
    if (operate_table) begin
      case (st.state)

        /* Selected stream is currently in the lowest dimension descriptor
         * and is generating valid addresses
         */
        ADDRESS: begin

          /* Its stops generating new addresses when all the iterations
           * of the lowest dimension have been processed. Jump to the
           * iteration of descriptors state and determine which descriptors
           * should be iterated
           */
          if (si_load_ena_i) begin
            updated_st.state = ITERATE;
            updated_st.active_dim = si_load_dim_i;

            next_state_iter[ITER_DIM] = st.descriptors[dim_load_idx].configured;
            next_state_iter[ITER_MOD_SIZE] = st.modifiers[dim_load_idx][TARGET_SIZE].configured;
            next_state_iter[ITER_MOD_STRIDE] = st.modifiers[dim_load_idx][TARGET_STRIDE].configured;
            next_state_iter[ITER_MOD_OFFSET] = st.modifiers[dim_load_idx][TARGET_OFFSET].configured;
          end

          /* Commit the new results */
          updated_st.descriptors[dim_curr_idx].acc_offset = si_res_accumulation_i;
          updated_st.descriptors[dim_curr_idx].iterations = si_res_iterations_i;

          /* Mark the dimension as completed in the last iteration */
          if (si_last_i) begin
            updated_st.descriptors[dim_curr_idx].complete = 1;
          end

          si_op1_o = st.descriptors[dim_curr_idx].acc_offset;
          si_op2_o = st.descriptors[dim_curr_idx].acc_stride;
          si_iterations_o = st.descriptors[dim_curr_idx].iterations;
          si_behaviour_o = BEHAVIOUR_INC;
          si_size_o = st.descriptors[dim_curr_idx].acc_size;
          si_mod_o = 0;
          si_dim_o = dim_curr_idx;
          si_width_o = st.width;

          update_table = 1;
        end

        ITERATE: begin
          if (iter_option_null) begin
            updated_st.state = APPLY;
            next_state_appl[APPL_DIM_OFFSET] = 1;
            next_state_appl[APPL_MOD_OFFSET] = st.modifiers[dim_curr_idx][TARGET_OFFSET].configured;

            for (int i = 0; i < STREAM_NUM_DIMS; i++) begin

              if (forceComplete[i]) begin
                updated_st.descriptors[i].complete = 1;
              end else if (i < dim_curr_idx) begin
                updated_st.descriptors[i].complete = st.descriptors[i].acc_size <= 1;
              end
            end
            update_table = 1;
          end else begin
            case (iter_option_idx)
              ITER_DIM: begin

                next_state_iter[ITER_DIM] = 0;
                /* Commit the new results */
                updated_st.descriptors[dim_curr_idx].acc_offset = si_res_accumulation_i;
                updated_st.descriptors[dim_curr_idx].iterations = si_res_iterations_i;

                /* Mark the dimension as completed in the last iteration */
                if (si_last_i) begin
                  updated_st.descriptors[dim_curr_idx].complete = 1;
                end

                /* Update wires that connect to the Stream Iterator */
                si_op1_o = st.descriptors[dim_curr_idx].acc_offset;
                si_op2_o = st.descriptors[dim_curr_idx].acc_stride;
                si_iterations_o = st.descriptors[dim_curr_idx].iterations;
                si_behaviour_o = BEHAVIOUR_INC;
                si_size_o = st.descriptors[dim_curr_idx].acc_size;
                si_mod_o = 0;
              end
              ITER_MOD_SIZE: begin
                next_state_iter[ITER_MOD_SIZE] = 0;
                /* Commit the new results */
                if (st.modifiers[dim_curr_idx][TARGET_SIZE].iterations == 0) begin
                  updated_st.descriptors[dim_next_idx].acc_size = st.descriptors[dim_next_idx].size;
                  updated_st.modifiers[dim_curr_idx][TARGET_SIZE].iterations = st.modifiers[dim_curr_idx][TARGET_SIZE].size;
                end else begin
                  updated_st.descriptors[dim_next_idx].acc_size = si_res_accumulation_i;
                  updated_st.modifiers[dim_curr_idx][TARGET_SIZE].iterations = si_res_iterations_i;
                end
                /* Update wires that connect to the Stream Iterator */
                si_op1_o = st.descriptors[dim_next_idx].acc_size;
                si_op2_o = st.modifiers[dim_curr_idx][TARGET_SIZE].displacement;
                si_iterations_o = st.modifiers[dim_curr_idx][TARGET_SIZE].iterations;
                si_behaviour_o = st.modifiers[dim_curr_idx][TARGET_SIZE].behaviour;
                si_size_o = st.modifiers[dim_curr_idx][TARGET_SIZE].size;
                si_mod_o = 1;

              end

              ITER_MOD_STRIDE: begin
                next_state_iter[ITER_MOD_STRIDE] = 0;
                /* Commit the new results */
                if (st.modifiers[dim_curr_idx][TARGET_STRIDE].iterations == 0) begin
                  updated_st.descriptors[dim_next_idx].acc_stride = st.descriptors[dim_next_idx].stride;
                  updated_st.modifiers[dim_curr_idx][TARGET_STRIDE].iterations = st.modifiers[dim_curr_idx][TARGET_STRIDE].size;
                end else begin
                  updated_st.descriptors[dim_next_idx].acc_stride = si_res_accumulation_i;
                  updated_st.modifiers[dim_curr_idx][TARGET_STRIDE].iterations = si_res_iterations_i;
                end
                /* Update wires that connect to the Stream Iterator */
                si_op1_o = st.descriptors[dim_next_idx].acc_stride;
                si_op2_o = st.modifiers[dim_curr_idx][TARGET_STRIDE].displacement;
                si_iterations_o = st.modifiers[dim_curr_idx][TARGET_STRIDE].iterations;
                si_behaviour_o = st.modifiers[dim_curr_idx][TARGET_STRIDE].behaviour;
                si_size_o = st.modifiers[dim_curr_idx][TARGET_STRIDE].size;
                si_mod_o = 1;
              end
              ITER_MOD_OFFSET: begin
                next_state_iter[ITER_MOD_OFFSET] = 0;

                /* Commit the new results */
                if (st.modifiers[dim_curr_idx][TARGET_OFFSET].iterations == 0) begin
                  updated_st.mod_acc_offset[dim_curr_idx] = 0;
                  updated_st.modifiers[dim_curr_idx][TARGET_OFFSET].iterations = st.modifiers[dim_curr_idx][TARGET_OFFSET].size;
                end else begin
                  updated_st.mod_acc_offset[dim_curr_idx] = si_res_accumulation_i;
                  updated_st.modifiers[dim_curr_idx][TARGET_OFFSET].iterations = si_res_iterations_i;
                end

                /* Update wires that connect to the Stream Iterator */
                si_op1_o = st.mod_acc_offset[dim_curr_idx];
                si_op2_o = st.modifiers[dim_curr_idx][TARGET_OFFSET].displacement;
                si_iterations_o = st.modifiers[dim_curr_idx][TARGET_OFFSET].iterations;
                si_behaviour_o = st.modifiers[dim_curr_idx][TARGET_OFFSET].behaviour;
                si_size_o = st.modifiers[dim_curr_idx][TARGET_OFFSET].size;
                si_mod_o = 1;
              end
            endcase

            update_table = 1;
          end

          //* Update wires that connect to the Stream Iterator */
          si_dim_o   = dim_curr_idx;
          si_width_o = st.width;
          si_size_o  = st.descriptors[dim_curr_idx].acc_size;

        end

        APPLY: begin
          if (jump_state) begin
            updated_st.state = st.active_dim[1] ? ADDRESS : UPDATE;
            updated_st.active_dim = dim_next_vec;
          end

          updated_st.descriptors[dim_next_idx].acc_offset = si_res_accumulation_i;

          if (state_appl_reg[APPL_DIM_OFFSET]) begin


            for (int i = 0; i < STREAM_NUM_MODS; i++) begin
              updated_st.modifiers[dim_next_idx][i].iterations = st.modifiers[dim_next_idx][i].size;
            end

            updated_st.descriptors[dim_next_idx].iterations = st.descriptors[dim_next_idx].acc_size;
            next_state_appl[APPL_DIM_OFFSET] = 0;

            /* Update wires that connect to the Stream Iterator */
            si_op1_o = st.descriptors[dim_curr_idx].acc_offset;
            si_op2_o = st.descriptors[dim_next_idx].offset;
            si_behaviour_o = BEHAVIOUR_INC;
            si_size_o = st.descriptors[dim_curr_idx].acc_size;
          end else begin
            /* Update wires that connect to the Stream Iterator */
            si_op1_o = st.mod_acc_offset[dim_curr_idx];
            si_op2_o = st.descriptors[dim_next_idx].acc_offset;
            si_behaviour_o = BEHAVIOUR_INC;
            si_size_o = st.descriptors[dim_curr_idx].acc_size;
          end

          update_table = 1;
        end

        UPDATE: begin
          updated_st.active_dim = si_load_ena_i ? si_load_dim_i : dim_next_vec;

          /* When the active dimension is the one right above the ground
         * dimension, the finite state machine jumps to the state
         * where addresses start being produced
         */
          updated_st.state = si_load_ena_i ? ITERATE : st.active_dim[1] ? ADDRESS : UPDATE;

          /* Commit the new results and reset the iteration count of the lower dimension */
          updated_st.descriptors[dim_next_idx].acc_offset = si_res_accumulation_i;

          for (int i = 0; i < STREAM_NUM_MODS; i++) begin
            updated_st.modifiers[dim_next_idx][i].iterations = st.modifiers[dim_next_idx][i].size;
          end

          updated_st.descriptors[dim_next_idx].iterations = st.descriptors[dim_next_idx].size;

          updated_st.mod_acc_offset[dim_curr_idx] = 0;
          updated_st.descriptors[dim_next_idx].acc_size = st.descriptors[dim_next_idx].size;
          updated_st.descriptors[dim_next_idx].acc_stride = st.descriptors[dim_next_idx].stride;

          si_op1_o = st.descriptors[dim_curr_idx].acc_offset;
          si_op2_o = st.descriptors[dim_next_idx].offset;
          si_behaviour_o = BEHAVIOUR_INC;
          si_size_o = st.descriptors[dim_curr_idx].acc_size;

          update_table = 1;
        end
      endcase

    end
  end

endmodule

