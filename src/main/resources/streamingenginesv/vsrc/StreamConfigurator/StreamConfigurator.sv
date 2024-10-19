`timescale 1ns / 1ps

module StreamConfigurator
  import engine_pkg::*;
(
    input logic clk_i,
    input logic ctrl_reset_i,

    input logic ready_i,  // Configuration Ready for address generation


    input logic se_ready_load_i,
    input logic se_ready_store_i,

    // output logic se_valid_o,  // Configuration Valid
    output logic se_ack_o,  // End of Configuration Acknowledgement
    output logic se_we_o,  // Configuration Write Enable
    output logic se_append_o,  // Append to current configuration 
    output logic [1:0] se_width_o,
    output logic [STREAM_ID_WIDTH-1:0] se_stream_o,  // Stream ID
    output stream_type_t se_type_o,  // Stream Load or Store
    output logic se_vec_we_o,  // Vectorization Write Enable                            
    output logic [$clog2(STREAM_NUM_DIMS)-1:0] se_vec_idx_o,  // Vectorization Index
    output logic se_dim_we_o,  // Dimension Write Enable
    output logic [$clog2(STREAM_NUM_DIMS)-1:0] se_dim_idx_o,  // Dimension Index
    output logic [STREAM_OFFSET_WIDTH-1 : 0] se_dim_offset_o,  // Dimension Offset
    output logic [STREAM_STRIDE_WIDTH-1 : 0] se_dim_stride_o,  // Dimension Stride
    output logic [STREAM_SIZE_WIDTH-1 : 0] se_dim_size_o,  // Dimension Size
    output logic se_mod_we_o,  // Modifier Write Enable
    output logic [$clog2(STREAM_NUM_DIMS)-1:0] se_mod_idx_o,  // Modifier Index
    output logic [1:0] se_mod_target_o,  // Modifier Target
    output logic [MOD_BEHAVIOUR_WIDTH-1:0] se_mod_behaviour_o,  // Modifier Behaviour (Add/Sub)
    output logic [STREAM_STRIDE_WIDTH-1 : 0] se_mod_displacement_o,  // Modifier Displacement
    output logic [STREAM_SIZE_WIDTH-1 : 0] se_mod_size_o,  // Modifier Size


    input logic cpu_valid_i,
    input logic cpu_sta_i,
    input logic cpu_end_i,
    input stream_type_t cpu_type_i,
    input logic [1:0] cpu_width_i,
    input logic [STREAM_ID_WIDTH-1:0] cpu_stream_i,
    input logic cpu_mod_i,
    input logic cpu_vec_i,
    input logic [STREAM_OFFSET_WIDTH-1:0] cpu_dim_offset_i,
    input logic [STREAM_STRIDE_WIDTH-1:0] cpu_dim_stride_i,
    input logic [STREAM_SIZE_WIDTH-1:0] cpu_dim_size_i,
    input logic [1:0] cpu_mod_target_i,
    input logic [MOD_BEHAVIOUR_WIDTH-1:0] cpu_mod_behaviour_i,
    input logic [STREAM_STRIDE_WIDTH-1:0] cpu_mod_displacement_i,
    input logic [STREAM_SIZE_WIDTH-1:0] cpu_mod_size_i,
    output logic cpu_ready_o

);

  localparam MAX_NUM_MODS = STREAM_NUM_MODS * (STREAM_NUM_DIMS - 1);

  /* 
  * Internal registers declarations. The Stream Configuration
  * module will receiving multiple separated configuration 
  * instructions that together define a memory access pattern.
  * The information provided by each instruction is stored
  * in internal registers and then used to configure the Stream
  * State with a new stream
 */


  sc_dim_descriptor_t [STREAM_NUM_DIMS-1:0] dims_reg, next_dims;
  sc_mod_descriptor_t [MAX_NUM_MODS-1:0] mods_reg, next_mods;

  logic [STREAM_NUM_DIMS:0] write_dim, next_write_dim;
  logic [MAX_NUM_MODS:0] write_mod, next_write_mod;

  logic [1:0] width_reg, next_width;
  logic [STREAM_ID_WIDTH-1:0] stream_reg, next_stream;
  stream_type_t type_reg, next_type;

  logic vec_ena_reg, next_vec_ena;
  logic [$clog2(STREAM_NUM_DIMS)-1:0] next_vec_dim, vec_dim_reg;

  config_state_t state_reg, next_state_reg, state;


  /* Internal wire declaration */
  logic [STREAM_NUM_DIMS-1:0] last_dim_oh;
  logic [$clog2(STREAM_NUM_DIMS)-1:0] last_dim_idx;

  logic [MAX_NUM_MODS-1:0] last_mod_oh;
  logic [$clog2(MAX_NUM_MODS)-1:0] last_mod_idx;

  logic se_in_ready;

  logic se_out_valid;
  logic se_out_ack;

  logic se_out_vec_we;
  logic [$clog2(STREAM_NUM_DIMS)-1:0] se_out_vec_idx;

  logic se_out_cfg_we;
  stream_type_t se_out_cfg_type;
  logic [1:0] se_out_cfg_width;
  logic [STREAM_ID_WIDTH-1:0] se_out_cfg_stream;

  logic se_out_dim_we;
  logic [$clog2(STREAM_NUM_DIMS)-1:0] se_out_dim_idx;
  logic [STREAM_OFFSET_WIDTH-1:0] se_out_dim_offset;
  logic [STREAM_STRIDE_WIDTH-1:0] se_out_dim_stride;
  logic [STREAM_SIZE_WIDTH-1:0] se_out_dim_size;

  logic se_out_mod_we;
  logic [$clog2(STREAM_NUM_DIMS)-1:0] se_out_mod_idx;
  logic [1:0] se_out_mod_target;
  logic se_out_mod_behaviour;
  logic [STREAM_STRIDE_WIDTH-1:0] se_out_mod_displacement;
  logic [STREAM_SIZE_WIDTH-1:0] se_out_mod_size;

  logic [3:0] test, next_test;


  assign last_dim_oh = {1'b0, write_dim[STREAM_NUM_DIMS-1:1]};

  always_comb begin
    last_dim_idx = ($clog2(STREAM_NUM_DIMS))'(STREAM_NUM_DIMS - 1);
    foreach (last_dim_oh[i]) begin
      if (last_dim_oh[i]) begin
        last_dim_idx = ($clog2(STREAM_NUM_DIMS))'(i);
      end
    end
  end

  assign last_mod_oh = {1'b0, write_mod[MAX_NUM_MODS-1:1]};
  always_comb begin
    last_mod_idx = ($clog2(MAX_NUM_MODS))'(MAX_NUM_MODS - 1);
    foreach (last_mod_oh[i]) begin
      if (last_mod_oh[i]) begin
        last_mod_idx = ($clog2(MAX_NUM_MODS))'(i);
      end
    end
  end





  assign se_in_ready = (se_ready_load_i && type_reg == LOAD) || (se_ready_store_i && type_reg == STORE);



  always_ff @(posedge clk_i) begin
    if (ctrl_reset_i) begin
      state <= _CONFIG;

      for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
        dims_reg[i].offset <= 0;
        dims_reg[i].stride <= 0;
        dims_reg[i].size   <= 0;
      end

      for (int i = 0; i < MAX_NUM_MODS; i++) begin
        mods_reg[i].target <= 0;
        mods_reg[i].behaviour <= 0;
        mods_reg[i].displacement <= 0;
        mods_reg[i].size <= 0;
        mods_reg[i].dimension <= 0;
      end

      for (int i = 1; i < STREAM_NUM_DIMS; i++) begin
        write_dim[i] <= 0;
      end
      write_dim[0] <= 1;

      for (int i = 1; i < MAX_NUM_MODS; i++) begin
        write_mod[i] <= 0;
      end
      write_mod[0] <= 1;

      vec_ena_reg <= 0;
      vec_dim_reg <= 0;

      width_reg <= 0;
      stream_reg <= 0;
      type_reg <= LOAD;

    end else begin
      vec_ena_reg <= next_vec_ena;
      vec_dim_reg <= next_vec_dim;
      state <= next_state_reg;

      width_reg <= next_width;
      stream_reg <= next_stream;
      type_reg <= next_type;


      write_dim <= next_write_dim;
      write_mod <= next_write_mod;

      for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
        dims_reg[i] <= next_dims[i];
      end

      for (int i = 0; i < MAX_NUM_MODS; i++) begin
        mods_reg[i] <= next_mods[i];
      end
    end
  end

  // assign next_state_reg = (state == _CONFIG) ? (cpu_valid_i && cpu_end_i ? WAIT : _CONFIG) : 
  //                         (state == WAIT)    ? (se_in_ready ? COMMIT : WAIT) : 
  //                         (state == COMMIT)  ? (write_dim[0] && write_mod[0] ? _CONFIG : COMMIT) :
  //                         _CONFIG;

  assign next_write_dim = (state == _CONFIG) ? (cpu_valid_i && !cpu_vec_i && !cpu_mod_i ? {write_dim[STREAM_NUM_DIMS-1:0], 1'b0} : write_dim) :
                          (state == COMMIT)  ? (!write_dim[0] ? {1'b0, write_dim[STREAM_NUM_DIMS:1]} : write_dim) :           //Update the descriptor points as the information is being transfered to the Stream State
                          write_dim;

  assign next_write_mod = (state == _CONFIG) ? (cpu_valid_i && !cpu_vec_i && cpu_mod_i ? {write_mod[MAX_NUM_MODS-1:0], 1'b0} : write_mod) :
                          (state == COMMIT)  ? (!write_mod[0] ? {1'b0, write_mod[MAX_NUM_MODS:1]} : write_mod) :
                          write_mod;



  assign cpu_ready_o = (state == _CONFIG) ? 1 : 0;

  assign se_ack_o = (state == _CONFIG) ? 1 :
                    (state == WAIT)    ? 0 : 
                    (state == COMMIT && (write_dim[0] && write_mod[0])) ? 1 : 0;


  assign se_vec_we_o = (state == WAIT) ? vec_ena_reg : 0;
  assign se_vec_idx_o = (state == WAIT) ? vec_dim_reg : 0;
  assign se_we_o = (state == WAIT) ? 1 : 0;
  assign se_append_o = (state == COMMIT) ? 1 : 0;
  assign se_width_o = width_reg;
  assign se_stream_o = stream_reg;
  assign se_type_o = type_reg;



  assign se_dim_we_o = (state == COMMIT && !write_dim[0]) ? 1 : 0;
  assign se_dim_idx_o = last_dim_idx;
  assign se_dim_offset_o = (state == COMMIT) ? dims_reg[last_dim_idx].offset : 0;
  assign se_dim_stride_o = (state == COMMIT) ? dims_reg[last_dim_idx].stride : 0;
  assign se_dim_size_o = (state == COMMIT) ? dims_reg[last_dim_idx].size : 0;


  assign se_mod_we_o = (state == COMMIT && !write_mod[0]) ? 1 : 0;
  assign se_mod_idx_o = (state == COMMIT) ? mods_reg[last_mod_idx].dimension : 0;
  assign se_mod_target_o = (state == COMMIT) ? mods_reg[last_mod_idx].target : 0;
  assign se_mod_behaviour_o = (state == COMMIT) ? mods_reg[last_mod_idx].behaviour : 0;
  assign se_mod_displacement_o = (state == COMMIT) ? mods_reg[last_mod_idx].displacement : 0;
  assign se_mod_size_o = (state == COMMIT) ? mods_reg[last_mod_idx].size : 0;




  always_comb begin
    next_stream = stream_reg;
    next_type = type_reg;
    next_width = width_reg;
    next_vec_ena = vec_ena_reg;

    for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
      next_dims[i] = dims_reg[i];
    end

    for (int i = 0; i < MAX_NUM_MODS; i++) begin
      next_mods[i] = mods_reg[i];
    end

    next_vec_dim   = vec_dim_reg;
    next_state_reg = state;

    /* A finite state machine controls the stream configuration.
     * In the first state the processing unit feeds the Streaming
     * Engine with configuration instructions, describing the 
     * memory access pattern. In the second the Stream Configuration
     * module waits until the Streaming Engine is ready to receive
     * the information. In the third, that information is transfered
     */
    case (state)
      /* STATE: receiving configuration instructions from
       * the processing unit
       */
      _CONFIG: begin
        /* The processing unit is providing a new valid
         * configuration instruction
         */
        if (cpu_valid_i) begin
          /* It is the first configuration instruction,
          * save the header information */
          if (cpu_sta_i) begin
            next_type   = cpu_type_i;
            next_width  = cpu_width_i;
            next_stream = cpu_stream_i;
          end

          /* It is the last configuration instruction,
           * transition to the the STATE_WAIT state
           */
          if (cpu_end_i) begin
            next_state_reg = WAIT;
            /* Currently vectorizes in the last instruction
             * if the vectorization instruction was not called
             */
            if (!vec_ena_reg) begin
              next_vec_ena = 1;

              for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
                if (write_dim[i]) begin
                  next_vec_dim = ($clog2(STREAM_NUM_DIMS))'(i);
                  break;
                end
              end
            end
          end

          /* The cfg.vec instruction will vectorize the vectors
           * produced in the until now defined dimension
           */

          if (cpu_vec_i) begin
            if (!vec_ena_reg) begin
              next_vec_ena = 1;
              next_vec_dim = last_dim_idx;
            end
          end else begin
            /* If not a cfg.vec instruction, a dimension or modifiers
             * is being attached to the current configuration
             */
            if (cpu_mod_i) begin
              for (int i = 0; i < MAX_NUM_MODS; i++) begin
                if (write_mod[i]) begin
                  next_mods[i].target = cpu_mod_target_i;
                  next_mods[i].behaviour = cpu_mod_behaviour_i;
                  next_mods[i].displacement = cpu_mod_displacement_i;
                  next_mods[i].size = cpu_mod_size_i;
                  next_mods[i].dimension = last_dim_idx;
                end
              end
            end else begin
              for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
                if (write_dim[i]) begin
                  next_dims[i].offset = cpu_dim_offset_i;
                  next_dims[i].stride = cpu_dim_stride_i;
                  next_dims[i].size   = cpu_dim_size_i;
                end
              end
            end
          end

        end

      end
      WAIT: begin
        if (se_in_ready) begin
          next_state_reg = COMMIT;
        end


      end
      COMMIT: begin
        /* When all information has been sent to the Stream
         * State, reset the internal registers and transition
         * back to the first state
         */

        if (write_dim[0] && write_mod[0]) begin

          next_state_reg = _CONFIG;

          for (int i = 0; i < STREAM_NUM_DIMS; i++) begin
            next_dims[i].offset = 0;
            next_dims[i].stride = 0;
            next_dims[i].size   = 0;
          end

          for (int i = 0; i < MAX_NUM_MODS; i++) begin
            next_mods[i].target = 0;
            next_mods[i].behaviour = 0;
            next_mods[i].displacement = 0;
            next_mods[i].size = 0;
            next_mods[i].dimension = 0;
          end

          next_vec_ena = 0;
          next_vec_dim = 0;

          next_width = 0;
          next_stream = 0;
          next_type = LOAD;
        end

      end
      default: begin
      end


    endcase
  end
endmodule
