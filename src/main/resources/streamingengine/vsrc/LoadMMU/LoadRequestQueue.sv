`timescale 1ns / 1ps

module LoadRequestQueue
  import engine_pkg::*;
#(

) (
    input logic clk_i,
    input logic ctrl_reset_i,

    /* Stream State channel */
    input logic [$clog2(LF_NUM_TABLES)-1:0] ss_mmu_idx_i,
    input logic [ADDRESS_WIDTH-1:0] ss_addr_i,
    input logic ss_addr_valid_i,
    input logic [1:0] ss_width_i,
    output logic ss_trigger_o,

    /* Request solver channel */
    input logic [$clog2(LF_NUM_BYTES)-1:0] lf_ptr_i,
    input logic lf_ready_i,

    output logic [                      1:0] lf_req_width_o,
    output logic [$clog2(LF_NUM_TABLES)-1:0] lf_req_mmu_idx_o,
    output logic [ $clog2(LF_NUM_BYTES)-1:0] lf_req_offset_lf_o,
    output logic [$clog2(LLB_NUM_BYTES)-1:0] lf_req_offset_llb_o,
    output logic                             lf_req_valid_o,

    /* Data request channel */
    output logic                           req_llb_valid_o,
    output logic [ADDRESS_TAG_WIDTH-1 : 0] req_llb_addr_tag_o,
    input  logic                           req_llb_ready_i,


    /* Request solver channel */
    input  logic [ADDRESS_TAG_WIDTH-1:0] data_addr_tag_i,
    input  logic                         data_valid_i,
    output logic                         data_clear_o,

    output logic [63:0] hpc_lmmu_stall_llb_o
);


  /* Performance Counter */
  logic [63:0] hpc_stall_llb;

  /* The Load Request Queue consists of multiple tables
   * which will buffer outstanding data requests. When the
   * Stream State provides a new address, that request is 
   * registered in the LRQ. As soon as the data is available,
   * the requests are solved and the data copied to the
   * Load FIFOs
   */

  lrq_table_bundle_t [LRQ_NUM_TABLES-1:0] lrq_tables;
  lrq_table_bundle_t updated_table;
  lrq_request_bundle_t [LRQ_NUM_REQUESTS-1:0] data_valid_table_requests;


  logic [LRQ_NUM_REQUESTS-1:0] lrq_valid_requests;
  logic [$clog2(LRQ_NUM_TABLES)-1:0] nd_table_idx, nr_free_table_idx, nr_empty_table_idx;
  logic [$clog2(LRQ_NUM_REQUESTS)-1:0] request_idx, data_valid_request_idx;
  logic [LRQ_NUM_REQUESTS-1:0] request_empty;

  integer i, j;

  /* Declaring internal wires */
  logic [ADDRESS_TAG_WIDTH-1:0] ss_in_addr_tag;
  logic [$clog2(LLB_NUM_BYTES)-1:0] ss_in_llb_offset;
  logic ss_out_trigger;

  logic [1:0] lf_out_req_width;
  logic [$clog2(LF_NUM_TABLES)-1:0] lf_out_req_mmu_idx;
  logic [$clog2(LF_NUM_BYTES)-1:0] lf_out_req_offset_lf;
  logic [$clog2(LLB_NUM_BYTES)-1:0] lf_out_req_offset_llb;
  logic lf_out_req_valid;

  logic [LRQ_NUM_TABLES-1:0] lrq_config_tables;
  logic data_llb_out_clear;

  logic [LRQ_NUM_TABLES-1:0] nr_free_table_vec;
  logic nr_free_table_valid;
  logic [LRQ_NUM_TABLES-1:0] nr_requested_vec;
  logic nr_requested_valid;
  logic [LRQ_NUM_TABLES-1:0] nr_empty_table_vec;
  logic nr_empty_table_valid;

  logic [LRQ_NUM_TABLES-1:0] nd_table_vec;
  logic nd_table_valid;

  /* An arbiter selects unrequested but valid memory addresses from
   * the Load Request Queue to the Load Line Buffer. When ready,
   * the Load Line Buffer will accept the address and configure
   * a new table
   */

  logic arbiter_llb_trigger;
  logic [LRQ_NUM_TABLES-1:0] arbiter_llb_input;
  logic [$clog2(LRQ_NUM_TABLES)-1:0] arbiter_llb_output;

  RoundRobinArbiter #(
      .INPUT_WIDTH(LRQ_NUM_TABLES)
  ) arbiter_llb (
      .clk_i(clk_i),
      .rst_n_i(ctrl_reset_i),
      .trigger(arbiter_llb_trigger),
      .inputs(arbiter_llb_input),
      .output_idx(arbiter_llb_output)
  );

  always_ff @(posedge clk_i) begin
    /* 
     * When the reset signal is activated, the registers of
     * the module will be updated to their default values
     */
    if (ctrl_reset_i) begin
      for (int i = 0; i < LRQ_NUM_TABLES; i++) begin
        for (int j = 0; j < LRQ_NUM_REQUESTS; j++) begin
          lrq_tables[i].requests[j].offset_llb <= 0;
          lrq_tables[i].requests[j].offset_lf <= 0;
          lrq_tables[i].requests[j]._width <= 0;
          lrq_tables[i].requests[j].mmu_idx <= 0;
          lrq_tables[i].requests[j].valid <= 0;
        end

        lrq_tables[i].address_tag <= 0;
        lrq_tables[i].requested   <= 0;
      end


      hpc_lmmu_stall_llb_o <= 64'b0;
    end else begin
      if (ss_addr_valid_i && lf_ready_i) begin
        if (nr_free_table_valid) begin
          lrq_tables[nr_free_table_idx].requests[request_idx] <= updated_table.requests[request_idx];
        end else if (nr_empty_table_valid) begin
          lrq_tables[nr_empty_table_idx] <= updated_table;
        end
      end

      if (nd_table_valid) begin
        lrq_tables[nd_table_idx].requests[data_valid_request_idx] <= data_valid_table_requests[data_valid_request_idx];
      end

      if (req_llb_ready_i && !arbiter_llb_trigger) begin
        for (int i = 0; i < LRQ_NUM_TABLES; i++) begin
          if (lrq_tables[i].address_tag == lrq_tables[arbiter_llb_output].address_tag && lrq_config_tables[i]) begin
            lrq_tables[i].requested <= 1;
          end
        end
      end

      hpc_lmmu_stall_llb_o <= hpc_stall_llb;
    end
  end

  /* A performance counter registers the number of cycles where
   * the Load Request Queue had valid and unrequested addresses
   * but the Load Line Buffer was not ready
   */
  assign hpc_stall_llb = (!arbiter_llb_trigger && !req_llb_ready_i) ? hpc_lmmu_stall_llb_o + 1 : hpc_lmmu_stall_llb_o;


  /* 
   * The Load Request Queue will free all tables that no longer
   * have valid outstanding requests */
  always_comb begin
    for (int i = 0; i < LRQ_NUM_TABLES; i++) begin
      for (int j = 0; j < LRQ_NUM_REQUESTS; j++) begin
        lrq_valid_requests[j] = lrq_tables[i].requests[j].valid;
      end
      // Reduction OR operation to check if there is at least one '1' in the vector
      lrq_config_tables[i] = |lrq_valid_requests;
    end
  end

  /* Asserting the default values for internal wires related to
   * incoming generated addresses from the Stream State
   */

  assign ss_in_addr_tag   = ss_addr_i >> $clog2(LLB_NUM_BYTES);
  assign ss_in_llb_offset = ss_addr_i[$clog2(LLB_NUM_BYTES)-1:0];



  /* The Load Request Queue will register every valid address
   * from the Stream State as a outstanding/pending request.
   * Since each table can hold multiple request for a matching
   * address tag, the module will prioritize inserting new
   * requests in open tables. Otherwise it will configure a
   * new table.
   */
  always_comb begin
    for (int i = 0; i < LRQ_NUM_TABLES; i = i + 1) begin
      nr_free_table_vec[i] = lrq_tables[i].address_tag == ss_in_addr_tag && lrq_config_tables[i] && !check(
          lrq_tables[i].requests);

      nr_requested_vec[i] = lrq_tables[i].address_tag == ss_in_addr_tag && lrq_config_tables[i] && lrq_tables[i].requested;

      nr_empty_table_vec[i] = !lrq_config_tables[i];
    end
  end

  assign nr_free_table_valid  = |nr_free_table_vec;
  assign nr_requested_valid   = |nr_requested_vec;
  assign nr_empty_table_valid = |nr_empty_table_vec;


  /* Search for tables in the LRQ with the same tag of the
   * row of data bytes provided by the LLB
   */
  always_comb begin
    for (int i = 0; i < LRQ_NUM_TABLES; i++) begin
      nd_table_vec[i] = lrq_tables[i].address_tag == data_addr_tag_i && lrq_config_tables[i];
    end
  end
  assign nd_table_valid = |nd_table_vec;


  // Obtain table index based if its a to append to an existing table or to configure a new table
  always_comb begin
    nr_free_table_idx = ($clog2(LRQ_NUM_TABLES))'(LRQ_NUM_TABLES - 1);
    foreach (nr_free_table_vec[i]) begin
      if (nr_free_table_vec[i]) begin
        nr_free_table_idx = ($clog2(LRQ_NUM_TABLES))'(i);
      end
    end
  end

  always_comb begin
    nr_empty_table_idx = ($clog2(LRQ_NUM_TABLES))'(LRQ_NUM_TABLES - 1);
    foreach (nr_empty_table_vec[i]) begin
      if (nr_empty_table_vec[i]) begin
        nr_empty_table_idx = ($clog2(LRQ_NUM_TABLES))'(i);
      end
    end
  end

  always_comb begin
    nd_table_idx = ($clog2(LRQ_NUM_TABLES))'(LRQ_NUM_TABLES - 1);
    foreach (nd_table_vec[i]) begin
      if (nd_table_vec[i]) begin
        nd_table_idx = ($clog2(LRQ_NUM_TABLES))'(i);
      end
    end
  end


  always_comb begin
    for (int j = 0; j < LRQ_NUM_REQUESTS; j++) begin
      request_empty[j] = !lrq_tables[nr_free_table_idx].requests[j].valid;
    end
  end

  always_comb begin
    ss_out_trigger = 0;
    request_idx = 0;


    updated_table = '0;

    /* The Load FIFO is ready to enqueue the new address */
    /* 1. Will append to an existing table
     * 2. Will configure a new table
     * 3. Won't do anything, the LRQ is full and can't accept the request
     */

    if (ss_addr_valid_i && lf_ready_i) begin
      if (nr_free_table_valid) begin
        updated_table = lrq_tables[nr_free_table_idx];


        for (int j = 0; j < LRQ_NUM_REQUESTS; j++) begin
          if (request_empty[j]) begin
            request_idx = ($clog2(LRQ_NUM_REQUESTS))'(j);
            break;
          end
        end

        /* Update the registers with the new request information */
        updated_table.requests[request_idx].offset_llb = ss_in_llb_offset;
        updated_table.requests[request_idx].offset_lf = lf_ptr_i;
        updated_table.requests[request_idx]._width = ss_width_i;
        updated_table.requests[request_idx].mmu_idx = ss_mmu_idx_i;
        updated_table.requests[request_idx].valid = 1;

        ss_out_trigger = 0;
      end else if (nr_empty_table_valid) begin
        updated_table = lrq_tables[nr_empty_table_idx];

        /* Update the registers with the new request information */
        updated_table.requests[0].offset_llb = ss_in_llb_offset;
        updated_table.requests[0].offset_lf = lf_ptr_i;
        updated_table.requests[0]._width = ss_width_i;
        updated_table.requests[0].mmu_idx = ss_mmu_idx_i;
        updated_table.requests[0].valid = 1;

        /* Configure the table and the others request entries */

        for (int j = 1; j < LRQ_NUM_REQUESTS; j++) begin
          updated_table.requests[j].offset_llb = '0;
          updated_table.requests[j].offset_lf = '0;
          updated_table.requests[j]._width = '0;
          updated_table.requests[j].mmu_idx = '0;
          updated_table.requests[j].valid = 0;
        end

        updated_table.address_tag = ss_in_addr_tag;
        updated_table.requested = nr_requested_valid;

        ss_out_trigger = 0;

        /*
         * Needs to switch to a different stream because the current
         * request cannot be accepted by the Load MMU
         */
      end else begin
        ss_out_trigger = 1;
      end
    end


  end

  /* The Load Request Queue will be fed with valid data from
   * the Load Line Buffer to solve outstanding requests.
   * The LRQ finds the tables of the corresponding address tag
   * and solves one pending request per cycle
   */
  always_comb begin
    data_valid_table_requests = lrq_tables[nd_table_idx].requests;
    data_valid_request_idx = 0;


    /* Default values of the internal wires regarding 
     * the request solving information
     */
    lf_out_req_width = '0;
    lf_out_req_mmu_idx = '0;
    lf_out_req_offset_lf = '0;
    lf_out_req_offset_llb = '0;
    lf_out_req_valid = 0;

    data_llb_out_clear = 0;

    /*
     * When there is valid data and valid outstanding requests, one
     * is selected and the correct data is copied to the Load FIFO
     */
    if (data_valid_i) begin
      if (nd_table_valid) begin


        for (int j = 0; j < LRQ_NUM_REQUESTS; j++) begin
          if (lrq_tables[nd_table_idx].requests[j].valid) begin
            data_valid_request_idx = ($clog2(LRQ_NUM_REQUESTS))'(j);
            break;
          end
        end

        /* Update the wires with the request information, which will be processed by the Load FIFO */
        lf_out_req_width = lrq_tables[nd_table_idx].requests[data_valid_request_idx]._width;
        lf_out_req_mmu_idx = lrq_tables[nd_table_idx].requests[data_valid_request_idx].mmu_idx;
        lf_out_req_offset_lf = lrq_tables[nd_table_idx].requests[data_valid_request_idx].offset_lf;
        lf_out_req_offset_llb = lrq_tables[nd_table_idx].requests[data_valid_request_idx].offset_llb;
        lf_out_req_valid = lrq_tables[nd_table_idx].requests[data_valid_request_idx].valid;

        /* Free the resources of the selected request */
        data_valid_table_requests[data_valid_request_idx]._width = '0;
        data_valid_table_requests[data_valid_request_idx].mmu_idx = '0;
        data_valid_table_requests[data_valid_request_idx].offset_lf = '0;
        data_valid_table_requests[data_valid_request_idx].offset_llb = '0;
        data_valid_table_requests[data_valid_request_idx].valid = 0;
      end else begin
        data_llb_out_clear = 1;
      end
    end
  end

  /* An arbiter selects unrequested but valid memory addresses from
   * the Load Request Queue to the Load Line Buffer. When ready,
   * the Load Line Buffer will accept the address and configure
   * a new table
   */
  always_comb begin
    for (int i = 0; i < LRQ_NUM_TABLES; i++) begin
      arbiter_llb_input[i] = !lrq_tables[i].requested && lrq_config_tables[i];
    end


  end
  assign arbiter_llb_trigger = !lrq_config_tables[arbiter_llb_output] || (lrq_config_tables[arbiter_llb_output] && lrq_tables[arbiter_llb_output].requested);

  /* The Load Line Buffer is ready to receive new requests and 
   * the Load Request Queue has valid but unrequested addresses
   */
  assign req_llb_addr_tag_o = lrq_tables[arbiter_llb_output].address_tag;
  assign req_llb_valid_o = lrq_config_tables[arbiter_llb_output] && !lrq_tables[arbiter_llb_output].requested;


  // /* A performance counter registers the number of cycles where
  //     * the Load Request Queue had valid and unrequested addresses
  //     * but the Load Line Buffer was not ready
  //     */
  // 
  // if (!arbiter_llb_trigger && req_llb_ready_i) begin
  //   // Increment the performance counter
  // end

  /* Connecting the output ports to some of 
     * the internal wires of the module
     */
  assign ss_trigger_o = ss_out_trigger;

  assign lf_req_width_o = lf_out_req_width;
  assign lf_req_mmu_idx_o = lf_out_req_mmu_idx;
  assign lf_req_offset_lf_o = lf_out_req_offset_lf;

  assign lf_req_offset_llb_o = lf_out_req_offset_llb;
  assign lf_req_valid_o = lf_out_req_valid;


  assign data_clear_o = data_llb_out_clear;



  function logic check(input lrq_request_bundle_t [LRQ_NUM_REQUESTS-1:0] requests);
    for (int i = 0; i < LRQ_NUM_REQUESTS; i = i + 1) begin
      if (!requests[i].valid) begin
        return 0;
      end
    end
    return 1;
  endfunction

endmodule

