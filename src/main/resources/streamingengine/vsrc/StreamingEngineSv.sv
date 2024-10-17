// `include "Engine/StreamIterator.sv"
// `include "Engine/StreamState.sv"
// `include "Engine/StreamConfigurator.sv"


// `include "Engine/LoadMMU/LoadMMU.sv"
// `include "Engine/StoreMMU/StoreMMU.sv"~

`timescale 1ns / 1ps

module StreamingEngineSv
  import engine_pkg::*;
#(

) (
    input logic clk,
    input logic ctrl_reset,

    //----------------------------
    //  CPU <-> Stream Configurator
    //----------------------------

    input logic valid_cpu_sc,
    input logic sta_cpu_sc,
    input logic end_cpu_sc,
    input stream_type_t type_cpu_sc,
    input logic [1:0] width_cpu_sc,
    input logic [STREAM_ID_WIDTH-1:0] stream_cpu_sc,
    input logic mod_cpu_sc,
    input logic vec_cpu_sc,
    input logic [STREAM_OFFSET_WIDTH-1:0] dim_offset_cpu_sc,
    input logic [STREAM_STRIDE_WIDTH-1:0] dim_stride_cpu_sc,
    input logic [STREAM_SIZE_WIDTH-1:0] dim_size_cpu_sc,
    input logic [1:0] mod_target_cpu_sc,
    input logic [MOD_BEHAVIOUR_WIDTH-1:0] mod_behaviour_cpu_sc,
    input logic [STREAM_STRIDE_WIDTH-1:0] mod_displacement_cpu_sc,
    input logic [STREAM_SIZE_WIDTH-1:0] mod_size_cpu_sc,

    output logic ready_sc_cpu,

    //----------------------------
    //  CPU <-> SMMU
    //----------------------------

    input logic rd_valid_cpu_smmu,
    input logic [STREAM_ID_WIDTH-1:0] rd_streamid_cpu_smmu,
    input logic [VEC_WIDTH-1:0] rd_vecdata_cpu_smmu,
    input logic [VEC_NUM_BYTES-1:0] rd_predicate_cpu_smmu,

    output logic rd_ready_smmu_cpu,
    output logic [1:0] rd_width_smmu_cpu,
    output logic rd_done_smmu_cpu,
    output logic rd_store_request_successful_smmu_cpu,

    //----------------------------
    //  CPU <-> LMMU
    //----------------------------

    input logic [NUM_SRC_OPERANDS-1:0] rs_ready_cpu_lmmu,
    input logic [NUM_SRC_OPERANDS-1:0][STREAM_ID_WIDTH-1:0] rs_streamid_cpu_lmmu,

    output logic [NUM_SRC_OPERANDS-1:0] rs_valid_lmmu_cpu,
    output logic [NUM_SRC_OPERANDS-1:0] rs_done_lmmu_cpu,
    output logic [NUM_SRC_OPERANDS-1:0][VEC_WIDTH-1:0] rs_vecdata_lmmu_cpu,
    output logic [NUM_SRC_OPERANDS-1:0][VEC_NUM_BYTES-1:0] rs_predicate_lmmu_cpu,
    output logic [NUM_SRC_OPERANDS-1:0][STREAM_NUM_DIMS-1:0] rs_completed_lmmu_cpu,

    //----------------------------
    //  AXI <-> MMU
    //----------------------------

    /* AXI Write Address Channel */
    input logic aw_ready_axi_mmu,
    output logic aw_valid_mmu_axi,
    output logic [AXI_ADDR_WIDTH-1:0] aw_addr_mmu_axi,
    output logic [AXI_LEN_WIDTH-1:0] aw_len_mmu_axi,
    output logic [AXI_SIZE_WIDTH-1:0] aw_size_mmu_axi,
    output logic [AXI_BURST_WIDTH-1:0] aw_burst_mmu_axi,

    /* AXI Write Data Channel */
    input logic w_ready_axi_mmu,
    output logic w_valid_mmu_axi,
    output logic [AXI_W_DATA_WIDTH-1:0] w_data_mmu_axi,
    output logic [AXI_W_NUM_BYTES-1:0] w_strb_mmu_axi,
    output logic w_last_mmu_axi,

    /* AXI Write Response Channel */
    input logic b_valid_axi_mmu,
    input logic [1:0] b_resp_axi_mmu,
    output logic b_ready_mmu_axi,


    /* AXI Read Address Channel */
    input logic ar_ready_axi_mmu,
    output logic [ADDRESS_WIDTH-1 : 0] ar_addr_mmu_axi,
    output logic ar_valid_mmu_axi,
    output logic [AXI_LEN_WIDTH-1:0] ar_len_mmu_axi,
    output logic [AXI_SIZE_WIDTH-1:0] ar_size_mmu_axi,
    output logic [AXI_BURST_WIDTH-1:0] ar_burst_mmu_axi,

    /* AXI Read Data Channel */
    input logic [AXI_R_DATA_WIDTH-1:0] r_data_axi_mmu,
    input logic r_valid_axi_mmu,
    input logic r_last_axi_mmu,
    input logic [1:0] r_resp_axi_mmu,
    output logic r_ready_mmu_axi,

    /* Performance Counters */
    output logic [63:0] hpc_ss_desc,
    output logic [63:0] hpc_lmmu_commit,
    output logic [63:0] hpc_lmmu_stall,
    output logic [63:0] hpc_lmmu_stall_lf,
    output logic [63:0] hpc_lmmu_stall_lrq,
    output logic [63:0] hpc_lmmu_stall_llb,
    output logic [63:0] hpc_smmu_commit,
    output logic [63:0] hpc_smmu_stall,
    output logic [63:0] hpc_ops_load,
    output logic [63:0] hpc_ops_store
);


  //-------------------------------------
  // Stream Configurator <-> Stream State
  //-------------------------------------

  logic ack_sc_ss;  // End of Configuration Acknowledgement
  logic we_sc_ss;  // Configuration Write Enable
  logic append_sc_ss;  // Append to current configuration
  logic [1:0] width_sc_ss;
  logic [STREAM_ID_WIDTH-1:0] stream_sc_ss;  // Stream ID
  stream_type_t type_sc_ss;  // Stream Load or Store
  logic vec_we_sc_ss;  // Vectorization Write Enable                            
  logic [$clog2(STREAM_NUM_DIMS)-1:0] vec_idx_sc_ss;  // Vectorization Index

  logic dim_we_sc_ss;  // Dimension Write Enable
  logic [$clog2(STREAM_NUM_DIMS)-1:0] dim_idx_sc_ss;  // Dimension Index
  logic [STREAM_OFFSET_WIDTH-1 : 0] dim_offset_sc_ss;  // Dimension Offset
  logic [STREAM_STRIDE_WIDTH-1 : 0] dim_stride_sc_ss;  // Dimension Stride
  logic [STREAM_SIZE_WIDTH-1 : 0] dim_size_sc_ss;  // Dimension Size

  logic mod_we_sc_ss;  // Modifier Write Enable
  logic [$clog2(STREAM_NUM_DIMS)-1:0] mod_idx_sc_ss;  // Modifier Index
  logic [1:0] mod_target_sc_ss;  // Modifier Target
  logic mod_behaviour_sc_ss;  // Modifier Behaviour (Add/Sub)
  logic [STREAM_STRIDE_WIDTH-1 : 0] mod_displacement_sc_ss;  // Modifier Displacement
  logic [STREAM_SIZE_WIDTH-1 : 0] mod_size_sc_ss;  // Modifier Size

  logic ready_ss_sc;  // Configuration Ready for address generation


  logic ready_lmmu_sc;
  logic ready_smmu_sc;



  //-------------------------------------
  // Stream Configurator <-> SMMU
  //-------------------------------------

  logic we_sc_smmu;
  logic [STREAM_ID_WIDTH-1:0] stream_sc_smmu;
  logic [1:0] width_sc_smmu;


  logic ready_smmu;


  assign we_sc_smmu = we_sc_ss && type_sc_ss == STORE;
  assign stream_sc_smmu = stream_sc_ss;
  assign width_sc_smmu = width_sc_ss;


  //-------------------------------------
  // Stream Configurator <-> LMMU
  //-------------------------------------
  logic we_sc_lmmu;
  logic [STREAM_ID_WIDTH-1:0] stream_sc_lmmu;
  logic [1:0] width_sc_lmmu;

  logic ready_lmmu;

  assign we_sc_lmmu = we_sc_ss && type_sc_ss == LOAD;
  assign stream_sc_lmmu = stream_sc_ss;
  assign width_sc_lmmu = width_sc_ss;


  assign ready_lmmu_sc = ready_ss_sc && ready_lmmu;
  assign ready_smmu_sc = ready_ss_sc && ready_smmu;

  //------------------------
  // Stream State <-> MMU
  //------------------------

  logic [$clog2(SS_NUM_TABLES)-1:0] lmmu_idx_lmmu_ss;  // Load MMU Index
  logic [$clog2(SS_NUM_TABLES)-1:0] smmu_idx_smmu_ss;  // Store MMU Index
  logic trigger_lmmu_ss;
  logic trigger_smmu_ss;
  logic trigger_mmu_ss;

  logic addr_valid_ss_smmu;
  logic valid_ss_lmmu;
  logic addr_valid_ss_lmmu;


  logic [$clog2(SS_NUM_TABLES)-1:0] idx_ss_mmu;
  logic [STREAM_ID_WIDTH-1:0] stream_ss_mmu;
  logic valid_ss_mmu;
  logic [ADDRESS_WIDTH-1:0] addr_ss_mmu;
  logic addr_valid_ss_mmu;
  logic [1:0] width_ss_mmu;
  logic [STREAM_NUM_DIMS-1:0] completed_ss_mmu;
  logic vectorize_ss_mmu;
  logic last_ss_mmu;
  logic type_ss_mmu;



  assign trigger_mmu_ss = (type_ss_mmu && trigger_lmmu_ss) || (!type_ss_mmu && trigger_smmu_ss);

  assign addr_valid_ss_smmu = addr_valid_ss_mmu && !type_ss_mmu;

  assign valid_ss_lmmu = valid_ss_mmu && type_ss_mmu;
  assign addr_valid_ss_lmmu = addr_valid_ss_mmu && type_ss_mmu;

  //---------------------------------
  // Stream Iterator <-> Stream State
  //---------------------------------
  logic [STREAM_SIZE_WIDTH-1:0] res_iterations_si_ss;
  logic [WIDTH_MAX-1:0] res_accumulation_si_ss;
  logic last_si_ss;
  logic load_ena_si_ss;
  logic [STREAM_NUM_DIMS-1:0] load_dim_si_ss;

  logic [WIDTH_MAX-1:0] op1_ss_si;
  logic [WIDTH_MAX-1:0] op2_ss_si;
  logic mod_ss_si;
  logic [$clog2(STREAM_NUM_DIMS)-1:0] dim_ss_si;
  logic [1:0] width_ss_si;
  logic behaviour_ss_si;
  logic [STREAM_SIZE_WIDTH-1:0] iterations_ss_si;
  logic [STREAM_SIZE_WIDTH-1:0] size_ss_si;
  logic [STREAM_NUM_DIMS-1:0] completed_ss_si;
  logic [STREAM_NUM_DIMS-1:0] configured_ss_si;



  StreamConfigurator stream_configurator (
      .clk_i(clk),
      .ctrl_reset_i(ctrl_reset),

      .ready_i(ready_ss_sc),

      .se_ready_load_i (ready_lmmu_sc),
      .se_ready_store_i(ready_smmu_sc),

      .se_ack_o(ack_sc_ss),  // End of Configuration Acknowledgement
      .se_we_o(we_sc_ss),  // Configuration Write Enable
      .se_append_o(append_sc_ss),  // Append to current configuration

      .se_width_o(width_sc_ss),
      .se_stream_o(stream_sc_ss),  // Stream ID
      .se_type_o(type_sc_ss),  // Stream Load or Store
      .se_vec_we_o(vec_we_sc_ss),  // Vectorization Write Enable
      .se_vec_idx_o(vec_idx_sc_ss),  // Vectorization Index

      .se_dim_we_o(dim_we_sc_ss),  // Dimension Write Enable
      .se_dim_idx_o(dim_idx_sc_ss),  // Dimension Index
      .se_dim_offset_o(dim_offset_sc_ss),  // Dimension Offset
      .se_dim_stride_o(dim_stride_sc_ss),  // Dimension Stride
      .se_dim_size_o(dim_size_sc_ss),  // Dimension Size

      .se_mod_we_o(mod_we_sc_ss),  // Modifier Write Enable
      .se_mod_idx_o(mod_idx_sc_ss),  // Modifier Index
      .se_mod_target_o(mod_target_sc_ss),  // Modifier Target
      .se_mod_behaviour_o(mod_behaviour_sc_ss),  // Modifier Behaviour (Add/Sub)
      .se_mod_displacement_o(mod_displacement_sc_ss),  // Modifier Displacement
      .se_mod_size_o(mod_size_sc_ss),  // Modifier Size

      .cpu_valid_i(valid_cpu_sc),
      .cpu_sta_i(sta_cpu_sc),
      .cpu_end_i(end_cpu_sc),
      .cpu_type_i(type_cpu_sc),
      .cpu_width_i(width_cpu_sc),
      .cpu_stream_i(stream_cpu_sc),
      .cpu_mod_i(mod_cpu_sc),
      .cpu_vec_i(vec_cpu_sc),
      .cpu_dim_offset_i(dim_offset_cpu_sc),
      .cpu_dim_stride_i(dim_stride_cpu_sc),
      .cpu_dim_size_i(dim_size_cpu_sc),
      .cpu_mod_target_i(mod_target_cpu_sc),
      .cpu_mod_behaviour_i(mod_behaviour_cpu_sc),
      .cpu_mod_displacement_i(mod_displacement_cpu_sc),
      .cpu_mod_size_i(mod_size_cpu_sc),
      .cpu_ready_o(ready_sc_cpu)
  );


  LoadMMU load_mmu (
      .clk_i(clk),
      .ctrl_reset_i(ctrl_reset),

      .cfg_we_i(we_sc_lmmu),
      .cfg_stream_i(stream_sc_lmmu),
      .cfg_width_i(width_sc_lmmu),
      .cfg_ready_o(ready_lmmu),
      .cfg_mmu_idx_o(lmmu_idx_lmmu_ss),

      /* Source operands data channel */
      .rs_streamid_i(rs_streamid_cpu_lmmu),
      .rs_ready_i(rs_ready_cpu_lmmu),
      .rs_valid_o(rs_valid_lmmu_cpu),
      .rs_done_o(rs_done_lmmu_cpu),
      .rs_vecdata_o(rs_vecdata_lmmu_cpu),
      .rs_predicate_o(rs_predicate_lmmu_cpu),
      .rs_completed_o(rs_completed_lmmu_cpu),

      /* Stream State channel */
      .ss_mmu_idx_i(idx_ss_mmu),
      .ss_valid_i(valid_ss_lmmu),
      .ss_addr_i(addr_ss_mmu),
      .ss_addr_valid_i(addr_valid_ss_lmmu),
      .ss_width_i(width_ss_mmu),
      .ss_completed_i(completed_ss_mmu),
      .ss_vectorize_i(vectorize_ss_mmu),
      .ss_last_i(last_ss_mmu),
      .ss_trigger_o(trigger_lmmu_ss),

      /* AXI Read Address Channel */
      .axi_ar_ready_i(ar_ready_axi_mmu),
      .axi_ar_valid_o(ar_valid_mmu_axi),
      .axi_ar_addr_o (ar_addr_mmu_axi),
      .axi_ar_len_o  (ar_len_mmu_axi),
      .axi_ar_size_o (ar_size_mmu_axi),
      .axi_ar_burst_o(ar_burst_mmu_axi),

      /* AXI Read Data Channel */
      .axi_r_data_i (r_data_axi_mmu),
      .axi_r_valid_i(r_valid_axi_mmu),
      .axi_r_last_i (r_last_axi_mmu),
      .axi_r_resp_i (r_resp_axi_mmu),
      .axi_r_ready_o(r_ready_mmu_axi),


      /* Performance Counters */
      .hpc_lmmu_stall_lf_o(hpc_lmmu_stall_lf),
      .hpc_lmmu_stall_lrq_o(hpc_lmmu_stall_lrq),
      .hpc_lmmu_stall_o(hpc_lmmu_stall),
      .hpc_lmmu_commit_o(hpc_lmmu_commit),
      .hpc_lmmu_stall_llb_o(hpc_lmmu_stall_llb),
      .hpc_ops_load_o(hpc_ops_load)
  );

  StoreMMU store_mmu (
      .clk_i(clk),
      .ctrl_reset_i(ctrl_reset),

      .cfg_we_i(we_sc_smmu),
      .cfg_stream_i(stream_sc_smmu),
      .cfg_width_i(width_sc_smmu),
      .cfg_ready_o(ready_smmu),
      .cfg_mmu_idx_o(smmu_idx_smmu_ss),

      /* Destination operands data channel */
      .rd_valid_i(rd_valid_cpu_smmu),
      .rd_streamid_i(rd_streamid_cpu_smmu),
      .rd_vecdata_i(rd_vecdata_cpu_smmu),
      .rd_predicate_i(rd_predicate_cpu_smmu),
      .rd_ready_o(rd_ready_smmu_cpu),
      .rd_width_o(rd_width_smmu_cpu),
      .rd_done_o(rd_done_smmu_cpu),
      .rd_store_request_successful_o(rd_store_request_successful_smmu_cpu),

      /* Stream State channel */
      .ss_mmu_idx_i(idx_ss_mmu),
      .ss_addr_i(addr_ss_mmu),
      .ss_addr_valid_i(addr_valid_ss_smmu),
      .ss_last_i(last_ss_mmu),
      .ss_trigger_o(trigger_smmu_ss),

      /* AXI Write Address Channel */
      .axi_aw_ready_i(aw_ready_axi_mmu),
      .axi_aw_valid_o(aw_valid_mmu_axi),
      .axi_aw_addr_o (aw_addr_mmu_axi),
      .axi_aw_len_o  (aw_len_mmu_axi),
      .axi_aw_size_o (aw_size_mmu_axi),
      .axi_aw_burst_o(aw_burst_mmu_axi),

      /* AXI Write Data Channel */
      .axi_w_ready_i(w_ready_axi_mmu),
      .axi_w_valid_o(w_valid_mmu_axi),
      .axi_w_data_o (w_data_mmu_axi),
      .axi_w_strb_o (w_strb_mmu_axi),
      .axi_w_last_o (w_last_mmu_axi),

      /* AXI Write Response Channel */
      .axi_b_resp_i (b_resp_axi_mmu),
      .axi_b_valid_i(b_valid_axi_mmu),
      .axi_b_ready_o(b_ready_mmu_axi),

      /* Performance Counters */
      .hpc_smmu_commit_o(hpc_smmu_commit),
      .hpc_smmu_stall_o (hpc_smmu_stall),
      .hpc_ops_store_o  (hpc_ops_store)
  );


  StreamState #(
      .STREAM_NUM_DIMS(STREAM_NUM_DIMS),
      .STREAM_NUM_MODS(STREAM_NUM_MODS),
      .SS_NUM_TABLES(SS_NUM_TABLES),
      .STREAM_OFFSET_WIDTH(STREAM_OFFSET_WIDTH),
      .STREAM_STRIDE_WIDTH(STREAM_STRIDE_WIDTH),
      .STREAM_SIZE_WIDTH(STREAM_SIZE_WIDTH),
      .STREAM_ID_WIDTH(STREAM_ID_WIDTH)
  ) stream_state (
      .clk_i(clk),
      .ctrl_reset_i(ctrl_reset),

      .cfg_ack_i(ack_sc_ss),
      .cfg_new_i(we_sc_ss),
      .cfg_append_i(append_sc_ss),
      .cfg_width_i(width_sc_ss),
      .cfg_stream_i(stream_sc_ss),
      .cfg_type_i(type_sc_ss),
      .cfg_vec_we_i(vec_we_sc_ss),
      .cfg_vec_idx_i(vec_idx_sc_ss),
      .cfg_dim_we_i(dim_we_sc_ss),
      .cfg_dim_idx_i(dim_idx_sc_ss),
      .cfg_dim_offset_i(dim_offset_sc_ss),
      .cfg_dim_stride_i(dim_stride_sc_ss),
      .cfg_dim_size_i(dim_size_sc_ss),
      .cfg_mod_we_i(mod_we_sc_ss),
      .cfg_mod_idx_i(mod_idx_sc_ss),
      .cfg_mod_target_i(mod_target_sc_ss),
      .cfg_mod_behaviour_i(mod_behaviour_sc_ss),
      .cfg_mod_displacement_i(mod_displacement_sc_ss),
      .cfg_mod_size_i(mod_size_sc_ss),
      .cfg_lmmu_idx_i(lmmu_idx_lmmu_ss),
      .cfg_smmu_idx_i(smmu_idx_smmu_ss),
      .cfg_ready_o(ready_ss_sc),

      .mmu_trigger_i(trigger_mmu_ss),
      .mmu_idx_o(idx_ss_mmu),
      .mmu_stream_o(stream_ss_mmu),
      .mmu_valid_o(valid_ss_mmu),
      .mmu_addr_o(addr_ss_mmu),
      .mmu_addr_valid_o(addr_valid_ss_mmu),
      .mmu_width_o(width_ss_mmu),
      .mmu_completed_o(completed_ss_mmu),
      .mmu_vectorize_o(vectorize_ss_mmu),
      .mmu_last_o(last_ss_mmu),
      .mmu_type_o(type_ss_mmu),

      .si_res_iterations_i(res_iterations_si_ss),
      .si_res_accumulation_i(res_accumulation_si_ss),
      .si_last_i(last_si_ss),
      .si_load_ena_i(load_ena_si_ss),
      .si_load_dim_i(load_dim_si_ss),
      .si_op1_o(op1_ss_si),
      .si_op2_o(op2_ss_si),
      .si_mod_o(mod_ss_si),
      .si_dim_o(dim_ss_si),
      .si_width_o(width_ss_si),
      .si_behaviour_o(behaviour_ss_si),
      .si_iterations_o(iterations_ss_si),
      .si_size_o(size_ss_si),
      .si_completed_o(completed_ss_si),
      .si_configured_o(configured_ss_si),

      .hpc_ss_desc_o(hpc_ss_desc)
  );



  StreamIterator #(
      .STREAM_NUM_DIMS(STREAM_NUM_DIMS),
      .STREAM_OFFSET_WIDTH(STREAM_OFFSET_WIDTH),
      .STREAM_STRIDE_WIDTH(STREAM_STRIDE_WIDTH),
      .STREAM_SIZE_WIDTH(STREAM_SIZE_WIDTH)

  ) stream_iterator (
      .src_op1_i(op1_ss_si),
      .src_op2_i(op2_ss_si),
      .val_mod_i(mod_ss_si),
      .val_dim_i(dim_ss_si),
      .val_width_i(width_ss_si),
      .val_behaviour_i(behaviour_ss_si),
      .val_iterations_i(iterations_ss_si),
      .val_size_i(size_ss_si),
      .val_completed_i(completed_ss_si),
      .val_configured_i(configured_ss_si),

      .res_iterations_o(res_iterations_si_ss),
      .res_accumulation_o(res_accumulation_si_ss),
      .val_last_o(last_si_ss),
      .load_enable_o(load_ena_si_ss),
      .load_dim_o(load_dim_si_ss)
  );



endmodule
