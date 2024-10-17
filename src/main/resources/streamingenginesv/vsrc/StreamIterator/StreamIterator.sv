`timescale 1ns / 1ps

module StreamIterator #(
    STREAM_NUM_DIMS,
    STREAM_OFFSET_WIDTH,
    STREAM_STRIDE_WIDTH,
    STREAM_SIZE_WIDTH
) (
    input logic [31:0] src_op1_i,
    input logic [31:0] src_op2_i,

    input logic [$clog2(STREAM_NUM_DIMS)-1:0] val_dim_i,
    input logic val_mod_i,
    input logic [1:0] val_width_i,
    input logic val_behaviour_i,
    input logic [STREAM_SIZE_WIDTH-1:0] val_iterations_i,
    input logic [STREAM_SIZE_WIDTH-1:0] val_size_i,
    input logic [STREAM_NUM_DIMS-1:0] val_completed_i,
    input logic [STREAM_NUM_DIMS-1:0] val_configured_i,

    output logic val_last_o,
    output logic [STREAM_SIZE_WIDTH-1:0] res_iterations_o,
    output logic [31:0] res_accumulation_o,

    output logic load_enable_o,
    output logic [STREAM_NUM_DIMS-1:0] load_dim_o
);

  // Internal Signal
  int i;
  logic [31:0] src_op1, src_op2, next_accumulation;
  logic load_enable, val_last;
  logic [STREAM_NUM_DIMS-1:0] next_valid_dim, next_oh_dim;
  logic [STREAM_SIZE_WIDTH-1:0] next_iterations;

  /* The current number of iterations is by default decremented */
  assign next_iterations = val_iterations_i - 1;

  /* It is the last iteration when the next iterations value is lower than one */
  assign val_last = val_dim_i == 0 ? (val_iterations_i == 0 || next_iterations < 1) : (val_iterations_i == 0 || next_iterations <= 1);

  /* The Stream Iterator detects if the active state (dimension) of the
   * stream should be updated. This happens when the size is zero or
   * when the lowest dimension has completed all its iterations
   */

  assign load_enable = val_size_i == 0 || (val_last && val_dim_i == 0);

  /* The next active state (dimension) is always determined according
   * to the configured and completed dimensions of the current stream
   */

  always_comb begin
    for (i = 0; i < STREAM_NUM_DIMS; i++) begin
      next_valid_dim[i] = !(i <= val_dim_i) && val_configured_i[i] && !val_completed_i[i];
      next_oh_dim[i] = 0;
    end
    for (i = 0; i < STREAM_NUM_DIMS; i++) begin
      if (next_valid_dim[i]) begin
        next_oh_dim[i] = 1;
        break;
      end
    end
  end

  /* Converting the input source operands to signed representations */
  assign src_op1 = signed'(src_op1_i);

  /* When the second source operand is a stride, its value is shifted
   * according to the elements width. This way there is always memory
   * alignment of the data elements
   */

  assign src_op2 = val_mod_i ? signed'(src_op2_i) : 
                              val_width_i == 2'b00 ? signed'(src_op2_i) :
                              val_width_i == 2'b01 ? signed'(src_op2_i) << 1 :
                              val_width_i == 2'b10 ? signed'(src_op2_i) << 2 :
                              val_width_i == 2'b11 ? signed'(src_op2_i) << 3 : 0;

  assign next_accumulation = !val_behaviour_i ? src_op1 + src_op2 : src_op1 - src_op2;



  assign res_accumulation_o = unsigned'(next_accumulation);
  assign res_iterations_o = unsigned'(next_iterations);
  assign load_dim_o = next_oh_dim;
  assign load_enable_o = load_enable;
  assign val_last_o = val_last;

endmodule
