
`timescale 1ns / 1ps

package engine_pkg;

  // Stream Configuration Parameters

  localparam STREAM_NUM_DIMS = 5;
  localparam STREAM_NUM_MODS = 3;

  localparam STREAM_OFFSET_WIDTH = 33;
  localparam STREAM_STRIDE_WIDTH = 32;
  localparam STREAM_SIZE_WIDTH = 32;

  localparam LRQ_NUM_TABLES = 64;
  localparam LRQ_NUM_REQUESTS = 2;
  
  localparam LLB_NUM_TABLES = 4;
  localparam LLB_NUM_BYTES = 64;

  localparam AXI_R_DATA_WIDTH = 64;
  localparam AXI_W_DATA_WIDTH = 64;
  localparam AXI_READ_DELAY = 5;
  localparam AXI_WRITE_DELAY = 5;

  localparam LMMU_NUM_VECS = 4;
  localparam SMMU_NUM_ADDRESSES = 32;

  localparam MAX_NUM_LOAD_STREAMS = 4;
  localparam MAX_NUM_STORE_STREAMS = 4;

  localparam FU_NUM_VECTOR_REGISTERS = 32;
  localparam FU_NUM_SCALAR_REGISTERS = 32;
  localparam FU_VECTOR_RF_WIDTH = 1024;
  localparam FU_SCALAR_RF_WIDTH = 32;
  localparam FU_NUM_HW_INSTRUCTIONS = 8;

  localparam IM_DATA_WIDTH = 32;
  localparam IM_ADDR_WIDTH = 7;

  localparam DM_DATA_WIDTH = 8 * LLB_NUM_BYTES;
  localparam DM_ADDR_WIDTH = 10;

  ///////////////////////////////////////////////////

  /* Internal parameters, calculated using external ones */
  localparam VEC_WIDTH = FU_VECTOR_RF_WIDTH;
  localparam VEC_NUM_BYTES = VEC_WIDTH / 8;  // 16

  localparam SS_NUM_TABLES = MAX_NUM_LOAD_STREAMS + MAX_NUM_STORE_STREAMS;  // 4
  localparam LF_NUM_BYTES = LMMU_NUM_VECS * VEC_NUM_BYTES;  // 64
  localparam LF_NUM_TABLES = 2 * MAX_NUM_LOAD_STREAMS;  // 2
  localparam SMMU_NUM_TABLES = 2 * MAX_NUM_STORE_STREAMS;  // 2 
  localparam BIGGER_NUM_TABLES = (LF_NUM_TABLES > SMMU_NUM_TABLES) ? LF_NUM_TABLES : SMMU_NUM_TABLES; // 2


  localparam DM_DATA_NUM_BYTES = DM_DATA_WIDTH / 8;  // 32
  localparam IM_DATA_NUM_BYTES = IM_DATA_WIDTH / 8;  // 4
  localparam NUM_SRC_OPERANDS = 4;
  localparam STREAM_ID_WIDTH = 32;
  localparam STREAM_ADDR_WIDTH = FU_SCALAR_RF_WIDTH;
  localparam FU_VECTOR_NUM_BYTES = FU_VECTOR_RF_WIDTH / 8;


  /********************************************************/
  /* Internal parameters, calculated using external ones */

  localparam DIM_OFFSET_WIDTH = FU_SCALAR_RF_WIDTH;
  localparam DIM_STRIDE_WIDTH = FU_SCALAR_RF_WIDTH;
  localparam DIM_SIZE_WIDTH = FU_SCALAR_RF_WIDTH;

  localparam MOD_TARGET_WIDTH = 2;
  localparam MOD_BEHAVIOUR_WIDTH = 1;
  localparam MOD_DISPLACEMENT_WIDTH = FU_SCALAR_RF_WIDTH;
  localparam MOD_SIZE_WIDTH = FU_SCALAR_RF_WIDTH;

  localparam XLEN = 32;

  localparam WIDTH_MAX = 32;

  localparam ADDRESS_WIDTH = STREAM_ADDR_WIDTH;
  localparam ADDRESS_TAG_WIDTH = ADDRESS_WIDTH;

  localparam MEMORY_READ_DELAY = 5;
  localparam MEMORY_WRITE_DELAY = 5;

  localparam MEMORY_NUM_BYTES = DM_DATA_WIDTH / 8;  // 8
  localparam MEMORY_OFFSET_WIDTH = $clog2(MEMORY_NUM_BYTES);  // 3

  localparam AXI_W_NUM_BYTES = AXI_W_DATA_WIDTH / 8;  // 16
  localparam AXI_W_OFFSET_WIDTH = $clog2(AXI_W_NUM_BYTES);  // 4

  localparam AXI_ADDR_WIDTH = 32;
  localparam AXI_LEN_WIDTH = 8;
  localparam AXI_SIZE_WIDTH = 3;
  localparam AXI_BURST_WIDTH = 2;

  localparam COUNT = MEMORY_NUM_BYTES / AXI_W_NUM_BYTES;

  localparam MEMORY_DATA_WIDTH = DM_DATA_WIDTH;
  localparam MEMORY_ADDR_WIDTH = DM_ADDR_WIDTH;





  typedef logic [XLEN-1:0] xlen_t;


  typedef enum logic [1:0] {
    _CONFIG = 2'b00,
    WAIT    = 2'b01,
    COMMIT  = 2'b10
  } config_state_t;

  typedef enum logic {
    STORE = 1'b0,
    LOAD  = 1'b1
  } stream_type_t;

  typedef enum logic [1:0] {
    ADDRESS = 2'b00,
    ITERATE = 2'b01,
    APPLY   = 2'b10,
    UPDATE  = 2'b11
  } stream_state_t;

  typedef enum logic {
    FALSE = 1'b0,
    TRUE  = 1'b1
  } bool_t;

  typedef enum logic [1:0] {
    TARGET_SIZE   = 2'b0,
    TARGET_STRIDE = 2'b1,
    TARGET_OFFSET = 2'b10
  } target_t;

  typedef enum logic [1:0] {
    ITER_DIM = 2'b0,
    ITER_MOD_SIZE = 2'b1,
    ITER_MOD_STRIDE = 2'b10,
    ITER_MOD_OFFSET = 2'b11
  } iter_t;

  typedef enum logic {
    APPL_DIM_OFFSET = 1'b0,
    APPL_MOD_OFFSET = 1'b1
  } appl_t;

  typedef enum logic {
    BEHAVIOUR_INC = 1'b0,
    BEHAVIOUR_DEC = 1'b1
  } behaviour_t;


  typedef struct packed {
    logic [STREAM_OFFSET_WIDTH-1:0] offset;  // Offset from Base Address
    logic [STREAM_STRIDE_WIDTH-1:0] stride;  // Stride between Iterations
    logic [STREAM_SIZE_WIDTH-1:0]   size;    // Total Iteration Number

  } sc_dim_descriptor_t;

  typedef struct packed {
    logic [1:0]                         target;        // Target to Modify
    logic [MOD_BEHAVIOUR_WIDTH-1:0]     behaviour;     // 0 - Subtract, 1 - Add
    logic [STREAM_STRIDE_WIDTH-1:0]     displacement;  // Value to Add or Subtract
    logic [STREAM_SIZE_WIDTH-1:0]       size;          // Total Iteration Number
    logic [$clog2(STREAM_NUM_DIMS)-1:0] dimension;     // Dimension Index

  } sc_mod_descriptor_t;



  typedef struct packed {
    logic [STREAM_OFFSET_WIDTH-1:0] offset;  // Offset from Base Address
    logic [STREAM_STRIDE_WIDTH-1:0] stride;  // Stride between Iterations
    logic [STREAM_SIZE_WIDTH-1:0]   size;    // Total Iteration Number

    logic [STREAM_OFFSET_WIDTH-1:0] acc_offset;
    logic [STREAM_STRIDE_WIDTH-1:0] acc_stride;
    logic [STREAM_SIZE_WIDTH-1:0]   acc_size;

    logic [STREAM_SIZE_WIDTH-1:0] iterations;
    logic configured;
    logic vectorize;
    logic complete;

  } base_desc_t;

  typedef struct packed {
    logic [STREAM_STRIDE_WIDTH-1:0] displacement;  // Value to Add or Subtract
    logic [STREAM_SIZE_WIDTH-1:0]   size;          // Total Iteration Number
    logic [MOD_BEHAVIOUR_WIDTH-1:0] behaviour;     // 0 - Subtract, 1 - Add
    logic [STREAM_SIZE_WIDTH-1:0]   iterations;
    logic                           configured;    // Configuration Complete

  } static_mod_t;


  typedef struct packed {
    base_desc_t [STREAM_NUM_DIMS-1:0] descriptors;
    static_mod_t [STREAM_NUM_DIMS-1:0][STREAM_NUM_MODS-1:0] modifiers;

    logic [STREAM_ID_WIDTH-1:0] stream;  // Stream ID
    logic [1:0] width;
    stream_type_t _type;  // Stream Load or Store

    logic [1:0] state;
    logic       valid;
    logic       ready;

    logic [$clog2(SS_NUM_TABLES)-1:0] mmu_idx;
    logic [STREAM_NUM_DIMS-1:0] active_dim;
    logic [STREAM_NUM_DIMS-1:0][STREAM_OFFSET_WIDTH-1:0] mod_acc_offset;


  } stream_table_t;


  typedef struct packed {
    logic [STREAM_OFFSET_WIDTH-1:0] offset;  // Offset from Base Address
    logic [STREAM_STRIDE_WIDTH-1:0] stride;  // Stride between Iterations
    logic [STREAM_SIZE_WIDTH-1:0]   size;    // Total Iteration Number
  } my_struct_t;



  typedef struct packed {
    logic [LLB_NUM_BYTES-1:0][7 : 0] bytes;
    logic [ADDRESS_TAG_WIDTH-1 : 0] address_tag;
    logic _config;
    logic valid;
  } llb_table_bundle_t;

  typedef enum logic {
    ARREADY = 1'b0,
    RVALID  = 1'b1
  } lmmu_state_t;

  typedef enum logic [1:0] {
    WAIT_PAIR = 2'b00,
    WAIT_AWREADY = 2'b01,
    WAIT_WREADY = 2'b10,
    WAIT_BVALID = 2'b11
  } smmu_state_t;

  typedef struct packed {
    logic [ADDRESS_WIDTH-1:0] address;
    logic valid;
  } address_fifo_element_t;

  typedef struct packed {
    logic [7:0] data;
    logic valid;
  } data_fifo_element_t;


  typedef struct packed {

    address_fifo_element_t [SMMU_NUM_ADDRESSES-1:0] addressFIFO;
    data_fifo_element_t [VEC_NUM_BYTES-1:0] dataFIFO;

    logic [$clog2(SMMU_NUM_ADDRESSES)-1:0] address_write_ptr;
    logic [$clog2(SMMU_NUM_ADDRESSES)-1:0] address_read_ptr;
    logic [$clog2(VEC_NUM_BYTES)-1:0] data_ptr;

    logic ss_done;

    logic [STREAM_ID_WIDTH-1:0] stream;
    logic [1:0] _width;
    logic valid;
  } store_fifo_bundle_t;



  typedef struct packed {
    logic [$clog2(LLB_NUM_BYTES)-1:0] offset_llb;
    logic [$clog2(LF_NUM_BYTES)-1:0] offset_lf;
    logic [1:0] _width;
    logic [$clog2(LF_NUM_TABLES)-1:0] mmu_idx;
    logic valid;
  } lrq_request_bundle_t;


  typedef struct packed {
    lrq_request_bundle_t [LRQ_NUM_REQUESTS-1:0] requests;
    logic [ADDRESS_TAG_WIDTH-1:0] address_tag;
    logic requested;
  } lrq_table_bundle_t;





  typedef struct packed {
    logic [VEC_NUM_BYTES-1:0][7:0] req_data;
    logic [VEC_NUM_BYTES-1:0] req_predicate;
    logic [VEC_NUM_BYTES-1:0] req_valid;

    logic reserved;
    logic [STREAM_NUM_DIMS-1:0] completed;
  } load_vector_bundle_t;



  typedef struct packed {
    load_vector_bundle_t [LMMU_NUM_VECS-1:0] vecs;
    logic [$clog2(LMMU_NUM_VECS) - 1:0] ptr_vec_read;
    logic [$clog2(LMMU_NUM_VECS) - 1:0] idx_write_vec;
    logic [$clog2(VEC_NUM_BYTES)-1:0] idx_write_byte;

    logic ss_done;

    logic [STREAM_ID_WIDTH-1:0] stream;
    logic [1:0] _width;
    logic valid;
  } load_fifo_bundle_t;

endpackage
