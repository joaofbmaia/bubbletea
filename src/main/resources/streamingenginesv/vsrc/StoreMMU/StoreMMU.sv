`timescale 1ns / 1ps

module StoreMMU
  import engine_pkg::*;
(
    input logic clk_i,
    input logic ctrl_reset_i,

    /* Configuration channel */
    input logic cfg_we_i,
    input logic [STREAM_ID_WIDTH-1:0] cfg_stream_i,
    input logic [1:0] cfg_width_i,
    output logic cfg_ready_o,
    output logic [$clog2(SMMU_NUM_TABLES)-1:0] cfg_mmu_idx_o,

    /* Destination operands data channel */
    input logic rd_valid_i,
    input logic [STREAM_ID_WIDTH-1:0] rd_streamid_i,
    input logic [VEC_WIDTH-1:0] rd_vecdata_i,
    input logic [VEC_NUM_BYTES-1:0] rd_predicate_i,
    output logic rd_ready_o,
    output logic [1:0] rd_width_o,
    output logic rd_done_o,
    output logic rd_store_request_successful_o,

    /* Stream State channel */
    input logic [$clog2(SMMU_NUM_TABLES)-1:0] ss_mmu_idx_i,
    input logic [ADDRESS_WIDTH-1:0] ss_addr_i,
    input logic ss_addr_valid_i,
    input logic ss_last_i,
    output logic ss_trigger_o,

    /* AXI Write Address Channel */
    input logic axi_aw_ready_i,
    output logic axi_aw_valid_o,
    output logic [ADDRESS_WIDTH-1:0] axi_aw_addr_o,
    output logic [7:0] axi_aw_len_o,
    output logic [2:0] axi_aw_size_o,
    output logic [1:0] axi_aw_burst_o,

    /* AXI Write Data Channel */
    input logic axi_w_ready_i,
    output logic axi_w_valid_o,
    output logic [AXI_W_DATA_WIDTH-1:0] axi_w_data_o,
    output logic [(AXI_W_DATA_WIDTH / 8)-1:0] axi_w_strb_o,
    output logic axi_w_last_o,

    /* AXI Write Response Channel */
    input logic [1:0] axi_b_resp_i,
    input logic axi_b_valid_i,
    output logic axi_b_ready_o,

    /* Performance Counters */
    output logic [63:0] hpc_smmu_commit_o,
    output logic [63:0] hpc_smmu_stall_o,
    output logic [63:0] hpc_ops_store_o
);


  localparam AXI_W_TAG_WIDTH = ADDRESS_WIDTH - AXI_W_OFFSET_WIDTH;

  logic [63:0] hpc_commit;
  logic [63:0] hpc_stall;
  logic [63:0] hpc_ops_store;



  /* The Store MMU consists in multiple tables, each one
    * holding information of a single stream. Each table
    * has an address and a data FIFO. It also has registers
    * to guarante correct order of writting operations.
    * It has registers to hold paramenters required by the
    * AXI protocol
    */

  store_fifo_bundle_t [SMMU_NUM_TABLES-1:0] store_fifo_table;
  store_fifo_bundle_t store_fifo_entry;

  logic [SMMU_NUM_TABLES-1:0][$clog2(SMMU_NUM_TABLES)-1:0] sq_index_reg, next_sq_index;
  logic [SMMU_NUM_TABLES-1:0] sq_valid_reg, next_sq_valid;

  logic [SMMU_NUM_TABLES-1:0] store_ptr_reg, next_store_ptr_reg;
  logic [SMMU_NUM_TABLES-1:0] queue_ptr_reg, next_queue_ptr_reg;



  smmu_state_t store_state, next_state;

  logic [AXI_W_NUM_BYTES-1:0][7:0] buffer_data, next_buffer_data;
  logic [AXI_W_NUM_BYTES-1:0] buffer_strb, next_buffer_strb;
  logic [AXI_W_TAG_WIDTH-1:0] buffer_addr_tag, next_buffer_addr_tag;
  logic buffer_valid, next_buffer_valid;

  /* Internal wire declaration */

  logic clear_mmu_resources;

  logic [$clog2(SMMU_NUM_TABLES)-1 : 0] empty_table_idx;
  logic empty_table_ready;

  logic [$clog2(SMMU_NUM_TABLES)-1:0] table_idx;
  logic [$clog2(SMMU_NUM_ADDRESSES)-1:0] write_idx;


  logic [$clog2(SMMU_NUM_TABLES)-1:0] fu_idx;
  logic fu_exists;
  logic [$clog2(VEC_NUM_BYTES)-1:0] fu_read_ptr;

  logic [$clog2(SMMU_NUM_TABLES)-1:0] store_ptr_idx;
  // logic [$clog2(SMMU_NUM_TABLES)-1:0] queue_ptr_idx;

  logic [$clog2(SMMU_NUM_ADDRESSES)-1:0] store_r_addr_ptr;
  logic [$clog2(VEC_NUM_BYTES)-1:0] store_r_data_ptr;
  logic [$clog2(SMMU_NUM_TABLES)-1:0] store_sel_idx;

  logic next_addr_valid;
  logic next_data_valid;

  logic [ADDRESS_WIDTH-1:0] next_addr;
  logic [AXI_W_TAG_WIDTH-1:0] next_addr_tag;
  logic [AXI_W_OFFSET_WIDTH-1:0] next_addr_offset;




  always_ff @(posedge clk_i) begin
    if (ctrl_reset_i) begin
      for (int i = 0; i < SMMU_NUM_TABLES; i++) begin
        for (int j = 0; j < SMMU_NUM_ADDRESSES; j++) begin
          store_fifo_table[i].addressFIFO[j].address <= 0;
          store_fifo_table[i].addressFIFO[j].valid   <= 0;
        end

        for (int j = 0; j < VEC_NUM_BYTES; j++) begin
          store_fifo_table[i].dataFIFO[j].data  <= 0;
          store_fifo_table[i].dataFIFO[j].valid <= 0;
        end

        store_fifo_table[i].address_write_ptr <= 0;
        store_fifo_table[i].address_read_ptr  <= 0;
        store_fifo_table[i].data_ptr          <= 0;

        store_fifo_table[i].ss_done           <= 0;

        store_fifo_table[i].stream            <= 0;
        store_fifo_table[i]._width            <= 0;
        store_fifo_table[i].valid             <= 0;
      end

      sq_index_reg <= '0;
      sq_valid_reg <= '0;

      store_ptr_reg <= 1;
      queue_ptr_reg <= 1;

      store_state <= WAIT_PAIR;

      buffer_data <= '0;
      buffer_strb <= '0;
      buffer_addr_tag <= 0;
      buffer_valid <= 0;

      hpc_smmu_commit_o <= 0;
      hpc_smmu_stall_o <= 0;
      hpc_ops_store_o <= 0;
    end else begin
      hpc_smmu_commit_o <= hpc_commit;
      hpc_smmu_stall_o <= hpc_stall;
      hpc_ops_store_o <= hpc_ops_store;



      sq_index_reg <= next_sq_index;
      sq_valid_reg <= next_sq_valid;

      store_ptr_reg <= next_store_ptr_reg;
      queue_ptr_reg <= next_queue_ptr_reg;

      buffer_valid <= next_buffer_valid;
      buffer_data <= next_buffer_data;
      buffer_strb <= next_buffer_strb;
      buffer_addr_tag <= next_buffer_addr_tag;

      store_fifo_table[store_sel_idx] <= store_fifo_entry;

      store_state <= next_state;

      /* There are non-configured tables and a new store
       * stream is being configured
       */
      if (cfg_we_i && empty_table_ready) begin
        for (int i = 0; i < SMMU_NUM_ADDRESSES; i++) begin
          store_fifo_table[empty_table_idx].addressFIFO[i].address <= 0;
          store_fifo_table[empty_table_idx].addressFIFO[i].valid   <= 0;
        end

        for (int i = 0; i < VEC_NUM_BYTES; i++) begin
          store_fifo_table[empty_table_idx].dataFIFO[i].data  <= 0;
          store_fifo_table[empty_table_idx].dataFIFO[i].valid <= 0;
        end

        store_fifo_table[empty_table_idx].address_write_ptr <= 0;
        store_fifo_table[empty_table_idx].address_read_ptr  <= 0;
        store_fifo_table[empty_table_idx].data_ptr          <= 0;

        store_fifo_table[empty_table_idx].ss_done           <= 0;

        store_fifo_table[empty_table_idx].stream            <= cfg_stream_i;
        store_fifo_table[empty_table_idx]._width            <= cfg_width_i;
        store_fifo_table[empty_table_idx].valid             <= 1;
      end


      /* When the Stream State is providing new valid addresses, the
     * Store MMU will check is that new address can be registered
     * in the address FIFO of the Stream
     */
      if (ss_addr_valid_i && !ss_trigger_o) begin
        store_fifo_table[table_idx].addressFIFO[write_idx].address <= ss_addr_i;
        store_fifo_table[table_idx].addressFIFO[write_idx].valid <= 1;

        store_fifo_table[table_idx].address_write_ptr <= write_idx + 1;

        if (ss_last_i) begin
          store_fifo_table[table_idx].ss_done <= 1;
        end
      end

      /* An handshake occurs, and the data is commited */
      if (rd_valid_i && rd_ready_o) begin
        /* Transfering the predicated bytes to the data FIFO
		   * and reseting the data read pointer
		   */

        for (int i = 0; i < VEC_NUM_BYTES; i++) begin
          if (rd_predicate_i[i]) begin
            store_fifo_table[fu_idx].dataFIFO[i].data  <= rd_vecdata_i[8*(i)+:8];
            store_fifo_table[fu_idx].dataFIFO[i].valid <= 1;
          end else begin
            store_fifo_table[fu_idx].dataFIFO[i].valid <= 0;
          end
        end

        store_fifo_table[fu_idx].data_ptr <= 0;
      end


      /* Eventually all pairs will be resolved. Free all resources
		   * and issue one final request in there is valid buffered data
		   */
      if (clear_mmu_resources) begin
        /* Free all allocated resources of the stream that finished */
        for (int i = 0; i < SMMU_NUM_ADDRESSES; i++) begin
          store_fifo_table[store_sel_idx].addressFIFO[i].address <= 0;
          store_fifo_table[store_sel_idx].addressFIFO[i].valid   <= 0;
        end

        for (int i = 0; i < VEC_NUM_BYTES; i++) begin
          store_fifo_table[store_sel_idx].dataFIFO[i].data  <= 0;
          store_fifo_table[store_sel_idx].dataFIFO[i].valid <= 0;
        end

        store_fifo_table[store_sel_idx].address_write_ptr <= 0;
        store_fifo_table[store_sel_idx].address_read_ptr  <= 0;
        store_fifo_table[store_sel_idx].data_ptr          <= 0;

        store_fifo_table[store_sel_idx].ss_done           <= 0;

        store_fifo_table[store_sel_idx].stream            <= 0;
        store_fifo_table[store_sel_idx]._width            <= 0;
        store_fifo_table[store_sel_idx].valid             <= 0;

        /* Trigger one last store request if there is valid buffered data */
        if (buffer_valid) begin
          store_state <= WAIT_AWREADY;
        end
      end



    end
  end

  /* Increment the hardware performance counters
	 * when a new valid address is being provided
	 */

  assign hpc_stall = (ss_addr_valid_i && ss_trigger_o) ? hpc_smmu_stall_o + 1 : hpc_smmu_stall_o;
  assign hpc_commit = (ss_addr_valid_i && !ss_trigger_o) ? hpc_smmu_commit_o + 1 : hpc_smmu_commit_o;



  /* The Store MMU keeps track of non-configured tables. When
	 * a configuration of a store stream arrives, the module
	 * selects one of them and initializes the registers
	 */

  always_comb begin
    empty_table_idx   = 0;
    empty_table_ready = 0;
    foreach (store_fifo_table[i]) begin
      if (store_fifo_table[i].valid == 0) begin
        empty_table_idx   = $clog2(SMMU_NUM_TABLES)'(i);
        empty_table_ready = 1;
      end
    end
  end


  assign cfg_ready_o = empty_table_ready;
  assign cfg_mmu_idx_o = empty_table_idx;


  assign write_idx = store_fifo_table[ss_mmu_idx_i].address_write_ptr;
  assign table_idx = ss_mmu_idx_i;

  assign ss_trigger_o = ss_addr_valid_i ? store_fifo_table[table_idx].addressFIFO[write_idx].valid : '0;



  /* The Store MMU will accept valid data vectors from the
	 * Function Unit. It does so by searching the table with
	 * the correct stream that is providing the data and copying
	 * it to the data FIFO
	 */

  always_comb begin
    fu_exists = 0;
    fu_idx = 0;
    foreach (store_fifo_table[i]) begin
      if (store_fifo_table[i].stream == rd_streamid_i && store_fifo_table[i].valid) begin
        fu_exists = 1;
        fu_idx = $clog2(SMMU_NUM_TABLES)'(i);
      end
    end
  end

  assign fu_read_ptr = store_fifo_table[fu_idx].data_ptr;
  assign rd_ready_o  = !store_fifo_table[fu_idx].dataFIFO[fu_read_ptr].valid && fu_exists;
  assign rd_width_o  = store_fifo_table[fu_idx]._width;





  always_comb begin
    store_ptr_idx = 0;
    //queue_ptr_idx = 0;

    for (int i = 0; i < SMMU_NUM_TABLES; i++) begin
      if (store_ptr_reg[i] == 1) begin
        store_ptr_idx = $clog2(SMMU_NUM_TABLES)'(i);
      end
      // if (queue_ptr_reg[i] == 1) begin
      //   queue_ptr_idx = $clog2(SMMU_NUM_TABLES)'(i);
      // end
    end
  end

  assign store_sel_idx = sq_index_reg[store_ptr_idx];
  assign store_r_addr_ptr = store_fifo_table[store_sel_idx].address_read_ptr;
  assign store_r_data_ptr = store_fifo_table[store_sel_idx].data_ptr;

  /* Determine if there is a new valid pair of address and data */
  assign next_addr_valid = store_fifo_table[store_sel_idx].addressFIFO[store_r_addr_ptr].valid &&
                           store_fifo_table[store_sel_idx].valid &&
                           sq_valid_reg[store_ptr_idx];

  assign next_data_valid = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr].valid &&
                            store_fifo_table[store_sel_idx].valid &&
                            sq_valid_reg[store_ptr_idx];

  /* Obtain the address tag and the offset within the data row */
  assign next_addr = store_fifo_table[store_sel_idx].addressFIFO[store_r_addr_ptr].address;
  assign next_addr_tag = (AXI_W_TAG_WIDTH)'(next_addr >> AXI_W_OFFSET_WIDTH);
  assign next_addr_offset = next_addr[AXI_W_OFFSET_WIDTH-1:0];


  logic [$clog2(SMMU_NUM_ADDRESSES)-1:0] last_addr_ptr;

  assign last_addr_ptr = store_r_addr_ptr == 5'b11111 ? '0 : store_r_addr_ptr + 1;

  assign clear_mmu_resources = store_fifo_table[store_sel_idx].ss_done &&
                                store_fifo_table[store_sel_idx].valid &&
                                !store_fifo_table[store_sel_idx].addressFIFO[last_addr_ptr].valid;

  assign rd_done_o = clear_mmu_resources;
  assign rd_store_request_successful_o = store_state == WAIT_PAIR;



  assign next_queue_ptr_reg = (rd_valid_i && rd_ready_o) ? 
                              {queue_ptr_reg[SMMU_NUM_TABLES-2:0], queue_ptr_reg[SMMU_NUM_TABLES-1]} :  // shift left (circular)
                              queue_ptr_reg;

  always_comb begin
    next_sq_index = sq_index_reg;
    next_sq_valid = sq_valid_reg;

    next_store_ptr_reg = store_ptr_reg;

    if (store_state == WAIT_PAIR && 
     next_addr_valid && next_data_valid && 
     !(buffer_valid && buffer_addr_tag != next_addr_tag) && 
     !(store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr+1].valid)) begin
      for (int i = 0; i < SMMU_NUM_TABLES; i++) begin
        if (store_ptr_reg[i] == 1) begin
          next_sq_index[i] = 0;
          next_sq_valid[i] = 0;
        end
      end

      /* Update the FIFO pointer in case the last pair was issued */

      next_store_ptr_reg = {
        store_ptr_reg[SMMU_NUM_TABLES-2:0], store_ptr_reg[SMMU_NUM_TABLES-1]
      };  // shift left (circular)

    end else if (rd_valid_i && rd_ready_o) begin
      /* The Store MMU guarantes correct order of writting
		     * operations between streams through a queue, which
		     * registers the order that streams provide data to
		     * the Store MMU. When new valid data arrives, this
		     * queue is updated
		     */
      for (int i = 0; i < SMMU_NUM_TABLES; i++) begin
        if (queue_ptr_reg[i] == 1) begin
          next_sq_index[i] = fu_idx;
          next_sq_valid[i] = 1;
        end
      end
    end
  end


  /* Reassignment of AXI internal signals */
  assign axi_aw_valid_o = store_state == WAIT_AWREADY ? 1 : FALSE;
  assign axi_aw_addr_o  = store_state == WAIT_AWREADY ? (ADDRESS_WIDTH)'(32'(buffer_addr_tag) << AXI_W_OFFSET_WIDTH) : 0;
  assign axi_aw_len_o = 0;
  assign axi_aw_size_o = 3'($clog2(AXI_W_DATA_WIDTH / 8));
  assign axi_aw_burst_o = 2'b01;

  assign axi_w_valid_o = store_state == WAIT_WREADY ? 1 : FALSE;
  assign axi_w_data_o = store_state == WAIT_WREADY ? buffer_data : 0;
  assign axi_w_strb_o = store_state == WAIT_WREADY ? buffer_strb : 0;
  assign axi_w_last_o = 1;

  assign axi_b_ready_o = store_state == WAIT_BVALID ? 1 : FALSE;


  always_comb begin
    next_state = store_state;

    store_fifo_entry = store_fifo_table[store_sel_idx];



    next_buffer_data = buffer_data;
    next_buffer_strb = buffer_strb;
    next_buffer_addr_tag = buffer_addr_tag;
    next_buffer_valid = buffer_valid;

    hpc_ops_store = hpc_ops_store_o;



    /* A finite state machine manages the AXI protocol
     * transactions. It transitions to different states
     * as data is being accepted and communicated with
     * the Data Memory
     */
    case (store_state)
      /* STATE: waiting for valid pairs of (address, data) */
      WAIT_PAIR: begin
        if (next_addr_valid && next_data_valid) begin
          if (buffer_valid && buffer_addr_tag != next_addr_tag) begin
            next_state = WAIT_AWREADY;
          end else begin
            /* Update the address and valid registers in case it is a new configuration */
            next_buffer_addr_tag = next_addr_tag;
            next_buffer_valid = 1;

            /* Update the data and strobe values */
            case (store_fifo_table[store_sel_idx]._width)
              2'b00: begin
                for (int i = 0; i < AXI_W_NUM_BYTES; i++) begin
                  if ((AXI_W_OFFSET_WIDTH)'(i) == next_addr_offset) begin
                    next_buffer_data[next_addr_offset] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr].data;
                    next_buffer_strb[i] = 1;
                  end
                end
              end
              2'b01: begin
                for (int i = 0; i < AXI_W_NUM_BYTES; i += 2) begin
                  if ((AXI_W_OFFSET_WIDTH)'(i) == next_addr_offset) begin
                    next_buffer_data[next_addr_offset] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr].data;
                    next_buffer_strb[next_addr_offset] = 1;
                    next_buffer_data[next_addr_offset+1] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr+1].data;
                    next_buffer_strb[next_addr_offset+1] = 1;
                  end
                end
              end
              2'b10: begin
                for (int i = 0; i < AXI_W_NUM_BYTES; i += 4) begin
                  if ((AXI_W_OFFSET_WIDTH)'(i) == next_addr_offset) begin
                    next_buffer_data[next_addr_offset] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr].data;
                    next_buffer_strb[next_addr_offset] = 1;
                    next_buffer_data[next_addr_offset+1] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr+1].data;
                    next_buffer_strb[next_addr_offset+1] = 1;
                    next_buffer_data[next_addr_offset+2] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr+2].data;
                    next_buffer_strb[next_addr_offset+2] = 1;
                    next_buffer_data[next_addr_offset+3] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr+3].data;
                    next_buffer_strb[next_addr_offset+3] = 1;
                  end
                end
              end
              2'b11: begin
                for (int i = 0; i < AXI_W_NUM_BYTES; i += 8) begin
                  if ((AXI_W_OFFSET_WIDTH)'(i) == next_addr_offset) begin
                    next_buffer_data[next_addr_offset] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr].data;
                    next_buffer_strb[next_addr_offset] = 1;
                    next_buffer_data[next_addr_offset+1] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr+1].data;
                    next_buffer_strb[next_addr_offset+1] = 1;
                    next_buffer_data[next_addr_offset+2] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr+2].data;
                    next_buffer_strb[next_addr_offset+2] = 1;
                    next_buffer_data[next_addr_offset+3] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr+3].data;
                    next_buffer_strb[next_addr_offset+3] = 1;
                    next_buffer_data[next_addr_offset+4] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr+4].data;
                    next_buffer_strb[next_addr_offset+4] = 1;
                    next_buffer_data[next_addr_offset+5] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr+5].data;
                    next_buffer_strb[next_addr_offset+5] = 1;
                    next_buffer_data[next_addr_offset+6] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr+6].data;
                    next_buffer_strb[next_addr_offset+6] = 1;
                    next_buffer_data[next_addr_offset+7] = store_fifo_table[store_sel_idx].dataFIFO[store_r_data_ptr+7].data;
                    next_buffer_strb[next_addr_offset+7] = 1;
                  end
                end
              end
            endcase

            /* Clear the data entries from the FIFO */

            case (store_fifo_table[store_sel_idx]._width)
              2'b00: begin
                store_fifo_entry.data_ptr = store_r_data_ptr + 1;

                store_fifo_entry.dataFIFO[store_r_data_ptr].valid = 0;
              end
              2'b01: begin
                store_fifo_entry.data_ptr = store_r_data_ptr + 2;
                store_fifo_entry.dataFIFO[store_r_data_ptr].valid = 0;
                store_fifo_entry.dataFIFO[store_r_data_ptr+1].valid = 0;
              end
              2'b10: begin
                store_fifo_entry.data_ptr = store_r_data_ptr + 4;
                store_fifo_entry.dataFIFO[store_r_data_ptr].valid = 0;
                store_fifo_entry.dataFIFO[store_r_data_ptr+1].valid = 0;
                store_fifo_entry.dataFIFO[store_r_data_ptr+2].valid = 0;
                store_fifo_entry.dataFIFO[store_r_data_ptr+3].valid = 0;
              end
              2'b11: begin
                store_fifo_entry.data_ptr = store_r_data_ptr + 8;
                store_fifo_entry.dataFIFO[store_r_data_ptr].valid = 0;
                store_fifo_entry.dataFIFO[store_r_data_ptr+1].valid = 0;
                store_fifo_entry.dataFIFO[store_r_data_ptr+2].valid = 0;
                store_fifo_entry.dataFIFO[store_r_data_ptr+3].valid = 0;
                store_fifo_entry.dataFIFO[store_r_data_ptr+4].valid = 0;
                store_fifo_entry.dataFIFO[store_r_data_ptr+5].valid = 0;
                store_fifo_entry.dataFIFO[store_r_data_ptr+6].valid = 0;
                store_fifo_entry.dataFIFO[store_r_data_ptr+7].valid = 0;
              end
            endcase

            /* Clear the address entry from the FIFO */
            store_fifo_entry.addressFIFO[store_r_addr_ptr].valid = 0;
            store_fifo_entry.address_read_ptr = store_r_addr_ptr + 1;
          end
        end
      end

      /* STATE: waiting for AWREADY */
      WAIT_AWREADY: begin
        if (axi_aw_ready_i) begin
          next_state = WAIT_WREADY;

          /* Memory controller accepted a new load request. Increment the
           * performance counter regarding the total number of load requests
           */
          hpc_ops_store = hpc_ops_store_o + 1;
        end
      end


      /* STATE: waiting for WREADY */
      WAIT_WREADY: begin
        if (axi_w_ready_i) begin
          next_state = WAIT_BVALID;
        end
      end

      /* STATE: waiting for BVALID */
      WAIT_BVALID: begin
        if (axi_b_valid_i) begin

          if (axi_b_resp_i == 2'b00) begin
            /* Free the buffers when no errors occurred */
            next_buffer_data = '0;
            next_buffer_strb = '0;
            next_buffer_addr_tag = 0;
            next_buffer_valid = 0;

            next_state = WAIT_PAIR;
          end else begin
            /* Repeat the store request when an error occurred */
            next_state = WAIT_AWREADY;
          end
        end
      end
    endcase
  end
endmodule
