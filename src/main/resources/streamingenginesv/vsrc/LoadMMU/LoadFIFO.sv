`timescale 1ns / 1ps

module LoadFIFO
  import engine_pkg::*;
#(

) (
    input logic clk_i,
    input logic ctrl_reset_i,

    /* Configuration channel */
    input  logic                                                  cfg_we_i,
    input  logic [      STREAM_ID_WIDTH-1:0]                      cfg_stream_i,
    input  logic [                      1:0]                      cfg_width_i,
    output logic                                                  cfg_ready_o,
    output logic [$clog2(LF_NUM_TABLES)-1:0]                      cfg_mmu_idx_o,
    /* Stream State channel */
    input  logic [$clog2(LF_NUM_TABLES)-1:0]                      ss_mmu_idx_i,
    input  logic                                                  ss_valid_i,
    input  logic                                                  ss_addr_valid_i,
    input  logic [      STREAM_NUM_DIMS-1:0]                      ss_completed_i,
    input  logic                                                  ss_vectorize_i,
    input  logic                                                  ss_last_i,
    output logic                                                  ss_trigger_o,
    /* Load Request Queue channel */
    input  logic                                                  lrq_ready_i,
    output logic [ $clog2(LF_NUM_BYTES)-1:0]                      lrq_ptr_o,
    /* Request solver channel */
    input  logic [        LLB_NUM_BYTES-1:0][                7:0] data_llb_bytes_i,
    input  logic [                      1:0]                      data_lrq_width_i,
    input  logic [$clog2(LF_NUM_TABLES)-1:0]                      data_lrq_mmu_idx_i,
    input  logic [ $clog2(LF_NUM_BYTES)-1:0]                      data_lrq_offset_lf_i,
    input  logic [$clog2(LLB_NUM_BYTES)-1:0]                      data_lrq_offset_llb_i,
    input  logic                                                  data_lrq_valid_i,
    /* Source operands data channel */
    input  logic [     NUM_SRC_OPERANDS-1:0][STREAM_ID_WIDTH-1:0] rs_streamid_i,
    input  logic [     NUM_SRC_OPERANDS-1:0]                      rs_ready_i,
    output logic [     NUM_SRC_OPERANDS-1:0]                      rs_valid_o,
    output logic [     NUM_SRC_OPERANDS-1:0]                      rs_done_o,
    output logic [     NUM_SRC_OPERANDS-1:0][      VEC_WIDTH-1:0] rs_vecdata_o,
    output logic [     NUM_SRC_OPERANDS-1:0][  VEC_NUM_BYTES-1:0] rs_predicate_o,
    output logic [     NUM_SRC_OPERANDS-1:0][STREAM_NUM_DIMS-1:0] rs_completed_o
);

  load_fifo_bundle_t [LF_NUM_TABLES-1:0] load_fifo_table;

  logic [VEC_NUM_BYTES-1:0][7:0] req_data;
  logic [VEC_NUM_BYTES-1:0] req_predicate;
  logic [VEC_NUM_BYTES-1:0] req_valid, next_valid;



  logic reserved, ss_done;

  logic [NUM_SRC_OPERANDS-1:0][$clog2(LF_NUM_TABLES)-1:0] rs_idx;
  logic [NUM_SRC_OPERANDS-1:0][$clog2(LMMU_NUM_VECS) - 1:0] rs_ptr_vec;
  logic [NUM_SRC_OPERANDS-1:0] rs_valid;


  /* Internal signals */
  int i, j;

  logic [STREAM_NUM_DIMS-1:0] vec_completed, next_vec_completed;

  logic [LF_NUM_TABLES-1:0] cfg_empty_table_vec;
  logic [$clog2(LF_NUM_TABLES)-1:0] cfg_empty_table_idx;
  logic cfg_empty_table_valid;

  logic cfg_out_ready;
  logic [$clog2(LF_NUM_TABLES)-1:0] cfg_out_mmu_idx;

  logic [$clog2(LF_NUM_TABLES) - 1:0] table_idx;
  logic [$clog2(VEC_NUM_BYTES) - 1:0] idx_write_byte;  // 4 bits
  logic [$clog2(LMMU_NUM_VECS) - 1:0] idx_write_vec;  // 2 bits
  logic [$clog2(VEC_NUM_BYTES) - 1:0] next_idx_write_byte;
  logic [$clog2(LMMU_NUM_VECS) - 1:0] next_idx_write_vec;

  logic [$clog2(LF_NUM_TABLES)-1:0] req_idx_fifo;
  logic [$clog2(VEC_NUM_BYTES)-1:0] req_idx_byte;
  logic [$clog2(LMMU_NUM_VECS) - 1:0] req_idx_vec;

  logic [NUM_SRC_OPERANDS-1:0] rs_out_valid;
  logic [NUM_SRC_OPERANDS-1:0] rs_out_done;
  logic [NUM_SRC_OPERANDS-1:0][VEC_WIDTH-1:0] rs_out_vecdata;
  logic [NUM_SRC_OPERANDS-1:0][VEC_NUM_BYTES-1:0] rs_out_predicate;
  logic [NUM_SRC_OPERANDS-1:0][STREAM_NUM_DIMS-1:0] rs_out_completed;

  logic [NUM_SRC_OPERANDS-1:0] clear_mmu_resources;


  always_comb begin
    cfg_empty_table_idx   = 0;
    cfg_empty_table_valid = 0;

    foreach(load_fifo_table[i]) begin
      if (!load_fifo_table[i].valid) begin
        cfg_empty_table_idx   = ($clog2(LF_NUM_TABLES))'(i);
        cfg_empty_table_valid = 1;
      end
    end
  end

  assign cfg_ready_o = cfg_empty_table_valid;
  assign cfg_mmu_idx_o = cfg_empty_table_idx;



  /* The Load FIFO will be receiving valid addresses from the 
   * Stream State and will save them in the respetive load FIFO.
   * Since an address is associated with a memory load request,
   * that address can only be accepted if the request can also
   * be accepted in the Load Request Queue module
   */
  assign table_idx = ss_mmu_idx_i;

  assign idx_write_byte = load_fifo_table[table_idx].idx_write_byte;
  assign idx_write_vec = load_fifo_table[table_idx].idx_write_vec;

  assign ss_trigger_o = !load_fifo_table[table_idx].valid || 
                            (load_fifo_table[table_idx].valid && load_fifo_table[table_idx].vecs[idx_write_vec].reserved);

  assign lrq_ptr_o = {idx_write_vec, idx_write_byte};



  always_ff @(posedge clk_i) begin
    if (ctrl_reset_i) begin
      for (int i = 0; i < LF_NUM_TABLES; i++) begin
        for (int j = 0; j < LMMU_NUM_VECS; j++) begin
          for (int k = 0; k < VEC_NUM_BYTES; k++) begin
            load_fifo_table[i].vecs[j].req_data[k] <= 8'b0;
          end
          load_fifo_table[i].vecs[j].req_predicate <= 0;
          load_fifo_table[i].vecs[j].req_valid <= 1;
          load_fifo_table[i].vecs[j].reserved <= 0;
          load_fifo_table[i].vecs[j].completed <= 0;
        end
        load_fifo_table[i].ptr_vec_read <= 0;
        load_fifo_table[i].idx_write_vec <= 0;
        load_fifo_table[i].idx_write_byte <= 0;
        load_fifo_table[i].ss_done <= 0;
        load_fifo_table[i].stream <= 0;
        load_fifo_table[i]._width <= 0;
        load_fifo_table[i].valid <= 0;
      end
    end else if (cfg_we_i && cfg_empty_table_valid) begin
      for (int i = 0; i < LMMU_NUM_VECS; i++) begin
        for (int j = 0; j < VEC_NUM_BYTES; j++) begin
          load_fifo_table[cfg_empty_table_idx].vecs[i].req_data[j] <= 8'b0;
          load_fifo_table[cfg_empty_table_idx].vecs[i].req_predicate[j] <= 0;
          load_fifo_table[cfg_empty_table_idx].vecs[i].req_valid[j] <= 1;

          load_fifo_table[cfg_empty_table_idx].vecs[i].reserved <= 0;
          load_fifo_table[cfg_empty_table_idx].vecs[i].completed <= 0;
        end
      end

      load_fifo_table[cfg_empty_table_idx].ptr_vec_read <= 0;
      load_fifo_table[cfg_empty_table_idx].idx_write_vec <= 0;
      load_fifo_table[cfg_empty_table_idx].idx_write_byte <= 0;

      load_fifo_table[cfg_empty_table_idx].ss_done <= 0;

      load_fifo_table[cfg_empty_table_idx].stream <= cfg_stream_i;
      load_fifo_table[cfg_empty_table_idx]._width <= cfg_width_i;
      load_fifo_table[cfg_empty_table_idx].valid <= 1;
    end

    if (ss_valid_i && lrq_ready_i && !ss_trigger_o) begin
      load_fifo_table[table_idx].idx_write_vec <= next_idx_write_vec;
      load_fifo_table[table_idx].idx_write_byte <= next_idx_write_byte;
      load_fifo_table[table_idx].vecs[idx_write_vec].completed <= next_vec_completed;

      if (ss_addr_valid_i) begin
        for (int i = 0; i < 2 ** load_fifo_table[table_idx]._width; i++) begin
          load_fifo_table[table_idx].vecs[idx_write_vec].req_predicate[idx_write_byte+4'(i)] <= req_predicate[idx_write_byte+4'(i)];
          load_fifo_table[table_idx].vecs[idx_write_vec].req_valid[idx_write_byte+4'(i)] <= next_valid[idx_write_byte+4'(i)];
        end
      end

      load_fifo_table[table_idx].vecs[idx_write_vec].reserved <= reserved;
      load_fifo_table[table_idx].ss_done <= ss_done;
    end

    if (data_lrq_valid_i) begin
      for (int i = 0; i < 2 ** data_lrq_width_i; i++) begin
        load_fifo_table[req_idx_fifo].vecs[req_idx_vec].req_data[req_idx_byte + 4'(i)] <= req_data[req_idx_byte + 4'(i)];
        load_fifo_table[req_idx_fifo].vecs[req_idx_vec].req_valid[req_idx_byte + 4'(i)] <=  req_valid[req_idx_byte + 4'(i)];
      end
    end



    /* When the handshake is asserted, clear the vector being 
       * consumed and update the read pointers
       */
    for (int k = 0; k < NUM_SRC_OPERANDS; k++) begin
      if (rs_ready_i[k] && rs_valid[k]) begin
        for (int j = 0; j < VEC_NUM_BYTES; j++) begin
          load_fifo_table[rs_idx[k]].vecs[rs_ptr_vec[k]].req_data[j] <= 8'b0;
          load_fifo_table[rs_idx[k]].vecs[rs_ptr_vec[k]].req_predicate[j] <= 0;
          load_fifo_table[rs_idx[k]].vecs[rs_ptr_vec[k]].req_valid[j] <= 1;
        end

        load_fifo_table[rs_idx[k]].vecs[rs_ptr_vec[k]].reserved <= 0;
        load_fifo_table[rs_idx[k]].vecs[rs_ptr_vec[k]].completed <= 0;
        load_fifo_table[rs_idx[k]].ptr_vec_read <= rs_ptr_vec[k] + 1;

        if (clear_mmu_resources[k]) begin
          for (int i = 0; i < LMMU_NUM_VECS; i++) begin
            for (int j = 0; j < VEC_NUM_BYTES; j++) begin
              load_fifo_table[rs_idx[k]].vecs[i].req_data[j] <= 8'b0;
              load_fifo_table[rs_idx[k]].vecs[i].req_predicate[j] <= 0;
              load_fifo_table[rs_idx[k]].vecs[i].req_valid[j] <= 1;
            end
            load_fifo_table[rs_idx[k]].vecs[i].reserved  <= 0;
            load_fifo_table[rs_idx[k]].vecs[i].completed <= 0;
          end

          load_fifo_table[rs_idx[k]].ptr_vec_read <= 0;
          load_fifo_table[rs_idx[k]].idx_write_vec <= 0;
          load_fifo_table[rs_idx[k]].idx_write_byte <= 0;
          load_fifo_table[rs_idx[k]].ss_done <= 0;
          load_fifo_table[rs_idx[k]].stream <= 0;
          load_fifo_table[rs_idx[k]]._width <= 0;
          load_fifo_table[rs_idx[k]].valid <= 0;
        end
      end
    end

  end


  assign req_idx_fifo = data_lrq_mmu_idx_i;
  assign req_idx_byte = data_lrq_offset_lf_i[$clog2(VEC_NUM_BYTES)-1:0];
  assign req_idx_vec  = ($clog2(LMMU_NUM_VECS))'(data_lrq_offset_lf_i >> $clog2(VEC_NUM_BYTES));



  always_comb begin
    /* For each source channel port, obtain information
     * regarding the readiness of the vector data
     */
    for (int i = 0; i < NUM_SRC_OPERANDS; i++) begin
      rs_idx[i] = '1;

      rs_ptr_vec[i] = '1;
      rs_valid[i] = '1;


      for (int j = 0; j < LF_NUM_TABLES; j++) begin
        if (load_fifo_table[j].stream === rs_streamid_i[i] && load_fifo_table[j].valid) begin
          rs_idx[i] = ($clog2(LF_NUM_TABLES))'(j);
          break;
        end
      end

      rs_ptr_vec[i] = load_fifo_table[rs_idx[i]].ptr_vec_read;
      rs_valid[i] = load_fifo_table[rs_idx[i]].vecs[rs_ptr_vec[i]].reserved && load_fifo_table[rs_idx[i]].valid && &load_fifo_table[rs_idx[i]].vecs[rs_ptr_vec[i]].req_valid;

      /* Eventually all data will be consumed. When that 
     * happens, free the load FIFO 
	   */
      clear_mmu_resources[i] = load_fifo_table[rs_idx[i]].ss_done &&
                             !load_fifo_table[rs_idx[i]].vecs[($clog2(LMMU_NUM_VECS))
          '(rs_ptr_vec[i]+1'b1)].reserved;

    end



  end




  always_comb begin
    /* The Load FIFO module communicates with the processing
     * unit by transfering vectors of data through an handshake
     * protocol
     */
    for (int i = 0; i < NUM_SRC_OPERANDS; i++) begin
      rs_valid_o[i] = 0;
      rs_done_o[i] = 0;
      rs_vecdata_o[i] = '0;
      rs_predicate_o[i] = '0;
      rs_completed_o[i] = '0;

    end

    /* The Load FIFO establishes the communication for each
     * of the source operand through the respective ports
     */
    for (int i = 0; i < NUM_SRC_OPERANDS; i++) begin


      rs_valid_o[i] = load_fifo_table[rs_idx[i]].vecs[rs_ptr_vec[i]].reserved && load_fifo_table[rs_idx[i]].valid && &load_fifo_table[rs_idx[i]].vecs[rs_ptr_vec[i]].req_valid;
      rs_vecdata_o[i] = load_fifo_table[rs_idx[i]].vecs[rs_ptr_vec[i]].req_data;
      rs_predicate_o[i] = load_fifo_table[rs_idx[i]].vecs[rs_ptr_vec[i]].req_predicate;
      rs_completed_o[i] = load_fifo_table[rs_idx[i]].vecs[rs_ptr_vec[i]].completed;
      /* When the handshake is asserted, clear the vector being 
       * consumed and update the read pointers
       */
      if (rs_ready_i[i] && rs_valid[i]) begin
        if (clear_mmu_resources[i]) begin
          rs_done_o[i] = 1;
        end else begin
          rs_done_o[i] = 0;
        end
      end
    end
  end


  always_comb begin
    next_idx_write_vec = load_fifo_table[table_idx].idx_write_vec;
    next_idx_write_byte = load_fifo_table[table_idx].idx_write_byte;


    vec_completed = load_fifo_table[table_idx].vecs[idx_write_vec].completed;
    next_vec_completed = load_fifo_table[table_idx].vecs[idx_write_vec].completed;


    reserved = load_fifo_table[table_idx].vecs[idx_write_vec].reserved;
    ss_done = load_fifo_table[table_idx].ss_done;



    req_predicate = load_fifo_table[table_idx].vecs[idx_write_vec].req_predicate;
    next_valid = load_fifo_table[table_idx].vecs[idx_write_vec].req_valid;


    req_data = load_fifo_table[req_idx_fifo].vecs[req_idx_vec].req_data;
    req_valid = load_fifo_table[req_idx_fifo].vecs[req_idx_vec].req_valid;

    /* The address is valid and both the Load FIFO and the
     * Load Request Queue are ready to accept the address
     */
    if (ss_valid_i && lrq_ready_i && !ss_trigger_o) begin
      /* Define how the address write pointer is updated */
      if (ss_vectorize_i) begin
        /* When the Stream State signals vectorization, the
           * current vector is closed by advancing the address
           * pointer to the next vector, not allowing this way
           * the predication of more bytes in the current vector
           */

        next_idx_write_vec  = idx_write_vec + 1;
        next_idx_write_byte = 0;

      end else if (ss_addr_valid_i) begin
        /* When there is no vectorization, just increment the
           * address write pointer according to the element width
           */
        case (load_fifo_table[table_idx]._width)
          2'b00: next_idx_write_byte = idx_write_byte + 1;
          2'b01: next_idx_write_byte = idx_write_byte + 2;
          2'b10: next_idx_write_byte = idx_write_byte + 4;
          2'b11: next_idx_write_byte = idx_write_byte + 8;
        endcase

        if (next_idx_write_byte == 0) begin
          next_idx_write_vec = idx_write_vec + 1;
        end else begin
          next_idx_write_vec = idx_write_vec;
        end

      end else begin
        next_idx_write_vec  = idx_write_vec;
        next_idx_write_byte = idx_write_byte;
      end



      /* Reserve the incoming address in the respective Load FIFO */
      if (ss_addr_valid_i) begin
        for (int i = 0; i < 2 ** load_fifo_table[table_idx]._width; i++) begin
          req_predicate[idx_write_byte+4'(i)] = 1;
          next_valid[idx_write_byte+4'(i)] = 0;
        end
      end

      /* The state of completeness of each dimension of the memory access pattern
       * needs to be saved for branch control purposes (in the processing unit)
       */
      next_vec_completed = vec_completed | ss_completed_i;


      /* Mark the current vector as reserved */
      if (next_idx_write_byte == 0) begin
        reserved = 1;
      end

      /* The last address will eventually be provided by
       * the Stream State, which needs to be registered
       */
      if (ss_last_i) begin
        ss_done = 1;
      end
    end


    /* As new valid data is being requested, so the outstanding requests
     * are solved by writting the correct data into the reserved load FIFO
     * slots
     */
    if (data_lrq_valid_i) begin
      for (int i = 0; i < 2 ** data_lrq_width_i; i++) begin
        req_data[req_idx_byte+4'(i)]  = data_llb_bytes_i[data_lrq_offset_llb_i+4'(i)];
        req_valid[req_idx_byte+4'(i)] = 1;
      end
    end
  end



endmodule
