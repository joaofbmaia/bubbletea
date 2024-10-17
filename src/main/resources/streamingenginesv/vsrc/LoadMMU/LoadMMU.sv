// `include "Engine/LoadMMU/LoadFIFO.sv"
// `include "Engine/LoadMMU/LoadRequestQueue.sv"
// `include "Engine/LoadMMU/LoadLineBuffer.sv"
`timescale 1ns / 1ps

module LoadMMU
  import engine_pkg::*;
#(
    /* Internal parameters, calculated using external ones */

) (
    input logic clk_i,
    input logic ctrl_reset_i,


    /* Configuration channel */
    input logic cfg_we_i,
    input logic [STREAM_ID_WIDTH-1:0] cfg_stream_i,
    input logic [1:0] cfg_width_i,
    output logic cfg_ready_o,
    output logic [$clog2(LF_NUM_TABLES)-1:0] cfg_mmu_idx_o,

    /* Source operands data channel */
    input logic [NUM_SRC_OPERANDS-1:0][STREAM_ID_WIDTH-1:0] rs_streamid_i,
    input logic [NUM_SRC_OPERANDS-1:0] rs_ready_i,
    output logic [NUM_SRC_OPERANDS-1:0] rs_valid_o,
    output logic [NUM_SRC_OPERANDS-1:0] rs_done_o,
    output logic [NUM_SRC_OPERANDS-1:0][VEC_WIDTH-1:0] rs_vecdata_o,
    output logic [NUM_SRC_OPERANDS-1:0][VEC_NUM_BYTES-1:0] rs_predicate_o,
    output logic [NUM_SRC_OPERANDS-1:0][STREAM_NUM_DIMS-1:0] rs_completed_o,

    /* Stream State channel */
    input logic [$clog2(LF_NUM_TABLES)-1:0] ss_mmu_idx_i,
    input logic ss_valid_i,
    input logic [ADDRESS_WIDTH-1:0] ss_addr_i,
    input logic ss_addr_valid_i,
    input logic [1:0] ss_width_i,
    input logic [STREAM_NUM_DIMS-1:0] ss_completed_i,
    input logic ss_vectorize_i,
    input logic ss_last_i,
    output logic ss_trigger_o,

    /* AXI Read Address Channel */
    input logic axi_ar_ready_i,
    output logic [ADDRESS_WIDTH-1:0] axi_ar_addr_o,
    output logic axi_ar_valid_o,
    output logic [AXI_LEN_WIDTH-1:0] axi_ar_len_o,
    output logic [AXI_SIZE_WIDTH-1:0] axi_ar_size_o,
    output logic [AXI_BURST_WIDTH-1:0] axi_ar_burst_o,

    /* AXI Read Data Channel */
    input logic [AXI_R_DATA_WIDTH-1:0] axi_r_data_i,
    input logic axi_r_valid_i,
    input logic axi_r_last_i,
    input logic [1:0] axi_r_resp_i,
    output logic axi_r_ready_o,

    /* Performance Counters */
    output logic [63:0] hpc_lmmu_stall_lf_o,
    output logic [63:0] hpc_lmmu_stall_lrq_o,
    output logic [63:0] hpc_lmmu_stall_o,
    output logic [63:0] hpc_lmmu_commit_o,
    output logic [63:0] hpc_lmmu_stall_llb_o,
    output logic [63:0] hpc_ops_load_o
);




  // Performance Counters
  logic [63:0] hpc_lf_stall;
  logic [63:0] hpc_lrq_stall;
  logic [63:0] hpc_stall;
  logic [63:0] hpc_commit;


  logic trigger_lf_ss;

  //------------------------
  // Load Line Buffer <-> Load FIFO
  //------------------------

  logic [LLB_NUM_BYTES-1:0][7:0] data_bytes_llb_lf;

  //------------------------
  // Load Request Queue <-> Load FIFO
  //------------------------
  logic trigger_lrq_lf;

  logic [1:0] width_lrq_lf;
  logic [$clog2(LF_NUM_TABLES)-1:0] mmu_idx_lrq_lf;
  logic [$clog2(LF_NUM_BYTES)-1:0] lf_offset_lrq_lf;
  logic [$clog2(LLB_NUM_BYTES)-1:0] llb_offset_lrq_lf;
  logic valid_lrq_lf;

  logic [$clog2(LF_NUM_BYTES)-1:0] ptr_lf_lrq;
  logic trigger_lf_lrq;

  //------------------------
  // Load Request Queue <-> Load Line Buffer
  //------------------------

  logic ready_llb_lrq;
  logic [ADDRESS_TAG_WIDTH-1:0] addr_tag_llb_lrq;

  logic [ADDRESS_TAG_WIDTH-1:0] data_addr_tag_llb_lrq;
  logic valid_llb_lrq;
  logic valid_llb_lf;
  logic data_clear_lrq_llb;

  logic req_valid_lrq_lbb;

  logic [ADDRESS_TAG_WIDTH-1 : 0] addr_tag_lrq_lbb;


  assign valid_llb_lf = valid_llb_lrq;



  always_ff @(posedge clk_i or posedge ctrl_reset_i) begin
    if (ctrl_reset_i) begin
      hpc_lmmu_stall_lf_o <= 64'b0;
      hpc_lmmu_stall_lrq_o <= 64'b0;
      hpc_lmmu_stall_o <= 64'b0;
      hpc_lmmu_commit_o <= 64'b0;
    end else begin
      hpc_lmmu_stall_lf_o <= hpc_lf_stall;
      hpc_lmmu_stall_lrq_o <= hpc_lrq_stall;
      hpc_lmmu_stall_o <= hpc_stall;
      hpc_lmmu_commit_o <= hpc_commit;
    end

  end

  always_comb begin
    hpc_lf_stall = hpc_lmmu_stall_lf_o;
    hpc_lrq_stall = hpc_lmmu_stall_lrq_o;
    hpc_stall = hpc_lmmu_stall_o;
    hpc_commit = hpc_lmmu_commit_o;


    /* When the Stream State provides a new valid address, the
     * Load MMU increments the performance counters, depending
     * on if it could accept the address or not
     */
    if (ss_addr_valid_i) begin

      /* The Load FIFO is stalling */
      if (trigger_lf_ss) begin
        hpc_lf_stall += 1;
      end

      /* The Load Request Queue is stalling */
      if (trigger_lrq_lf) begin
        hpc_lrq_stall += 1;
      end


      /* Neither the Load FIFO or the LRQ stalled */
      if (!(trigger_lf_ss || trigger_lrq_lf)) begin
        hpc_commit += 1;
      end else begin
        hpc_stall += 1;
      end

    end
  end


  assign ss_trigger_o = ss_addr_valid_i ? (trigger_lf_ss || trigger_lrq_lf) : 1'b0;





  LoadFIFO lf (
      .clk_i(clk_i),
      .ctrl_reset_i(ctrl_reset_i),
      /* Configuration channel */
      .cfg_we_i(cfg_we_i),
      .cfg_stream_i(cfg_stream_i),
      .cfg_width_i(cfg_width_i),
      .cfg_ready_o(cfg_ready_o),
      .cfg_mmu_idx_o(cfg_mmu_idx_o),
      /* Stream State channel */
      .ss_mmu_idx_i(ss_mmu_idx_i),
      .ss_valid_i(ss_valid_i),
      .ss_addr_valid_i(ss_addr_valid_i),
      .ss_completed_i(ss_completed_i),
      .ss_vectorize_i(ss_vectorize_i),
      .ss_last_i(ss_last_i),
      .ss_trigger_o(trigger_lf_ss),
      /* Load Request Queue channel */
      .lrq_ready_i(!trigger_lrq_lf),
      .lrq_ptr_o(ptr_lf_lrq),
      /* Request solver channel */
      .data_llb_bytes_i(data_bytes_llb_lf),
      .data_lrq_width_i(width_lrq_lf),
      .data_lrq_mmu_idx_i(mmu_idx_lrq_lf),
      .data_lrq_offset_lf_i(lf_offset_lrq_lf),
      .data_lrq_offset_llb_i(llb_offset_lrq_lf),
      .data_lrq_valid_i(valid_lrq_lf && valid_llb_lf),
      /* Source operands data channel */
      .rs_streamid_i(rs_streamid_i),
      .rs_ready_i(rs_ready_i),
      .rs_valid_o(rs_valid_o),
      .rs_done_o(rs_done_o),
      .rs_vecdata_o(rs_vecdata_o),
      .rs_predicate_o(rs_predicate_o),
      .rs_completed_o(rs_completed_o)
  );


  LoadRequestQueue lrq (
      .clk_i(clk_i),
      .ctrl_reset_i(ctrl_reset_i),
      /* Stream State channel */
      .ss_mmu_idx_i(ss_mmu_idx_i),
      .ss_addr_i(ss_addr_i),
      .ss_addr_valid_i(ss_addr_valid_i),
      .ss_width_i(ss_width_i),
      .ss_trigger_o(trigger_lrq_lf),
      /* Request solver channel */
      .lf_ptr_i(ptr_lf_lrq),
      .lf_ready_i(!trigger_lf_ss),
      .lf_req_width_o(width_lrq_lf),
      .lf_req_mmu_idx_o(mmu_idx_lrq_lf),
      .lf_req_offset_lf_o(lf_offset_lrq_lf),
      .lf_req_offset_llb_o(llb_offset_lrq_lf),
      .lf_req_valid_o(valid_lrq_lf),
      /* Data request channel */
      .req_llb_ready_i(ready_llb_lrq),
      .req_llb_addr_tag_o(addr_tag_lrq_lbb),
      .req_llb_valid_o(req_valid_lrq_lbb),
      /* Request solver channel */
      .data_addr_tag_i(data_addr_tag_llb_lrq),
      .data_valid_i(valid_llb_lrq),
      .data_clear_o(data_clear_lrq_llb),
      /* Performance Counters */
      .hpc_lmmu_stall_llb_o(hpc_lmmu_stall_llb_o)
  );

  LoadLineBuffer llb (
      .clk_i(clk_i),
      .ctrl_reset_i(ctrl_reset_i),
      /* Data request channel */
      .req_lrq_valid_i(req_valid_lrq_lbb),
      .req_lrq_addr_tag_i(addr_tag_lrq_lbb),
      .req_lrq_ready_o(ready_llb_lrq),
      /* Request solver channel */
      .data_clear_i(data_clear_lrq_llb),
      .data_addr_tag_o(data_addr_tag_llb_lrq),
      .data_bytes_o(data_bytes_llb_lf),
      .data_valid_o(valid_llb_lrq),
      /* AXI Read Address channel */
      .ar_ready_i(axi_ar_ready_i),
      .ar_addr_o(axi_ar_addr_o),
      .ar_valid_o(axi_ar_valid_o),
      .ar_len_o(axi_ar_len_o),
      .ar_size_o(axi_ar_size_o),
      .ar_burst_o(axi_ar_burst_o),
      /* AXI Read Data channel */
      .r_ready_o(axi_r_ready_o),
      .r_data_i(axi_r_data_i),
      .r_valid_i(axi_r_valid_i),
      .r_last_i(axi_r_last_i),
      .r_resp_i(axi_r_resp_i),
      /* Performance Counters */
      .hpc_ops_load_o(hpc_ops_load_o)
  );
endmodule
